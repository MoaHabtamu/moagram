#!/usr/bin/env python3
"""Execute production request guards with a recording transport and no network."""
from pathlib import Path
import subprocess
import tempfile


repository = Path(__file__).resolve().parents[2]
source = (repository / "TMessagesProj/src/main/java/org/telegram/tgnet/ConnectionsManager.java").read_text()
signature = "private void sendRequestInternal("
start = source.index(signature)
end = source.index("        final LocalRepeatController.Preparation localRepeat", start)
guards = source[start:end] + "        sent = object;\n    }"
read_types = ["TL_messages_readHistory", "TL_channels_readHistory", "TL_messages_readDiscussion",
              "TL_messages_readSavedHistory", "TL_messages_readEncryptedHistory", "TL_messages_readMessageContents",
              "TL_channels_readMessageContents", "TL_messages_readMentions", "TL_messages_readReactions",
              "TL_messages_readPollVotes"]
send_types = ["TL_messages_sendMessage", "TL_messages_sendMedia", "TL_messages_forwardMessages",
              "TL_messages_sendMultiMedia", "TL_messages_sendInlineBotResult"]
types = "\n".join(f"static class {name} extends TLObject {{}}" for name in read_types)
types += "\n" + "\n".join(f"static class {name} extends MessageSend {{}}" for name in send_types)
java = r"""
import java.util.concurrent.atomic.AtomicBoolean;
public class GhostRequestTest {
    static class TLObject { boolean freed; void freeResources() { freed = true; } }
    static class MessageSend extends TLObject { boolean background; }
    static class TLRPC {
        static class TL_boolTrue extends TLObject {}
        %s
    }
    static class TL_stories {
        static class TL_stories_readStories extends TLObject {}
        static class TL_stories_incrementStoryViews extends TLObject {}
    }
    interface RequestDelegate { void run(TLObject response, Object error); }
    interface RequestDelegateTimestamp { void run(TLObject response, Object error, int timestamp); }
    interface QuickAckDelegate {}
    interface WriteToSocketDelegate {}
    static class BuildVars { static boolean LOGS_ENABLED = false; }
    static class FileLog { static void d(String message) {} }
    static class Queue { void postRunnable(Runnable callback) { callback.run(); } }
    static class Utilities { static Queue stageQueue = new Queue(); }
    static class GhostModeController {
        static GhostModeController[] accounts = {new GhostModeController(), new GhostModeController()};
        boolean reads, stories, stealth, online;
        static GhostModeController getInstance(int account) { return accounts[account]; }
        boolean isGhostRead() { return reads; }
        boolean isStoriesHidden() { return stories; }
        boolean isStealthSend() { return stealth; }
        boolean isOnlineHidden() { return online; }
    }
    static class Manager {
        int currentAccount;
        TLObject sent;
        AtomicBoolean allowNextGhostReadReceipt = new AtomicBoolean();
        Manager(int account) { currentAccount = account; }
        int getCurrentTime() { return 1234; }
        void send(TLObject object, RequestDelegate callback) {
            sent = null;
            sendRequestInternal(object, callback, null, null, null, 0, 0, 0, false, 1);
        }
        %s
    }
    static int checks;
    static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    static TLObject[] reads() { return new TLObject[] {%s}; }
    static MessageSend[] sends() { return new MessageSend[] {%s}; }
    public static void main(String[] args) {
        Manager manager = new Manager(0);
        GhostModeController config = GhostModeController.getInstance(0);
        for (TLObject request : reads()) {
            manager.send(request, null);
            check(manager.sent == request && !request.freed, "normal read was suppressed");
        }
        config.reads = true;
        for (TLObject request : reads()) {
            int[] callbacks = {0};
            manager.send(request, (response, error) -> callbacks[0]++);
            check(manager.sent == null && request.freed, "ghost read reached transport");
            check(callbacks[0] == 1, "suppressed read callback did not complete once");
        }
        manager.allowNextGhostReadReceipt.set(true);
        TLObject ordinary = new TLObject();
        manager.send(ordinary, null);
        check(manager.sent == ordinary && manager.allowNextGhostReadReceipt.get(), "unrelated request consumed read allowance");
        TLObject allowed = reads()[0];
        manager.send(allowed, null);
        check(manager.sent == allowed && !allowed.freed, "explicit read allowance was lost");
        manager.send(reads()[0], null);
        check(manager.sent == null && !manager.allowNextGhostReadReceipt.get(), "read allowance must be one-shot");
        Manager other = new Manager(1);
        TLObject otherRead = reads()[0];
        other.send(otherRead, null);
        check(other.sent == otherRead, "ghost settings leaked to another account");
        config.stories = true;
        for (TLObject request : new TLObject[] {new TL_stories.TL_stories_readStories(), new TL_stories.TL_stories_incrementStoryViews()}) {
            int[] callbacks = {0};
            manager.send(request, (response, error) -> {
                check(response instanceof TLRPC.TL_boolTrue && error == null, "story callback changed");
                callbacks[0]++;
            });
            check(manager.sent == null && request.freed && callbacks[0] == 1, "story view reached transport");
        }
        for (int mode = 0; mode < 3; mode++) {
            config.stealth = mode == 1;
            config.online = mode == 2;
            for (MessageSend request : sends()) {
                manager.send(request, null);
                check(manager.sent == request && request.background == (mode != 0), "send background flag changed");
            }
        }
        int[] timestampCallbacks = {0};
        manager.sent = null;
        manager.sendRequestInternal(reads()[0], null, (response, error, timestamp) -> {
            check(timestamp == 1234 && response == null && error == null, "timestamp callback changed");
            timestampCallbacks[0]++;
        }, null, null, 0, 0, 0, false, 1);
        check(timestampCallbacks[0] == 1 && manager.sent == null, "timestamp callback did not complete");
        System.out.println("Ghost request guards: " + checks + " checks passed");
    }
}
""" % (types, guards, ", ".join(f"new TLRPC.{name}()" for name in read_types),
       ", ".join(f"new TLRPC.{name}()" for name in send_types))

with tempfile.TemporaryDirectory(prefix="moagram-ghost-requests-") as directory:
    path = Path(directory) / "GhostRequestTest.java"
    path.write_text(java)
    subprocess.run(["javac", "-d", directory, str(path)], check=True)
    subprocess.run(["java", "-cp", directory, "GhostRequestTest"], check=True)

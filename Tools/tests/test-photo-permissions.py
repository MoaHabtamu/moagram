#!/usr/bin/env python3
"""Execute PhotoViewer's production permission expressions and rich-media helper."""
from pathlib import Path
import re
import subprocess
import tempfile


repository = Path(__file__).resolve().parents[2]
source = (repository / "TMessagesProj/src/main/java/org/telegram/ui/PhotoViewer.java").read_text()
sharing = re.search(r"allowShare = (SharedConfig\.allowSaveRestrictedMedia[^;]+);", source)[1]
secure = re.search(
    r"if \((!SharedConfig\.allowSaveRestrictedMedia && \(.*?)\) \{\s*"
    r"windowLayoutParams.flags \|= WindowManager.LayoutParams.FLAG_SECURE;", source, re.S)[1]
start = source.index("private boolean isPageBlocksNoForwards(")
opening = source.index("{", start)
end, depth = opening + 1, 1
while depth:
    depth += (source[end] == "{") - (source[end] == "}")
    end += 1
helper = source[start:end]

java = r"""
public class PhotoPermissionsTest {
    static class SharedConfig { static boolean allowSaveRestrictedMedia; }
    static class Owner { boolean noforwards; }
    static class MessageObject {
        Owner messageOwner = new Owner();
        boolean revealed;
        long dialog;
        long getDialogId() { return dialog; }
        boolean hasRevealedExtendedMedia() { return revealed; }
    }
    static class PageBlocksAdapter {
        Object parent;
        Object getParentObject() { return parent; }
    }
    static class Chat {
        Object getCurrentEncryptedChat() { return this; }
    }
    static class MessagesController {
        static final MessagesController instance = new MessagesController();
        boolean peer, avatar;
        static MessagesController getInstance(int account) { return instance; }
        boolean isPeerNoForwards(long dialog) { return dialog == 2 ? avatar : peer; }
    }
    int currentAccount;
    long currentDialogId = 1, avatarsDialogId;
    MessageObject currentMessageObject, messageObject;
    PageBlocksAdapter pageBlocksAdapter;
    Chat chatActivity;
    boolean mayShare() { return %s; }
    boolean requiresSecureWindow() { return %s; }
    %s
    static int checks;
    static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        PhotoPermissionsTest viewer = new PhotoPermissionsTest();
        MessagesController controller = MessagesController.instance;
        for (int flags = 0; flags < 256; flags++) {
            boolean override = (flags & 1) != 0, peer = (flags & 2) != 0,
                present = (flags & 4) != 0, revealed = (flags & 8) != 0,
                encrypted = (flags & 16) != 0, avatar = (flags & 32) != 0,
                article = (flags & 64) != 0, owner = (flags & 128) != 0;
            SharedConfig.allowSaveRestrictedMedia = override;
            controller.peer = peer;
            controller.avatar = avatar;
            viewer.currentMessageObject = viewer.messageObject = present ? new MessageObject() : null;
            if (present) {
                viewer.messageObject.revealed = revealed;
                viewer.messageObject.messageOwner.noforwards = owner;
            }
            viewer.chatActivity = encrypted ? new Chat() : null;
            viewer.avatarsDialogId = avatar ? 2 : 0;
            viewer.pageBlocksAdapter = article ? new PageBlocksAdapter() : null;
            if (article) {
                MessageObject parent = new MessageObject();
                parent.messageOwner.noforwards = true;
                viewer.pageBlocksAdapter.parent = parent;
            }
            boolean shareExpected = override || !(peer || present && revealed || article);
            boolean secureExpected = !override && (encrypted || avatar || present && (peer || owner || revealed) || article);
            check(viewer.mayShare() == shareExpected, "sharing flags=" + flags);
            check(viewer.requiresSecureWindow() == secureExpected, "secure window flags=" + flags);
        }
        controller.peer = false;
        PageBlocksAdapter adapter = new PageBlocksAdapter();
        check(!viewer.isPageBlocksNoForwards(adapter), "missing parent");
        adapter.parent = new Object();
        check(!viewer.isPageBlocksNoForwards(adapter), "non-message parent");
        MessageObject parent = new MessageObject();
        adapter.parent = parent;
        check(!viewer.isPageBlocksNoForwards(adapter), "unprotected rich media");
        parent.revealed = true;
        check(viewer.isPageBlocksNoForwards(adapter), "revealed rich media");
        parent.revealed = false;
        controller.peer = true;
        check(viewer.isPageBlocksNoForwards(adapter), "protected rich-media peer");
        parent.messageOwner = null;
        check(!viewer.isPageBlocksNoForwards(adapter), "missing message owner");
        System.out.println("PhotoViewer permission checks passed: " + checks);
    }
}
""" % (sharing, secure, helper)

with tempfile.TemporaryDirectory() as directory:
    path = Path(directory) / "PhotoPermissionsTest.java"
    path.write_text(java)
    subprocess.run(["javac", "-d", directory, str(path)], check=True)
    subprocess.run(["java", "-cp", directory, "PhotoPermissionsTest"], check=True)

package org.telegram.messenger;

import android.content.SharedPreferences;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.PriorityQueue;
import org.telegram.tgnet.RequestDelegate;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_update;
import org.telegram.tgnet.SerializedData;
import org.json.JSONArray;
import org.json.JSONObject;

/** Runs the actual controller from request interception through persistence and replenishment. */
public class LocalRepeatQueueTest {
    private static int checks;

    public static void main(String[] args) {
        Server server = new Server();
        SharedPreferences preferences = memoryPreferences();
        server.controller = new LocalRepeatController(0, preferences, server);
        int firstDate = server.now() + 3600;
        TLRPC.TL_messages_sendMessage seed = text(12, firstDate, 1);
        // SendMessagesHelper populates this object but leaves flag 13 OFF for normal messages.
        seed.send_as = new TLRPC.TL_inputPeerSelf();
        server.send(seed, (response, error) -> check(error == null, "Seed scheduling failed"));
        server.advance(30000);
        check(server.messages.size() == 7, "Expected 7 queued messages; got " + server.messages.size()
                + ", paused=" + server.controller.getRepeats(12).get(0).paused);
        check(server.controller.getRepeats(12).get(0).queued == 7, "Manager queue count is incorrect");
        for (int i = 0; i < 7; i++) {
            check(server.hasDate(12, firstDate + i * 86400), "Missing future occurrence " + i);
        }

        // Restart against the persisted preferences and the same server queue.
        server.controller = new LocalRepeatController(0, preferences, server);
        server.advance(30000);
        check(server.messages.size() == 7, "Restart duplicated scheduled messages");
        check(server.controller.getRepeats(12).size() == 1, "Restart lost the repeat rule");

        server.messages.remove(server.messages.keySet().iterator().next());
        server.advance(90000);
        check(server.messages.size() == 7, "Queue did not replenish after an occurrence left");
        check(server.hasDate(12, firstDate + 7 * 86400), "Replenishment used the wrong date");

        long id = server.controller.getRepeats(12).get(0).id;
        server.controller.stop(id);
        server.advance(30000);
        check(server.messages.isEmpty(), "Stop did not remove queued messages");
        check(server.controller.getRepeats(12).isEmpty(), "Stopped rule is still active");

        // The three daily rules discussed with the user must maintain 21 distinct occurrences.
        for (int minute = 0; minute < 3; minute++) {
            server.send(text(12, firstDate + minute * 60, 100 + minute), (response, error) -> {});
        }
        server.advance(90000);
        check(server.messages.size() == 21, "Three daily rules did not queue 21 messages");
        for (int day = 0; day < 7; day++) for (int minute = 0; minute < 3; minute++) {
            check(server.hasDate(12, firstDate + day * 86400 + minute * 60), "Missing daily three-message occurrence");
        }
        legacyPausedRuleResumes();
        errorsAreVisibleAndRetryable();
        explicitSenderSurvivesRestart();
        System.out.println("LocalRepeatQueue: " + checks + " checks passed");
    }

    private static void legacyPausedRuleResumes() {
        Server server = new Server();
        SharedPreferences preferences = memoryPreferences();
        server.controller = new LocalRepeatController(0, preferences, server);
        server.send(text(12, server.now() + 3600, 800), (response, error) -> {});
        server.advance(1000);
        check(server.messages.size() == 1, "Migration fixture needs exactly the first occurrence");
        server.tasks.clear(); // Simulate process death, not live callbacks from the old instance.
        JSONArray saved = new JSONArray(preferences.getString("rules", "[]"));
        JSONObject rule = saved.getJSONObject(0);
        SerializedData data = new SerializedData();
        new TLRPC.TL_inputPeerSelf().serializeToStream(data);
        rule.put("sendAs", android.util.Base64.encodeToString(data.toByteArray(), android.util.Base64.NO_WRAP));
        data.cleanup();
        rule.put("paused", true);
        rule.put("wireSender", false);
        rule.put("pendingRandom", 1234567L);
        rule.put("pendingDate", rule.getInt("seedDate") + 86400);
        preferences.edit().putString("rules", new JSONArray().put(rule).toString()).commit();
        server.controller = new LocalRepeatController(0, preferences, server);
        check(!server.controller.getRepeats(12).get(0).paused, "Legacy sender bug was not repaired");
        server.advance(30000);
        check(server.messages.size() == 7, "Repaired legacy rule did not fill seven occurrences");
    }

    private static void errorsAreVisibleAndRetryable() {
        Server server = new Server();
        server.controller = new LocalRepeatController(0, memoryPreferences(), server);
        server.failRefills = true;
        server.send(text(12, server.now() + 3600, 900), (response, error) -> {});
        server.advance(30000);
        LocalRepeatController.RepeatInfo info = server.controller.getRepeats(12).get(0);
        check(server.messages.size() == 1 && info.paused, "Permanent error should pause replenishment");
        check("CHAT_WRITE_FORBIDDEN".equals(info.error), "Actual server error is not visible");
        server.failRefills = false;
        server.controller.retry(info.id);
        server.advance(30000);
        info = server.controller.getRepeats(12).get(0);
        check(server.messages.size() == 7 && !info.paused, "Retry did not refill the queue");
        check(info.error.isEmpty(), "Successful retry did not clear the old error");
    }

    private static void explicitSenderSurvivesRestart() {
        Server server = new Server();
        SharedPreferences preferences = memoryPreferences();
        server.controller = new LocalRepeatController(0, preferences, server);
        server.explicitSender = true;
        TLRPC.TL_messages_sendMessage seed = text(12, server.now() + 3600, 1000);
        seed.send_as = new TLRPC.TL_inputPeerSelf();
        seed.flags |= 1 << 13;
        server.send(seed, (response, error) -> {});
        server.advance(30000);
        check(server.messages.size() == 7, "Explicitly selected sender was not preserved");
        server.controller = new LocalRepeatController(0, preferences, server);
        server.messages.remove(server.messages.keySet().iterator().next());
        server.advance(90000);
        check(server.messages.size() == 7 && !server.controller.getRepeats(12).get(0).paused, "Restart changed an explicitly selected sender");
    }

    static TLRPC.TL_messages_sendMessage text(long dialogId, int date, long randomId) {
        TLRPC.TL_messages_sendMessage request = new TLRPC.TL_messages_sendMessage();
        TLRPC.TL_inputPeerUser peer = new TLRPC.TL_inputPeerUser();
        peer.user_id = dialogId; peer.access_hash = 42;
        request.peer = peer; request.message = "Hello"; request.random_id = randomId;
        request.schedule_date = date; request.schedule_repeat_period = 86400;
        request.flags = (1 << 10) | (1 << 24);
        return request;
    }

    static SharedPreferences memoryPreferences() {
        Map<String, Object> saved = new HashMap<>();
        return (SharedPreferences) Proxy.newProxyInstance(LocalRepeatQueueTest.class.getClassLoader(), new Class<?>[] {SharedPreferences.class}, (proxy, method, args) -> {
            if (method.getName().equals("getLong") || method.getName().equals("getString")) return saved.getOrDefault(args[0], args[1]);
            if (method.getName().equals("edit")) {
                Map<String, Object> pending = new HashMap<>();
                boolean[] clear = {false};
                return Proxy.newProxyInstance(LocalRepeatQueueTest.class.getClassLoader(), new Class<?>[] {SharedPreferences.Editor.class}, (editor, edit, values) -> {
                    if (edit.getName().equals("clear")) { clear[0] = true; return editor; }
                    if (edit.getName().startsWith("put")) { pending.put((String) values[0], values[1]); return editor; }
                    if (edit.getName().equals("commit") || edit.getName().equals("apply")) {
                        if (clear[0]) saved.clear();
                        saved.putAll(pending);
                        return edit.getName().equals("commit") ? true : null;
                    }
                    throw new UnsupportedOperationException(edit.getName());
                });
            }
            throw new UnsupportedOperationException(method.getName());
        });
    }

    static class Server implements LocalRepeatController.Backend {
        LocalRepeatController controller;
        long time = 1800000000000L, nextRandom = 10000, order;
        int nextId = 1;
        boolean failRefills;
        boolean explicitSender;
        final LinkedHashMap<Integer, TLRPC.Message> messages = new LinkedHashMap<>();
        final PriorityQueue<Task> tasks = new PriorityQueue<>((a, b) -> a.when == b.when ? Long.compare(a.order, b.order) : Long.compare(a.when, b.when));
        public long userId() { return 99; }
        public boolean premium() { return false; }
        public boolean active() { return true; }
        public boolean testBackend() { return false; }
        public int now() { return (int) (time / 1000); }
        public boolean connected() { return true; }
        public long randomId() { return nextRandom++; }
        public void later(Runnable runnable, long delayMs) { tasks.add(new Task(time + delayMs, order++, runnable)); }
        public void cache(TLRPC.messages_Messages history) {}
        public void updates(TLRPC.Updates updates) {}
        public void send(TLObject request, RequestDelegate callback) {
            LocalRepeatController.Preparation preparation = controller.prepare(request);
            later(() -> {
                TLObject response = null;
                TLRPC.TL_error error = null;
                if (request instanceof TLRPC.TL_messages_getScheduledHistory) {
                    TLRPC.TL_messages_messages history = new TLRPC.TL_messages_messages();
                    long dialogId = ((TLRPC.TL_messages_getScheduledHistory) request).peer.user_id;
                    for (TLRPC.Message message : messages.values()) if (message.peer_id.user_id == dialogId) history.messages.add(message);
                    response = history;
                } else if (request instanceof TLRPC.TL_messages_sendMessage) {
                    TLRPC.TL_messages_sendMessage send = (TLRPC.TL_messages_sendMessage) request;
                    if (failRefills && !messages.isEmpty()) {
                        error = new TLRPC.TL_error(); error.code = 403; error.text = "CHAT_WRITE_FORBIDDEN";
                    } else if (((send.flags & (1 << 13)) != 0) != explicitSender) {
                        // A normal send must not silently opt into a different sender identity.
                        error = new TLRPC.TL_error(); error.code = 400; error.text = "SEND_AS_PEER_INVALID";
                    } else {
                        check(send.schedule_repeat_period == 0 && (send.flags & (1 << 24)) == 0, "Premium repeat flag leaked into request");
                        TLRPC.TL_message message = new TLRPC.TL_message();
                        message.id = nextId++; message.date = send.schedule_date; message.message = send.message;
                        message.peer_id = new TLRPC.TL_peerUser(); message.peer_id.user_id = send.peer.user_id;
                        messages.put(message.id, message);
                        TL_update.TL_updateNewScheduledMessage update = new TL_update.TL_updateNewScheduledMessage();
                        update.message = message;
                        TLRPC.TL_updates result = new TLRPC.TL_updates(); result.updates.add(update);
                        response = result;
                    }
                } else if (request instanceof TLRPC.TL_messages_deleteScheduledMessages) {
                    for (int id : ((TLRPC.TL_messages_deleteScheduledMessages) request).id) messages.remove(id);
                    response = new TLRPC.TL_updates();
                } else throw new AssertionError("Unexpected request " + request.getClass());
                controller.onResponse(preparation, response, error);
                callback.run(response, error);
            }, 1);
        }
        void advance(int durationMs) {
            long until = time + durationMs;
            for (; time <= until; time += 1000) {
                while (!tasks.isEmpty() && tasks.peek().when <= time) tasks.remove().runnable.run();
                controller.tick();
            }
        }
        boolean hasDate(long dialogId, int date) {
            for (TLRPC.Message message : messages.values()) if (message.peer_id.user_id == dialogId && message.date == date) return true;
            return false;
        }
    }

    static class Task {
        final long when, order; final Runnable runnable;
        Task(long when, long order, Runnable runnable) { this.when = when; this.order = order; this.runnable = runnable; }
    }
    static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
}

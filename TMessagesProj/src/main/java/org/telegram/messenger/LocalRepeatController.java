package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.SerializedData;
import org.telegram.tgnet.RequestDelegate;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_update;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-device recurrence for free accounts. Only ordinary future scheduled messages go to Telegram.
 * State changes and network callbacks run on stageQueue; synchronized snapshots are used by the UI.
 */
public final class LocalRepeatController extends BaseController {
    public static final String UNSUPPORTED = "LOCAL_REPEAT_UNSUPPORTED";
    private static final LocalRepeatController[] instances = new LocalRepeatController[UserConfig.MAX_ACCOUNT_COUNT];

    public static synchronized LocalRepeatController getInstance(int account) {
        if (instances[account] == null) instances[account] = new LocalRepeatController(account);
        return instances[account];
    }

    public static final class Preparation {
        private Rule rule;
        private Rule previous;
        private int editedId;
        private TLObject request;
        public String error;
    }

    public static final class RepeatInfo {
        public long id;
        public int interval;
        public int queued;
        public String text;
        public boolean paused;
        public boolean stopping;
        public String error;
    }

    private static final class Rule {
        long id, dialogId, nextDate, pendingRandom;
        int interval, pendingDate, seedDate, forwardId, topMsgId;
        String seedText = "", peer = "", reply = "", sendAs = "", source = "", template = "";
        String error = "";
        boolean silent, background, noWebpage, invertMedia, dropAuthor, dropCaptions;
        boolean paused, stopping, inFlight;
        long retryAt;
        final LinkedHashMap<Integer, Integer> queued = new LinkedHashMap<>();
    }

    interface Backend {
        long userId();
        boolean premium();
        boolean active();
        boolean testBackend();
        int now();
        boolean connected();
        long randomId();
        void send(TLObject request, RequestDelegate callback);
        void later(Runnable runnable, long delayMs);
        void cache(TLRPC.messages_Messages history);
        void updates(TLRPC.Updates updates);
    }

    private final Backend backend;
    private final SharedPreferences preferences;
    private final ArrayList<Rule> rules = new ArrayList<>();
    private long ownerId, nextCheck;
    private boolean checking;
    private int cursor;

    private LocalRepeatController(int account) {
        this(account, null, null);
    }

    // Native networking and the account timer are injected so the actual queue loop can be tested.
    LocalRepeatController(int account, SharedPreferences testPreferences, Backend testBackend) {
        super(account);
        backend = testBackend != null ? testBackend : new Backend() {
            public long userId() { return getUserConfig().getClientUserId(); }
            public boolean premium() { return getUserConfig().isPremium(); }
            public boolean active() { return getUserConfig().isClientActivated(); }
            public boolean testBackend() { return getConnectionsManager().isTestBackend(); }
            public int now() { return getConnectionsManager().getCurrentTime(); }
            public boolean connected() {
                int state = getConnectionsManager().getConnectionState();
                return state == ConnectionsManager.ConnectionStateConnected || state == ConnectionsManager.ConnectionStateUpdating;
            }
            public long randomId() { return Utilities.random.nextLong(); }
            public void send(TLObject request, RequestDelegate callback) { getConnectionsManager().sendRequest(request, callback); }
            public void later(Runnable runnable, long delayMs) { Utilities.stageQueue.postRunnable(runnable, delayMs); }
            public void cache(TLRPC.messages_Messages history) {
                getMessagesController().putUsers(history.users, false);
                getMessagesController().putChats(history.chats, false);
            }
            public void updates(TLRPC.Updates updates) { getMessagesController().processUpdates(updates, false); }
        };
        preferences = testPreferences != null ? testPreferences : ApplicationLoader.applicationContext.getSharedPreferences("local_repeats_" + account, Context.MODE_PRIVATE);
        ownerId = backend.userId();
        if (ownerId != 0 && preferences.getLong("owner", 0) == ownerId) load();
    }

    public synchronized ArrayList<RepeatInfo> getRepeats(long dialogId) {
        ArrayList<RepeatInfo> result = new ArrayList<>();
        for (Rule rule : rules) {
            if (rule.dialogId != dialogId) continue;
            RepeatInfo info = new RepeatInfo();
            info.id = rule.id;
            info.interval = rule.interval;
            info.queued = rule.queued.size();
            info.text = rule.seedText;
            info.paused = rule.paused;
            info.stopping = rule.stopping;
            info.error = rule.error;
            result.add(info);
        }
        return result;
    }

    public synchronized int getRepeatPeriod(long dialogId, int messageId) {
        Rule rule = findRule(dialogId, messageId);
        return rule == null || rule.stopping ? 0 : rule.interval;
    }

    public void stop(long ruleId) {
        backend.later(() -> {
            synchronized (this) {
                for (Rule rule : rules) {
                    if (rule.id == ruleId) {
                        rule.stopping = true;
                        rule.paused = false;
                        rule.retryAt = 0;
                        rule.error = "";
                    }
                }
                save();
                nextCheck = 0;
                tick();
            }
        }, 0);
    }

    public void retry(long ruleId) {
        backend.later(() -> {
            synchronized (this) {
                for (Rule rule : rules) {
                    if (rule.id == ruleId) {
                        rule.paused = false;
                        rule.retryAt = 0;
                    }
                }
                save();
                nextCheck = 0;
                tick();
            }
        }, 0);
    }

    public synchronized void cleanup() {
        rules.clear();
        ownerId = 0;
        checking = false;
        preferences.edit().clear().commit();
    }

    /** Called immediately before serializing an outgoing request. */
    public synchronized Preparation prepare(TLObject request) {
        ensureOwner();
        Rule editedRule = null;
        if (request instanceof TLRPC.TL_messages_editMessage) {
            TLRPC.TL_messages_editMessage edit = (TLRPC.TL_messages_editMessage) request;
            long dialogId = edit.peer instanceof TLRPC.TL_inputPeerSelf ? backend.userId() : DialogObject.getPeerDialogId(edit.peer);
            Rule previous = findRule(dialogId, edit.id);
            editedRule = previous;
            if (previous != null && edit.schedule_date != 0 && (edit.schedule_repeat_period == 0 || backend.premium())) {
                Preparation preparation = new Preparation();
                preparation.previous = previous;
                preparation.editedId = edit.id;
                return preparation;
            }
        }
        if (backend.premium()) return null;
        Rule rule = new Rule();
        TLRPC.InputPeer peer;
        TLRPC.InputReplyTo reply = null;
        TLRPC.InputPeer sendAs = null;
        TLRPC.InputMedia media = null;
        boolean unsupported = false;
        if (request instanceof TLRPC.TL_messages_sendMessage) {
            TLRPC.TL_messages_sendMessage req = (TLRPC.TL_messages_sendMessage) request;
            if (req.schedule_repeat_period == 0 || req.schedule_date == 0) return null;
            rule.id = req.random_id;
            rule.interval = req.schedule_repeat_period;
            rule.seedDate = req.schedule_date;
            rule.seedText = req.message;
            rule.silent = req.silent;
            rule.background = req.background;
            rule.noWebpage = req.no_webpage;
            rule.invertMedia = req.invert_media;
            peer = req.peer;
            reply = (req.flags & 1) != 0 ? req.reply_to : null;
            sendAs = (req.flags & (1 << 13)) != 0 ? req.send_as : null;
            unsupported = req.rich_message != null || req.suggested_post != null || req.allow_paid_stars != 0 || req.reply_markup != null;
        } else if (request instanceof TLRPC.TL_messages_sendMedia) {
            TLRPC.TL_messages_sendMedia req = (TLRPC.TL_messages_sendMedia) request;
            if (req.schedule_repeat_period == 0 || req.schedule_date == 0) return null;
            rule.id = req.random_id;
            rule.interval = req.schedule_repeat_period;
            rule.seedDate = req.schedule_date;
            rule.seedText = req.message;
            rule.silent = req.silent;
            rule.background = req.background;
            rule.invertMedia = req.invert_media;
            peer = req.peer;
            reply = (req.flags & 1) != 0 ? req.reply_to : null;
            sendAs = (req.flags & (1 << 13)) != 0 ? req.send_as : null;
            media = req.media;
            unsupported = req.suggested_post != null || req.allow_paid_stars != 0 || req.reply_markup != null || !supportedInputMedia(media);
        } else if (request instanceof TLRPC.TL_messages_forwardMessages) {
            TLRPC.TL_messages_forwardMessages req = (TLRPC.TL_messages_forwardMessages) request;
            if (req.schedule_repeat_period == 0 || req.schedule_date == 0) return null;
            unsupported = req.id.size() != 1 || req.random_id.size() != 1 || req.allow_paid_stars != 0 || req.suggested_post != null;
            rule.id = req.random_id.isEmpty() ? 0 : req.random_id.get(0);
            rule.interval = req.schedule_repeat_period;
            rule.seedDate = req.schedule_date;
            rule.silent = req.silent;
            rule.background = req.background;
            rule.dropAuthor = req.drop_author;
            rule.dropCaptions = req.drop_media_captions;
            rule.source = encode(req.from_peer);
            rule.forwardId = req.id.isEmpty() ? 0 : req.id.get(0);
            rule.topMsgId = req.top_msg_id;
            peer = req.to_peer; reply = req.reply_to; sendAs = req.send_as;
        } else if (request instanceof TLRPC.TL_messages_editMessage) {
            TLRPC.TL_messages_editMessage req = (TLRPC.TL_messages_editMessage) request;
            if (req.schedule_repeat_period == 0 || req.schedule_date == 0) return null;
            rule.id = newRandomId();
            rule.interval = req.schedule_repeat_period;
            rule.seedDate = req.schedule_date;
            rule.seedText = req.message == null ? "" : req.message;
            rule.noWebpage = req.no_webpage;
            rule.invertMedia = req.invert_media;
            rule.queued.put(req.id, req.schedule_date);
            peer = req.peer; media = req.media;
            unsupported = req.rich_message != null || media != null && !supportedInputMedia(media);
            if (editedRule != null) {
                rule.silent = editedRule.silent;
                rule.background = editedRule.background;
                rule.source = editedRule.source;
                rule.forwardId = editedRule.forwardId;
                rule.dropAuthor = editedRule.dropAuthor;
                rule.dropCaptions = editedRule.dropCaptions;
                rule.topMsgId = editedRule.topMsgId;
                reply = inputReply(editedRule.reply);
                sendAs = inputPeerOrNull(editedRule.sendAs);
            }
        } else {
            return null;
        }
        Preparation preparation = new Preparation();
        if (unsupported || media != null && media.ttl_seconds != 0
                || !RepeatSchedule.isValidInterval(rule.interval, backend.testBackend())) {
            preparation.error = UNSUPPORTED;
            return preparation;
        }
        rule.peer = encode(peer);
        rule.reply = encode(reply);
        rule.sendAs = encode(sendAs);
        rule.dialogId = peer instanceof TLRPC.TL_inputPeerSelf ? backend.userId() : DialogObject.getPeerDialogId(peer);
        rule.nextDate = (long) rule.seedDate + rule.interval;
        rule.inFlight = true;
        if (rule.id == 0) rule.id = newRandomId();
        // Keep the intent before sending. If the process dies before the acknowledgement,
        // recovery only adopts a unique matching scheduled message; it never sends the seed again.
        rules.add(rule);
        ownerId = backend.userId();
        if (!save()) {
            rules.remove(rule);
            preparation.error = "LOCAL_REPEAT_STORAGE_FAILED";
            return preparation;
        }
        setRepeat(request, 0);
        preparation.rule = rule;
        preparation.request = request;
        return preparation;
    }

    /** Observe the response before SendMessagesHelper consumes its updates. */
    public synchronized void onResponse(Preparation preparation, TLObject response, TLRPC.TL_error error) {
        if (preparation != null && preparation.previous != null) {
            if (error == null && rules.contains(preparation.previous)) {
                preparation.previous.queued.remove(preparation.editedId);
                preparation.previous.stopping = true;
                preparation.previous.paused = false;
                preparation.previous.retryAt = 0;
                save();
                nextCheck = 0;
                tick();
            }
            return;
        }
        if (preparation == null || preparation.rule == null || !rules.contains(preparation.rule)) return;
        Rule rule = preparation.rule;
        rule.inFlight = false;
        TLRPC.Message message = scheduledMessage(response);
        if (error != null) {
            rules.remove(rule);
            // File-reference retries reuse the request, so retain the original repeat intent.
            setRepeat(preparation.request, rule.interval);
        } else if (message != null) {
            Rule previous = findRule(rule.dialogId, message.id, rule);
            if (previous != null) {
                previous.queued.remove(message.id);
                previous.stopping = true;
                previous.paused = false;
            }
            rule.template = encode(message);
            rule.seedText = message.message == null ? "" : message.message;
            rule.queued.put(message.id, message.date);
            rule.seedDate = message.date;
            rule.nextDate = (long) message.date + rule.interval;
            if (rule.reply.isEmpty() && message.reply_to instanceof TLRPC.TL_messageReplyHeader) {
                TLRPC.TL_inputReplyToMessage reply = new TLRPC.TL_inputReplyToMessage();
                reply.reply_to_msg_id = message.reply_to.reply_to_msg_id;
                reply.top_msg_id = message.reply_to.reply_to_top_id;
                if (reply.top_msg_id != 0) reply.flags |= 1;
                rule.reply = encode(reply);
            }
        } else {
            rule.paused = true;
            rule.error = "LOCAL_REPEAT_UNCONFIRMED";
        }
        save();
        nextCheck = 0;
        tick();
    }

    /** Called by MessagesController's existing account timer. */
    public synchronized void tick() {
        long now = backend.now();
        if (checking || now < nextCheck || !backend.active()
                || !backend.connected()) return;
        if (ownerId != backend.userId()) {
            ensureOwner();
        }
        nextCheck = now + 60;
        for (int i = 0; i < rules.size(); i++) {
            Rule rule = rules.get(Math.floorMod(cursor++, rules.size()));
            if (rule.inFlight || rule.paused || rule.retryAt > now) continue;
            rule.retryAt = now + 60;
            checking = true;
            TLRPC.TL_messages_getScheduledHistory req = new TLRPC.TL_messages_getScheduledHistory();
            req.peer = inputPeer(rule.peer);
            backend.send(req, (response, error) -> {
                synchronized (this) {
                    if (!rules.contains(rule)) return;
                    if (error != null || !(response instanceof TLRPC.messages_Messages)) {
                        failed(rule, error);
                        finishCheck();
                        return;
                    }
                    reconcile(rule, (TLRPC.messages_Messages) response);
                }
            });
            return;
        }
    }

    private void reconcile(Rule rule, TLRPC.messages_Messages history) {
        backend.cache(history);
        Map<Integer, TLRPC.Message> existing = new LinkedHashMap<>();
        for (TLRPC.Message message : history.messages) existing.put(message.id, message);
        rule.queued.keySet().retainAll(existing.keySet());
        for (Map.Entry<Integer, Integer> entry : rule.queued.entrySet()) entry.setValue(existing.get(entry.getKey()).date);

        if (rule.template.isEmpty()) {
            TLRPC.Message seed = null;
            for (TLRPC.Message message : history.messages) {
                if ((rule.queued.containsKey(message.id) || findRule(rule.dialogId, message.id, rule) == null
                        && message.date == rule.seedDate && rule.seedText.equals(message.message))) {
                    if (seed != null) { seed = null; break; }
                    seed = message;
                }
            }
            if (seed == null) {
                rule.paused = true;
                rule.error = "LOCAL_REPEAT_SEED_NOT_FOUND";
                save();
                finishCheck();
                return;
            }
            rule.template = encode(seed);
            rule.queued.put(seed.id, seed.date);
        }
        if (rule.stopping) {
            if (rule.pendingRandom != 0) {
                // A send may have reached Telegram before the app stopped. Adopt its ID for deletion.
                for (TLRPC.Message message : history.messages) {
                    if (findRule(rule.dialogId, message.id, rule) == null
                            && message.date == rule.pendingDate && matchesTemplate(rule, message)) rule.queued.put(message.id, message.date);
                }
                rule.pendingRandom = 0;
                rule.pendingDate = 0;
            }
            if (rule.queued.isEmpty()) {
                rules.remove(rule);
                save();
                finishCheck();
                return;
            }
            TLRPC.TL_messages_deleteScheduledMessages req = new TLRPC.TL_messages_deleteScheduledMessages();
            req.peer = inputPeer(rule.peer);
            req.id.addAll(rule.queued.keySet());
            backend.send(req, (response, error) -> {
                synchronized (this) {
                    if (!rules.contains(rule)) return;
                    if (error == null && response instanceof TLRPC.Updates) {
                        rules.remove(rule);
                        backend.updates((TLRPC.Updates) response);
                    } else failed(rule, error);
                    save();
                    finishCheck();
                }
            });
            return;
        }

        long now = backend.now();
        if (rule.pendingRandom != 0) {
            TLRPC.Message pending = null;
            for (TLRPC.Message message : history.messages) {
                if (!rule.queued.containsKey(message.id) && findRule(rule.dialogId, message.id, rule) == null
                        && message.date == rule.pendingDate && matchesTemplate(rule, message)) {
                    if (pending != null) { pending = null; rule.paused = true; rule.error = "LOCAL_REPEAT_AMBIGUOUS"; break; }
                    pending = message;
                }
            }
            if (pending != null) {
                rule.queued.put(pending.id, pending.date);
                rule.nextDate = (long) rule.pendingDate + rule.interval;
                rule.pendingRandom = 0;
                rule.pendingDate = 0;
            } else if (rule.pendingDate < now + RepeatSchedule.MIN_LEAD_SECONDS) {
                // Never retry an expired schedule_date: Telegram could deliver it immediately.
                rule.nextDate = (long) rule.pendingDate + rule.interval;
                rule.pendingRandom = 0;
                rule.pendingDate = 0;
            }
        }
        rule.nextDate = RepeatSchedule.nextFutureDate(rule.nextDate, rule.interval, now);
        long date = rule.pendingRandom != 0 ? rule.pendingDate : rule.nextDate;
        if (rule.paused || !RepeatSchedule.canQueue(date, now, rule.queued.size(), history.messages.size())) {
            save();
            finishCheck();
            return;
        }
        if (rule.pendingRandom == 0) {
            rule.pendingRandom = newRandomId();
            rule.pendingDate = (int) date;
        }
        // Persist the idempotency key before dispatch, including across process restarts.
        if (!save()) {
            rule.paused = true;
            rule.error = "LOCAL_REPEAT_STORAGE_FAILED";
            finishCheck();
            return;
        }
        TLObject request;
        try {
            request = buildRequest(rule, history);
        } catch (Exception e) {
            FileLog.e(e);
            rule.paused = true;
            rule.error = "LOCAL_REPEAT_BUILD_" + e.getClass().getSimpleName();
            save();
            finishCheck();
            return;
        }
        rule.inFlight = true;
        backend.send(request, (response, error) -> {
            synchronized (this) {
                if (!rules.contains(rule)) return;
                rule.inFlight = false;
                TLRPC.Message message = scheduledMessage(response);
                if (error == null && message != null) {
                    rule.queued.put(message.id, message.date);
                    rule.nextDate = (long) rule.pendingDate + rule.interval;
                    rule.pendingRandom = 0;
                    rule.pendingDate = 0;
                    rule.retryAt = backend.now() + 1;
                    rule.error = "";
                    save();
                    backend.updates((TLRPC.Updates) response);
                } else {
                    failed(rule, error);
                    save();
                }
                finishCheck();
            }
        });
    }

    private static TLObject buildRequest(Rule rule, TLRPC.messages_Messages history) {
        if (!rule.source.isEmpty()) {
            TLRPC.TL_messages_forwardMessages req = new TLRPC.TL_messages_forwardMessages();
            req.from_peer = inputPeer(rule.source);
            req.to_peer = inputPeer(rule.peer);
            req.id.add(rule.forwardId);
            req.random_id.add(rule.pendingRandom);
            req.schedule_date = rule.pendingDate;
            req.flags |= 1 << 10;
            req.silent = rule.silent; req.background = rule.background;
            req.drop_author = rule.dropAuthor; req.drop_media_captions = rule.dropCaptions;
            req.send_as = inputPeerOrNull(rule.sendAs);
            req.reply_to = inputReply(rule.reply);
            req.top_msg_id = rule.topMsgId;
            if (req.top_msg_id != 0) req.flags |= 1 << 9;
            return req;
        }
        TLRPC.Message template = message(rule.template);
        // Refresh expiring photo/document references from an occurrence still in the server queue.
        for (TLRPC.Message queued : history.messages) {
            if (!rule.queued.containsKey(queued.id) || template.media == null || queued.media == null) continue;
            if (template.media.photo != null && queued.media.photo != null && template.media.photo.id == queued.media.photo.id) template.media.photo = queued.media.photo;
            if (template.media.document != null && queued.media.document != null && template.media.document.id == queued.media.document.id) template.media.document = queued.media.document;
        }
        TLRPC.InputReplyTo reply = inputReply(rule.reply);
        TLRPC.InputPeer sendAs = inputPeerOrNull(rule.sendAs);
        if (template.media == null || template.media instanceof TLRPC.TL_messageMediaEmpty || template.media instanceof TLRPC.TL_messageMediaWebPage) {
            TLRPC.TL_messages_sendMessage req = new TLRPC.TL_messages_sendMessage();
            req.peer = inputPeer(rule.peer); req.reply_to = reply; req.send_as = sendAs;
            req.message = template.message; req.entities.addAll(template.entities);
            req.random_id = rule.pendingRandom; req.schedule_date = rule.pendingDate;
            req.silent = rule.silent; req.background = rule.background;
            req.no_webpage = rule.noWebpage; req.invert_media = rule.invertMedia;
            req.flags = sendFlags(reply, sendAs, !req.entities.isEmpty());
            return req;
        }
        TLRPC.TL_messages_sendMedia req = new TLRPC.TL_messages_sendMedia();
        req.peer = inputPeer(rule.peer); req.reply_to = reply; req.send_as = sendAs;
        req.message = template.message; req.entities.addAll(template.entities);
        req.random_id = rule.pendingRandom; req.schedule_date = rule.pendingDate;
        req.silent = rule.silent; req.background = rule.background; req.invert_media = rule.invertMedia;
        req.media = inputMedia(template.media);
        req.flags = sendFlags(reply, sendAs, !req.entities.isEmpty());
        return req;
    }

    private static int sendFlags(TLRPC.InputReplyTo reply, TLRPC.InputPeer sendAs, boolean entities) {
        return (1 << 10) | (reply != null ? 1 : 0) | (sendAs != null ? 1 << 13 : 0) | (entities ? 1 << 3 : 0);
    }

    private static TLRPC.InputMedia inputMedia(TLRPC.MessageMedia media) {
        if (media instanceof TLRPC.TL_messageMediaPhoto && media.photo instanceof TLRPC.TL_photo) {
            TLRPC.TL_inputMediaPhoto input = new TLRPC.TL_inputMediaPhoto();
            TLRPC.TL_inputPhoto photo = new TLRPC.TL_inputPhoto();
            photo.id = media.photo.id; photo.access_hash = media.photo.access_hash; photo.file_reference = media.photo.file_reference;
            input.id = photo; input.spoiler = media.spoiler;
            if (media.document != null) input.video = inputDocument(media.document);
            return input;
        } else if (media instanceof TLRPC.TL_messageMediaDocument && media.document instanceof TLRPC.TL_document) {
            TLRPC.TL_inputMediaDocument input = new TLRPC.TL_inputMediaDocument();
            input.id = inputDocument(media.document); input.spoiler = media.spoiler;
            return input;
        } else if (media instanceof TLRPC.TL_messageMediaContact) {
            TLRPC.TL_inputMediaContact input = new TLRPC.TL_inputMediaContact();
            input.phone_number = media.phone_number; input.first_name = media.first_name; input.last_name = media.last_name; input.vcard = media.vcard;
            return input;
        } else if (media instanceof TLRPC.TL_messageMediaGeo || media instanceof TLRPC.TL_messageMediaVenue) {
            TLRPC.InputMedia input = media instanceof TLRPC.TL_messageMediaVenue ? new TLRPC.TL_inputMediaVenue() : new TLRPC.TL_inputMediaGeoPoint();
            TLRPC.TL_inputGeoPoint geo = new TLRPC.TL_inputGeoPoint();
            geo.lat = media.geo.lat; geo._long = media.geo._long;
            input.geo_point = geo;
            input.title = media.title; input.address = media.address; input.provider = media.provider;
            input.venue_id = media.venue_id; input.venue_type = media.venue_type;
            return input;
        }
        throw new IllegalArgumentException("Unsupported recurring media");
    }

    private static TLRPC.InputDocument inputDocument(TLRPC.Document document) {
        TLRPC.TL_inputDocument input = new TLRPC.TL_inputDocument();
        input.id = document.id; input.access_hash = document.access_hash; input.file_reference = document.file_reference;
        return input;
    }

    private static boolean supportedInputMedia(TLRPC.InputMedia media) {
        return media instanceof TLRPC.TL_inputMediaPhoto || media instanceof TLRPC.TL_inputMediaUploadedPhoto
                || media instanceof TLRPC.TL_inputMediaPhotoExternal || media instanceof TLRPC.TL_inputMediaDocument
                || media instanceof TLRPC.TL_inputMediaUploadedDocument || media instanceof TLRPC.TL_inputMediaDocumentExternal
                || media instanceof TLRPC.TL_inputMediaContact || media instanceof TLRPC.TL_inputMediaGeoPoint
                || media instanceof TLRPC.TL_inputMediaVenue;
    }

    private void finishCheck() {
        checking = false;
        nextCheck = 0;
        // At most one request chain per second; full/paused series wait for the account timer.
        backend.later(this::tick, 1000);
        nextCheck = backend.now() + 1;
    }

    private void failed(Rule rule, TLRPC.TL_error error) {
        long now = backend.now();
        rule.error = error == null ? "LOCAL_REPEAT_UNCONFIRMED" : error.text;
        if (error == null || error.code >= 500 || error.code < 0 || "SCHEDULE_TOO_MUCH".equals(error.text)
                || error.text != null && error.text.startsWith("FILE_REFERENCE_")) {
            rule.retryAt = now + 300;
        } else if (error.text != null && (error.text.startsWith("FLOOD_WAIT_") || error.text.startsWith("SLOWMODE_WAIT_"))) {
            long seconds;
            try { seconds = Long.parseLong(error.text.substring(error.text.lastIndexOf('_') + 1)); }
            catch (NumberFormatException e) { seconds = 60; }
            rule.retryAt = now + Math.max(60L, seconds);
        } else {
            rule.paused = true;
        }
        save();
    }

    private Rule findRule(long dialogId, int messageId) { return findRule(dialogId, messageId, null); }

    private Rule findRule(long dialogId, int messageId, Rule except) {
        for (Rule rule : rules) if (rule != except && rule.dialogId == dialogId && rule.queued.containsKey(messageId)) return rule;
        return null;
    }

    private static boolean matchesTemplate(Rule rule, TLRPC.Message candidate) {
        TLRPC.Message template = message(rule.template);
        if (!java.util.Objects.equals(template.message, candidate.message)) return false;
        if (template.media == null) return candidate.media == null || candidate.media instanceof TLRPC.TL_messageMediaEmpty;
        if (candidate.media == null || template.media.getClass() != candidate.media.getClass()) return false;
        if (template.media.photo != null) return candidate.media.photo != null && template.media.photo.id == candidate.media.photo.id;
        if (template.media.document != null) return candidate.media.document != null && template.media.document.id == candidate.media.document.id;
        return true;
    }

    private static TLRPC.Message scheduledMessage(TLObject response) {
        if (response instanceof TLRPC.Updates) {
            for (TLRPC.Update update : ((TLRPC.Updates) response).updates) {
                if (update instanceof TL_update.TL_updateNewScheduledMessage) return ((TL_update.TL_updateNewScheduledMessage) update).message;
            }
        }
        return null;
    }

    private static void setRepeat(TLObject request, int period) {
        if (request instanceof TLRPC.TL_messages_sendMessage) {
            TLRPC.TL_messages_sendMessage req = (TLRPC.TL_messages_sendMessage) request;
            req.schedule_repeat_period = period; req.flags = period == 0 ? req.flags & ~(1 << 24) : req.flags | 1 << 24;
        } else if (request instanceof TLRPC.TL_messages_sendMedia) {
            TLRPC.TL_messages_sendMedia req = (TLRPC.TL_messages_sendMedia) request;
            req.schedule_repeat_period = period; req.flags = period == 0 ? req.flags & ~(1 << 24) : req.flags | 1 << 24;
        } else if (request instanceof TLRPC.TL_messages_forwardMessages) {
            TLRPC.TL_messages_forwardMessages req = (TLRPC.TL_messages_forwardMessages) request;
            req.schedule_repeat_period = period; req.flags = period == 0 ? req.flags & ~(1 << 24) : req.flags | 1 << 24;
        } else if (request instanceof TLRPC.TL_messages_editMessage) {
            TLRPC.TL_messages_editMessage req = (TLRPC.TL_messages_editMessage) request;
            req.schedule_repeat_period = period; req.flags = period == 0 ? req.flags & ~(1 << 18) : req.flags | 1 << 18;
        }
    }

    private long newRandomId() {
        long id;
        do { id = backend.randomId(); } while (id == 0);
        return id;
    }

    private void ensureOwner() {
        long currentOwner = backend.userId();
        if (currentOwner == 0 || currentOwner == ownerId) return;
        rules.clear();
        checking = false;
        ownerId = currentOwner;
        // Controllers can be created before UserConfig finishes loading during startup.
        // In that case, recover this user's saved rules instead of clearing them.
        if (preferences.getLong("owner", 0) == ownerId) load();
        else preferences.edit().clear().commit();
    }

    private static String encode(TLObject object) {
        if (object == null) return "";
        SerializedData data = new SerializedData();
        try {
            object.serializeToStream(data);
            return Base64.encodeToString(data.toByteArray(), Base64.NO_WRAP);
        } finally { data.cleanup(); }
    }

    private static TLRPC.InputPeer inputPeerOrNull(String value) { return value.isEmpty() ? null : inputPeer(value); }
    private static TLRPC.InputPeer inputPeer(String value) {
        SerializedData data = new SerializedData(Base64.decode(value, Base64.NO_WRAP));
        try { return TLRPC.InputPeer.TLdeserialize(data, data.readInt32(true), true); }
        finally { data.cleanup(); }
    }
    private static TLRPC.InputReplyTo inputReply(String value) {
        if (value.isEmpty()) return null;
        SerializedData data = new SerializedData(Base64.decode(value, Base64.NO_WRAP));
        try { return TLRPC.InputReplyTo.TLdeserialize(data, data.readInt32(true), true); }
        finally { data.cleanup(); }
    }
    private static TLRPC.Message message(String value) {
        SerializedData data = new SerializedData(Base64.decode(value, Base64.NO_WRAP));
        try { return TLRPC.Message.TLdeserialize(data, data.readInt32(true), true); }
        finally { data.cleanup(); }
    }

    private boolean save() {
        try {
            JSONArray array = new JSONArray();
            for (Rule r : rules) {
                JSONObject j = new JSONObject();
                j.put("id", r.id); j.put("dialog", r.dialogId); j.put("interval", r.interval); j.put("next", r.nextDate);
                j.put("pendingRandom", r.pendingRandom); j.put("pendingDate", r.pendingDate);
                j.put("seedDate", r.seedDate); j.put("text", r.seedText); j.put("peer", r.peer);
                j.put("reply", r.reply); j.put("sendAs", r.sendAs); j.put("source", r.source);
                j.put("forwardId", r.forwardId); j.put("topMsgId", r.topMsgId); j.put("template", r.template);
                j.put("silent", r.silent); j.put("background", r.background); j.put("noWebpage", r.noWebpage); j.put("invert", r.invertMedia);
                j.put("dropAuthor", r.dropAuthor); j.put("dropCaptions", r.dropCaptions);
                j.put("paused", r.paused); j.put("stopping", r.stopping); j.put("retryAt", r.retryAt);
                j.put("error", r.error);
                j.put("wireSender", true);
                JSONObject queued = new JSONObject();
                for (Map.Entry<Integer, Integer> entry : r.queued.entrySet()) queued.put(String.valueOf(entry.getKey()), entry.getValue());
                j.put("queued", queued);
                array.put(j);
            }
            return preferences.edit().putLong("owner", ownerId).putString("rules", array.toString()).commit();
        } catch (Exception e) { FileLog.e(e); return false; }
    }

    private void load() {
        try {
            JSONArray array = new JSONArray(preferences.getString("rules", "[]"));
            for (int i = 0; i < array.length(); i++) {
                JSONObject j = array.getJSONObject(i);
                Rule r = new Rule();
                r.id = j.getLong("id"); r.dialogId = j.getLong("dialog"); r.interval = j.getInt("interval"); r.nextDate = j.getLong("next");
                r.pendingRandom = j.optLong("pendingRandom"); r.pendingDate = j.optInt("pendingDate");
                r.seedDate = j.getInt("seedDate"); r.seedText = j.optString("text"); r.peer = j.getString("peer");
                r.reply = j.optString("reply"); r.sendAs = j.optString("sendAs"); r.source = j.optString("source");
                r.forwardId = j.optInt("forwardId"); r.topMsgId = j.optInt("topMsgId"); r.template = j.optString("template");
                r.silent = j.optBoolean("silent"); r.background = j.optBoolean("background"); r.noWebpage = j.optBoolean("noWebpage"); r.invertMedia = j.optBoolean("invert");
                r.dropAuthor = j.optBoolean("dropAuthor"); r.dropCaptions = j.optBoolean("dropCaptions");
                r.paused = j.optBoolean("paused"); r.stopping = j.optBoolean("stopping"); r.retryAt = j.optLong("retryAt");
                r.error = j.optString("error");
                if (!j.optBoolean("wireSender") && !r.sendAs.isEmpty()) {
                    TLRPC.InputPeer sender = inputPeer(r.sendAs);
                    if (sender instanceof TLRPC.TL_inputPeerSelf || sender.user_id == ownerId) {
                        // Older builds captured send_as even when flag 13 was OFF. The default
                        // sender needs no override; repair and resume these paused rules once.
                        r.sendAs = "";
                        if (r.paused && r.error.isEmpty() && !r.stopping) {
                            r.paused = false;
                            r.retryAt = 0;
                        }
                    }
                }
                JSONObject queued = j.getJSONObject("queued");
                Iterator<String> keys = queued.keys();
                while (keys.hasNext()) { String key = keys.next(); r.queued.put(Integer.parseInt(key), queued.getInt(key)); }
                if (r.interval > 0 && !r.peer.isEmpty()) rules.add(r);
            }
        } catch (Exception e) { FileLog.e(e); }
    }
}

import org.telegram.messenger.LocalRepeatController;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Field;

/** Protocol regression checks against the compiled app classes; no network or account is used. */
public class LocalRepeatProtocolTest {
    public static void main(String[] args) throws Exception {
        Method repeat = LocalRepeatController.class.getDeclaredMethod("setRepeat", TLObject.class, int.class);
        repeat.setAccessible(true);
        TLRPC.TL_messages_sendMessage text = new TLRPC.TL_messages_sendMessage();
        text.flags = (1 << 24) | (1 << 10) | (1 << 3);
        text.schedule_repeat_period = 86400;
        repeat.invoke(null, text, 0);
        check(text.schedule_repeat_period == 0 && text.flags == ((1 << 10) | (1 << 3)));
        repeat.invoke(null, text, 86400);
        check(text.schedule_repeat_period == 86400 && (text.flags & (1 << 24)) != 0);

        TLRPC.TL_messages_sendMedia media = new TLRPC.TL_messages_sendMedia();
        media.flags = (1 << 24) | (1 << 10) | 1;
        media.schedule_repeat_period = 86400;
        repeat.invoke(null, media, 0);
        check(media.schedule_repeat_period == 0 && media.flags == ((1 << 10) | 1));

        TLRPC.TL_messages_forwardMessages forward = new TLRPC.TL_messages_forwardMessages();
        forward.flags = (1 << 24) | (1 << 10) | (1 << 9);
        repeat.invoke(null, forward, 0);
        check(forward.flags == ((1 << 10) | (1 << 9)) && forward.schedule_repeat_period == 0);

        TLRPC.TL_messages_editMessage edit = new TLRPC.TL_messages_editMessage();
        edit.flags = (1 << 18) | (1 << 15) | (1 << 11);
        repeat.invoke(null, edit, 0);
        check(edit.flags == ((1 << 15) | (1 << 11)) && edit.schedule_repeat_period == 0);
        repeat.invoke(null, edit, 604800);
        check(edit.schedule_repeat_period == 604800 && (edit.flags & (1 << 18)) != 0);

        Method convert = LocalRepeatController.class.getDeclaredMethod("inputMedia", TLRPC.MessageMedia.class);
        convert.setAccessible(true);
        TLRPC.TL_messageMediaPhoto photo = new TLRPC.TL_messageMediaPhoto();
        photo.photo = new TLRPC.TL_photo();
        photo.photo.id = 123;
        photo.photo.access_hash = 456;
        photo.photo.file_reference = new byte[] {7, 8};
        photo.spoiler = true;
        TLRPC.TL_inputMediaPhoto inputPhoto = (TLRPC.TL_inputMediaPhoto) convert.invoke(null, photo);
        check(inputPhoto.id.id == 123 && inputPhoto.id.access_hash == 456 && inputPhoto.id.file_reference[0] == 7 && inputPhoto.spoiler);

        TLRPC.TL_messageMediaDocument document = new TLRPC.TL_messageMediaDocument();
        document.document = new TLRPC.TL_document();
        document.document.id = 987;
        document.document.access_hash = 654;
        document.document.file_reference = new byte[] {3};
        document.spoiler = true;
        TLRPC.TL_inputMediaDocument inputDocument = (TLRPC.TL_inputMediaDocument) convert.invoke(null, document);
        check(inputDocument.id.id == 987 && inputDocument.id.access_hash == 654 && inputDocument.id.file_reference[0] == 3 && inputDocument.spoiler);

        TLRPC.TL_messageMediaContact contact = new TLRPC.TL_messageMediaContact();
        contact.phone_number = "123"; contact.first_name = "Test"; contact.last_name = "User"; contact.vcard = "vcard";
        TLRPC.InputMedia inputContact = (TLRPC.InputMedia) convert.invoke(null, contact);
        check(inputContact instanceof TLRPC.TL_inputMediaContact && "123".equals(inputContact.phone_number) && "vcard".equals(inputContact.vcard));

        try {
            convert.invoke(null, new TLRPC.TL_messageMediaPoll());
            throw new AssertionError("Unsupported media accepted");
        } catch (InvocationTargetException expected) {
            check(expected.getCause() instanceof IllegalArgumentException);
        }
        checkRebuiltRequest();
        System.out.println("LocalRepeatProtocol: 16 checks passed");
    }

    private static void checkRebuiltRequest() throws Exception {
        Class<?> ruleClass = Class.forName("org.telegram.messenger.LocalRepeatController$Rule");
        java.lang.reflect.Constructor<?> constructor = ruleClass.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object rule = constructor.newInstance();
        Method encode = LocalRepeatController.class.getDeclaredMethod("encode", TLObject.class);
        encode.setAccessible(true);
        Method build = LocalRepeatController.class.getDeclaredMethod("buildRequest", ruleClass, TLRPC.messages_Messages.class);
        build.setAccessible(true);

        TLRPC.TL_inputPeerUser peer = new TLRPC.TL_inputPeerUser();
        peer.user_id = 123; peer.access_hash = 456;
        TLRPC.TL_inputReplyToMessage reply = new TLRPC.TL_inputReplyToMessage();
        reply.reply_to_msg_id = 17; reply.top_msg_id = 9; reply.flags = 1;
        TLRPC.TL_message template = new TLRPC.TL_message();
        template.peer_id = new TLRPC.TL_peerUser(); template.peer_id.user_id = 123;
        template.id = 2; template.date = 1800001000; template.message = "Repeat me";
        TLRPC.TL_messageEntityBold bold = new TLRPC.TL_messageEntityBold();
        bold.offset = 0; bold.length = 6; template.entities.add(bold); template.flags |= 128;
        set(rule, "peer", encode.invoke(null, peer));
        set(rule, "reply", encode.invoke(null, reply));
        set(rule, "template", encode.invoke(null, template));
        set(rule, "pendingDate", 1800087400);
        set(rule, "pendingRandom", 987654321L);
        set(rule, "silent", true);
        set(rule, "noWebpage", true);
        TLRPC.TL_messages_messages history = new TLRPC.TL_messages_messages();
        TLRPC.TL_messages_sendMessage first = (TLRPC.TL_messages_sendMessage) build.invoke(null, rule, history);
        TLRPC.TL_messages_sendMessage retry = (TLRPC.TL_messages_sendMessage) build.invoke(null, rule, history);
        check(first.schedule_date == 1800087400 && first.schedule_repeat_period == 0 && (first.flags & (1 << 24)) == 0);
        check(first.random_id == 987654321L && retry.random_id == first.random_id && retry.schedule_date == first.schedule_date);
        check(first.peer.user_id == 123 && first.peer.access_hash == 456 && "Repeat me".equals(first.message));
        check(first.reply_to instanceof TLRPC.TL_inputReplyToMessage && ((TLRPC.TL_inputReplyToMessage) first.reply_to).top_msg_id == 9);
        check(first.entities.size() == 1 && first.entities.get(0) instanceof TLRPC.TL_messageEntityBold && first.silent && first.no_webpage);
        set(rule, "pendingRandom", 987654322L);
        set(rule, "pendingDate", 1800173800);
        TLRPC.TL_messages_sendMessage next = (TLRPC.TL_messages_sendMessage) build.invoke(null, rule, history);
        check(next.random_id != first.random_id && next.schedule_date > first.schedule_date && "Repeat me".equals(next.message));
    }

    private static void set(Object object, String name, Object value) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(object, value);
    }

    private static void check(boolean condition) {
        if (!condition) throw new AssertionError("Protocol check failed");
    }
}

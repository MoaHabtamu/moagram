# Custom features

[Documentation index](README.md)

## Repeating scheduled messages

Free accounts use a rule saved on the device to keep ordinary Telegram scheduled messages queued ahead. Premium accounts retain Telegram's native repeat path. Read the [repeat guide](REPEATING_MESSAGES.md) before relying on a long-running schedule.

## Ghost Mode

Open **Settings → Ghost Mode** to enable the mode and choose its account-specific controls:

- Hide online status and outgoing typing/recording indicators.
- Control outgoing read receipts, including marking a chat as read when replying.
- Control story-view reporting and stealth-send behavior.

These controls change requests sent by this client. They do not make an account anonymous or establish guarantees about what Telegram's servers, recipients, or another signed-in client observe. Behavior can vary with message type and server changes. Test the settings with another account before depending on them.

## Hidden chats

Select chats in the chat list and use the hide action. Configure the hidden area under **Settings → Hidden Chats**:

- Use the app lock passcode or a separate PIN.
- Choose opening triggers, including title/search shortcuts and optional visible entry points.
- Hide or disguise notifications.

Hidden chat selections and their settings are local to the account on this device. Hiding a chat is a local interface feature; it does not create a new encrypted storage format or hide the conversation in other Telegram clients.

## Local history and media

The settings include optional retention of received deleted messages, media from deleted messages, message edit history, and cached story-viewer lists. They can only retain information this client received or cached; enabling a switch cannot recover content that was never available on this device.

Retained content uses local storage and can remain after the original message changes or is deleted remotely. Clearing app data or uninstalling can remove it. Consider the people and content involved before enabling retention. Cached viewer lists may be incomplete or outdated.

The client also includes controls for saving restricted content and retaining self-destructing media. These affect local handling, do not guarantee capture, and do not change the permissions or expectations of the content's owner. Use them only where you have permission.

## Interface and account behavior

Moagram has its own icon, app name, and package, plus additional chat/search controls. Many Telegram features remain inherited from upstream. Custom settings differ in scope: Ghost Mode and hidden-chat rules are account-specific, while some retention switches are shared app settings. Review settings after switching accounts.

These features are part of an early preview. See [development checks](DEVELOPMENT.md) for the scope of automated tests and remaining device coverage.

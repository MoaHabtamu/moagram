# Custom features

[Documentation index](README.md)

## Repeating scheduled messages

Free accounts use a rule saved on the device to keep ordinary Telegram scheduled messages queued ahead. Moagram also asks Android for a wakeup about every twelve hours to refill that queue while the app is closed. Android can delay or block that wakeup, so opening the app remains the reliable way to refill. A paused rule posts a notification that opens Manage Repeats. Manage rules per chat from the chat menu or across all chats under **Settings → Moagram Settings → Repeating Messages**. Premium accounts retain Telegram's native repeat path. Read the [repeat guide](REPEATING_MESSAGES.md) before relying on a long-running schedule.

## Ghost Mode

Open **Settings → Ghost Mode** (or **Settings → Moagram Settings → Ghost Mode**) to enable the mode and choose its account-specific controls:

- Hide online status and outgoing typing/recording indicators.
- Control outgoing read receipts, including marking a chat as read when replying, plus a per-chat **Mark as Read Now** overflow action when Ghost Read is active.
- Control story-view reporting and stealth-send behavior.

These controls change requests sent by this client. They do not make an account anonymous or establish guarantees about what Telegram's servers, recipients, or another signed-in client observe. Behavior can vary with message type and server changes. Test the settings with another account before depending on them.

## Hidden chats

Select chats in the chat list and use the hide action. Configure the hidden area under **Settings → Moagram Settings → Hidden Chats**:

- Use the app lock passcode or a separate PIN.
- Choose opening triggers inline, including title/search/menu/tab shortcuts and an optional top-bar lock button.
- Hide or disguise notifications.

Hidden chat selections and their settings are local to the account on this device. Hiding a chat is a local interface feature; it does not create a new encrypted storage format or hide the conversation in other Telegram clients.

## Local history and media

Under **Settings → Moagram Settings → Local History & Media**, app-wide switches provide optional retention of received deleted messages, media from deleted messages, message edit history, and cached story-viewer lists. They can only retain information this client received or cached; enabling a switch cannot recover content that was never available on this device.

Retained content uses local storage and can remain after the original message changes or is deleted remotely. Clearing app data or uninstalling can remove it. Consider the people and content involved before enabling retention. Cached viewer lists may be incomplete or outdated.

This screen also includes controls for saving restricted content, bypassing channel restrictions, and retaining self-destructing media. These affect local handling, do not guarantee capture, and do not change the permissions or expectations of the content's owner. Use them only where you have permission.

## Interface, confirmations, and background health

Open **Settings → Moagram Settings → Interface & Confirmations** to configure optional everyday UX tweaks (all app-wide):

- **Accidental-action guards:** Confirm before starting voice/video calls, sending stickers, or sending GIFs.
- **Chat list & compose:** Hide the Stories bar, hide the Gift button in the message input bar, and disable pulling up at the bottom of a channel to jump to the next unread channel.
- **Messages & profiles:** Show exact seconds on message timestamps and show copyable numeric IDs on user, group, and channel profiles.
- **Media & downloads:** **Download Speed Boost** (enabled by default for free accounts) uses 8 parallel download connections with 512 KB pipelined chunks (`128 KB` for small files) instead of Telegram's default 2-connection / 2 MB byte-budget throttle, improving throughput especially on distant datacenters and lossy mobile networks. When the download is waiting on a chunk that other chunks have overtaken and that has taken more than 3× the recent average (2–12 s), the chunk is re-requested on another connection and the first copy to arrive wins. This shortens the stall near the end of a download, which otherwise lasts until the slow connection recovers or its 25 s idle timeout reconnects it. Turn it off if your network or account hits rate limits.

**Settings → Moagram Settings** also shows a **Background & Delivery Health** section reporting Android battery optimization exemption, system notification permission, and Keep-Alive status, with one-tap shortcuts to the relevant system settings.

Custom settings differ in scope: Ghost Mode, hidden-chat rules, and repeating rules are account-specific, while Local History & Media and Interface & Confirmations apply across all accounts on the device. Review settings after switching accounts.

These features are part of an early preview. See [development checks](DEVELOPMENT.md) for the scope of automated tests and remaining device coverage.

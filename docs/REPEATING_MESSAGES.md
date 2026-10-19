# Repeating scheduled messages

[Documentation index](README.md)

## Create a series

1. Open a chat, such as Saved Messages, and compose a supported message.
2. Long-press Send and choose **Schedule Message**.
3. Choose the first future date/time and a repeat interval, such as **Daily**.
4. Confirm the schedule, then open **Chat menu → Manage Repeats** to check the local rule and queued count.

A free-account local rule keeps **up to seven future occurrences** queued. Queue filling needs a connection and can take time; inspect the rule if it stays at one message. Premium accounts use Telegram's native repeating messages and may not appear as local rules in Manage Repeats.

## What “seven occurrences” means

| Rules | Maximum local queue, when limits allow |
| --- | --- |
| One Daily rule at 09:00 | Seven occurrences at 09:00 on successive days |
| Three Daily rules at 09:00, 09:01, and 09:02 | Seven per rule, up to 21 messages total |
| One Weekly rule | Up to seven weekly occurrences |

The first selected date is the start of the series. Seven occurrences does not always mean seven days. As messages leave the queue, Moagram replenishes it while the app runs and connects. The series continues until stopped or interrupted by a condition requiring attention; it is not an independent always-on service.

Already queued messages are sent by Telegram even if this device is offline. If the app stays offline long enough to exhaust the queue, the series cannot continue until it reconnects. Missed dates are skipped instead of sending a backlog. Simply having the app installed does not guarantee replenishment if Android suspends it.

## Intervals and limits

The normal picker uses fixed intervals: 1, 7, 14, 30, 91, 182, and 365 days. Month/year labels represent fixed day counts, not calendar-month arithmetic. The cadence follows elapsed seconds; changing time zones or daylight-saving time can change the displayed local hour.

The local scheduler also enforces:

- At most seven queued occurrences per rule.
- A scheduling horizon of 364 days from the current time.
- A shared 90-message queue threshold per chat, including other scheduled messages.
- A minimum scheduling lead time of 60 seconds.

Long intervals, an already busy chat queue, or server limits can leave fewer than seven occurrences queued. The 364-day horizon particularly limits annual and other long-interval series. This feature does not bypass Telegram's server-side limits or grant Premium features.

## Supported content

Supported: single text messages, photos, documents (including videos and stickers), contacts, static locations, and single forwarded messages.

Excluded: albums, polls, paid messages, rich articles, and self-destructing media. Forwarded series require continuing access to the original message. Expired media references can prevent replenishment after a long absence.

## Manage or stop a series

Open **Manage Repeats** from the chat menu, scheduled-message screen, or schedule picker. Select a series to inspect its interval, queued count, and any error.

- **Retry:** available for a paused rule after you address its error.
- **Stop:** cancels the local series and requests removal of its remaining queued messages.
- **Stop while offline:** cancellation is saved locally and sent when the app reconnects. Messages may still send before the cancellation reaches Telegram.
- **Delete one occurrence:** skips that occurrence.
- **Edit one occurrence:** changes that occurrence only, not the series template.
- **Change a queued occurrence to Never:** preserves that occurrence and stops the rest of the local series.

Rules are stored separately per account on this device and cleared on logout. They are not synchronized as Moagram rules to other devices. Do not assume logging out cancels scheduled messages already stored on Telegram; stop the series while connected first if you want those removed.

## Troubleshooting

| Symptom | Check |
| --- | --- |
| Only one scheduled message, no local rule | Confirm you selected a repeat interval before scheduling, that the message type is supported, and whether the account uses the native Premium path. |
| A paused rule | Read its exact error in Manage Repeats, resolve the cause, then use Retry. Ambiguous recovery pauses for review instead of risking duplicates. |
| Fewer than seven occurrences | Check the 364-day horizon, the chat's scheduled queue, connectivity, and server errors. |
| Series stopped after time offline | Open Moagram and reconnect. Missed dates are skipped; confirm new future occurrences appear. |
| An edited message did not change later messages | Edits affect the selected occurrence only. Stop and recreate the rule to change its template. |

For a reproducible problem, use the [bug report form](https://github.com/MoaHabtamu/moagram/issues/new?template=bug_report.yml). Include the interval, selected first date/time, time zone, queued count, free/Premium account type, and exact error text. See [test coverage](../Tools/tests/local-repeats.md) for technical details.

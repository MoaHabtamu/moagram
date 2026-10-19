# Free repeating schedules

Free accounts use a recurrence rule saved on the device, and ordinary Telegram scheduled messages.
Each series queues up to seven future occurrences, within a 364-day horizon and a shared limit of
90 queued messages per chat. The existing account timer replenishes the queue while connected.
Already queued messages send without the device online. Missed occurrences are skipped; rules
do not produce a backlog when the app reconnects. Intervals match Telegram's fixed day counts.

Rules are separate for each account and removed on logout. Stop a series from **Manage Repeats**
in the chat menu, scheduled-message screen, or schedule picker. Stopping while offline saves the
cancellation and removes queued messages on reconnection. Choosing **Never** when changing a
queued occurrence's schedule keeps that occurrence and stops the rest of its local series.
Deleting an individual occurrence skips it. Editing its content changes that occurrence only.
Premium accounts use Telegram's native repeating schedule; existing local series continue locally.

Single text messages, photos, documents (including videos and stickers), contacts, static locations
and single forwarded messages are supported. Paid messages, albums, polls, rich articles and
self-destructing media are excluded. Media references are refreshed from queued occurrences;
expired references can prevent replenishment after a long absence. Forwarded series require
continued access to the original message. An unconfirmed or ambiguous recovery pauses for review.

## Verification

```sh
./gradlew :TMessagesProj:compileStandaloneJavaWithJavac --offline --console=plain
bash Tools/tests/run-local-repeats.sh
```

The JVM tests use real compiled TL classes with Android and JSON adapters under `Tools/tests/jvm`.
They cover interval/cadence boundaries, queue caps, missed occurrences, date overflow,
native-repeat flag removal, retry keys, reply/topic metadata, text formatting and media references.
These adapters are outside the Android source sets and are never packaged in the app.

The callback test compiles and executes the actual compose date-picker callback bodies with
a recording sender. It checks all eight interval choices at each entry point, including the
long-press Schedule Message action, which previously discarded the chosen repeat interval.

The queue test runs the production controller with an injected clock, in-memory preferences and
a simulated Telegram transport. It reproduces the former one-message/paused state caused by
capturing a `send_as` object whose flag was disabled, and checks seven daily occurrences,
restart without duplicates, replenishment, stopping, three rules producing 21 occurrences,
repairing a saved paused rule, and visible/retryable server errors. Its JSON adapters use Gson
from the machine or the existing Gradle cache; `REPEAT_GSON_JAR` can supply its path explicitly.
This is a simulated transport test, not a live Telegram delivery test.

Device checks still needed with a free test account:

1. Schedule a repeating text message in Saved Messages; check the queue and Manage Repeats.
2. Restart Moagram; confirm the rule remains and queued occurrences are not duplicated.
3. Test a photo, document, forwarded message, and forum-topic reply.
4. Skip one occurrence, edit one occurrence, change a series to Never, and stop a series.
5. Stop while offline, reconnect before the next occurrence, and verify the queue is removed.
6. Exhaust the queued occurrences offline, reconnect, and verify missed messages are skipped.
7. Verify account isolation, logout cleanup, a full schedule queue and server rate limits.
8. Verify a Premium account still sends a native repeat request.

Telegram's test DC exposes one-minute and five-minute intervals for quicker device checks.

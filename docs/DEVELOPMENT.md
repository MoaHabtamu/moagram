# Development guide

[Documentation index](README.md) · [Contributing](../CONTRIBUTING.md)

## Repository map

| Location | Responsibility |
| --- | --- |
| `TMessagesProj/` | Shared Android client, Java code, resources, and native libraries |
| `TMessagesProj_AppStandalone/` | Standalone APK packaging, signing configuration, and application loader |
| `TMessagesProj/src/main/java/org/telegram/messenger/LocalRepeatController.java` | Local rule storage, queue reconciliation, transport, retry, and recovery |
| `TMessagesProj/src/main/java/org/telegram/messenger/RepeatSchedule.java` | Cadence calculations and queue limits |
| `TMessagesProj/src/main/java/org/telegram/ui/Components/AlertsCreator.java` | Repeat picker and Manage Repeats UI |
| `TMessagesProj/src/main/java/org/telegram/ui/Components/ChatActivityEnterView.java` | Compose scheduling callbacks |
| `TMessagesProj/src/main/java/org/telegram/messenger/GhostModeController.java` | Per-account Ghost Mode configuration |
| `TMessagesProj/src/main/java/org/telegram/messenger/HiddenChatsController.java` | Per-account hidden chat state and passcode handling |
| `TMessagesProj/src/main/java/org/telegram/ui/GhostModeActivity.java` | Custom privacy and retention settings |
| `Tools/tests/` | Repeat callback, cadence, protocol, and simulated queue checks |
| `docs/` | User and developer documentation |

Other app modules and most native/vendor code are inherited from Telegram. Prefer focused changes to the relevant client code and avoid unrelated vendor edits.

## Repeat architecture

Free-account repeat rules live in per-account local preferences. `LocalRepeatController` uses the existing account timer to reconcile them against Telegram's scheduled-message history, then adds future ordinary scheduled messages while the app is active and connected. `RepeatSchedule` supplies fixed intervals, lead time, horizon, and queue caps.

Telegram stores and sends already queued messages. The local controller is responsible for replenishment, retry/recovery, and cancellation. Premium repeat requests retain the native server path. Test adapters under `Tools/tests/jvm` are outside Android source sets and are never packaged in the app.

## Run checks

After completing the [build setup](BUILDING.md), compile the real Telegram classes and run the suite:

```bash
./gradlew :TMessagesProj:compileStandaloneJavaWithJavac --console=plain
bash Tools/tests/run-local-repeats.sh
```

The current suite reports 758 checks: 32 scheduling callback checks, 18 passcode-flow checks, 55 Ghost Mode request checks, 518 photo-permission checks, 21 interval checks, 16 protocol checks, and 98 queue checks. It covers seven-occurrence queues, three rules producing 21 occurrences, restart without duplication, replenishment, cancellation, recovery, and errors. Passcode checks execute the production success dispatcher and input-mode selection, covering hidden-chat callbacks, app settings, and Telegram's wallet authorization path. Request checks execute the production read/story guards and background-send flags with a recording transport, including one-shot read allowances and account isolation. Photo checks execute the production sharing and secure-window conditions and rich-media parent lookup, covering protected articles with the Moagram saving option enabled and disabled. This is simulated transport and UI-dispatch coverage, not a live delivery or security audit.

The runner also executes 36 in-app update policy/signature checks and nine release-feed tests. These cover the daily reminder boundary, version/Android compatibility, URL restrictions, corrupted downloads, cryptographic signature verification, wrong signing identities, and release metadata mismatches. See [In-app updates](IN_APP_UPDATES.md).

Contact sorting checks execute the production comparator and contact-screen status/resume handlers against mixed online, offline, private, approximate, bot, and missing-user states. They compare ordering with the actual contact-row Online condition, exercise live status changes and privacy-hint expiry, and preserve alphabetical mode. Run them independently with `python3 Tools/tests/test-contact-sorting.py`.

For a quick compose-only check with Python 3 and a JDK, without Android SDK setup:

```bash
python3 Tools/tests/test-schedule-callbacks.py
```

The full runner reads `sdk.dir` or `ANDROID_HOME`/`ANDROID_SDK_ROOT`. Override `REPEAT_CLASSES` if your compiled classes are in another variant directory. It finds Gson from the machine or Gradle cache; set `REPEAT_GSON_JAR` if needed. See [the detailed test notes](../Tools/tests/local-repeats.md).

## Device verification

For scheduling changes, use a test chat/account and verify text and media, restart, offline/reconnect behavior, queue limits, editing/deleting one occurrence, stopping, multi-account isolation, and the Premium path when relevant. The detailed notes contain a checklist of cases still needing broader device coverage.

For privacy or hidden-chat changes, verify both the app UI and what another account/client sees. Never describe simulated tests as proof of server behavior or anonymity.

## Working with upstream

The default development branch is `master`. For your own contribution, fork Moagram and create a topic branch. Maintainers can track Telegram separately:

```bash
git remote add upstream https://github.com/DrKLO/Telegram.git
git fetch upstream
```

Fetching is separate from merging. Review upstream updates in an isolated branch and run relevant checks before changing the working release. Preserve attribution and license notices.

Maintainers have a daily **Check Telegram updates** workflow that prepares a draft
PR or reports merge conflicts. See [automated upstream updates](UPSTREAM.md#automated-telegram-updates)
for setup, recovery, CI limitations, and the required merge-commit strategy.

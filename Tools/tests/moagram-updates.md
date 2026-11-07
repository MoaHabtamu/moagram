# Update flow verification

Automated checks (no device required):

```sh
python3 Tools/tests/test-update-flow.py
python3 Tools/tests/test-update-layout.py
python3 Tools/tests/test-moagram-updates.py
python3 Tools/tests/test-update-feed.py
./gradlew :TMessagesProj_AppStandalone:compileAfatStandaloneJavaWithJavac --offline
```

The layout regression compiles the page’s scroll-layout expression with the actual
layout helper to detect zero-height content caused by overload selection.

The flow harness executes the production controller with deterministic Android and
HTTPS doubles. Signature and hash checks run separately against real signed APKs.

On a standalone Moagram installation with a newer compatible release in the feed:

1. For a critical release, tap **Download update** in the alert. The current screen
   should remain open, with percentage progress and a cancel action in the bottom
   update bar above Android navigation. Continue switching between the main tabs.
2. Open **Moagram Updates** manually. Check for an update, start downloading, and
   confirm that the release card shows percentage and downloaded/total size. Go
   back: the same download should continue in the main bottom bar. Cancel and retry.
3. Disable Android's **Allow from this source** permission for Moagram. Finish a
   download. Confirm that the ready dialog and release card explain the permission
   restriction and offer **Allow installation**. Returning without granting it must
   retain the verified APK and avoid reopening Settings automatically.
   Both the dialog and page should also explain a possible Play Protect scan.
   Repeat with installation permission already granted: the explanation must
   still appear, and **Install update** must open Android's installer without an
   extra Moagram confirmation. A scan result must never be described as guaranteed.
4. Enable that permission and return. Android's package installer should open once,
   without another download. Cancel the installer and return: it should stay closed
   until **Install update** is tapped again.
5. Repeat the permission flow after killing the app process while Settings is open,
   and after rotating the device during installation preparation. The verified APK
   should be recovered and the installer should open on the resumed activity.
6. Finish a download while the app is paused or locked. The ready notice should wait
   until Moagram is foregrounded and unlocked.
7. Check narrow screens, large text, RTL, light/dark themes, gesture navigation,
   and three-button navigation. The page must scroll, and the bottom bar must not
   cover content or Android navigation.

Before activating a public feed, complete and record the actual Play Protect
device test in [the release guide](../updates/README.md#play-protect-device-test-before-release).
The automated tests validate record handling; they do not run Google's scanner.

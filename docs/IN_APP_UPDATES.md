# In-app updates

Users choose **Settings → Moagram Updates → Check for updates**, or **Moagram Updates** on the welcome screen. Opening the page does not check for regular updates. A check reads a small public metadata file; it does not download an APK. Users choose **Download update** and can continue chatting while the bottom bar on other pages shows progress. The updates page shows the percentage and downloaded size, and downloads can be cancelled. Once the APK is verified, Moagram offers **Install update** and Android asks for confirmation. If installation permission is missing, Moagram explains the restriction and offers **Allow installation**. Grant **Allow from this source** for Moagram and return: the installer opens automatically. Verified downloads and pending install requests survive app restarts; cached APKs are verified again before use.

The client accepts only HTTPS APK URLs from this repository’s GitHub releases and GitHub’s release-assets CDN. Before opening the installer it verifies the exact size, SHA-256, the cryptographic APK signature, the installed app’s signing identity, package name, and a strictly newer Android version code. Failed or cancelled downloads never open the installer. APKs remain in the app’s private update cache and are shared with Android through FileProvider only for installation. Keep the original signing key for future releases; signing-key rotation is not supported by this updater.

Before installation, the ready dialog and update page explain that Google Play
Protect may offer a scan because the APK was downloaded outside Google Play.
Users can choose **Scan app**, wait for its result, and cancel installation if a
threat is reported. This explanation does not add another confirmation dialog or
disable Android's protection.

## Critical reminders

While Moagram is open, it checks the small metadata feed at most once every six hours, independently of manual regular-update checks. A maintainer can mark a release as critical for installed version codes below a chosen threshold. Eligible users see a dismissible **Important Moagram update** notice at most once per 24 hours across all critical versions. **Later**, Back, or dismissal leaves the app usable. **Download update** starts the download directly without navigating away. The same bottom progress bar is used for downloads started from critical notices and from the manual update page. Notices are suppressed while the app is locked, in the background, updating, or on an Android version that cannot run the advertised APK. Failed background metadata checks stay quiet.

APK downloads begin only after the user chooses Download update; they continue when the user leaves the update page. Keep Moagram open while downloading. There are no scheduled APK downloads, forced installs, or account/device identifiers in these requests. Notice changes can take up to six hours plus GitHub cache propagation to reach an open client. A successful notice dismissal does not block future manual checks.

Clients released before this updater was added must install a new APK once; older binaries cannot receive these notices.

## Publish a normal release

1. Build and sign the APK using the existing private key. Complete the [Play Protect device test](../Tools/updates/README.md#play-protect-device-test-before-release), record the actual result, and publish the exact tested APK as a GitHub release with an APK asset. Preserve the original filename locally.
2. Prepare the feed using the published APK and its release tag:

```bash
python3 Tools/updates/publish-update.py \
  --tag v13.1.1-moagram.2 \
  --apk /path/to/Moagram-13.1.1-preview.2.apk \
  --scan-report /path/to/preview.2.play-protect.json
```

The tool reads the actual APK version/package/minimum Android version and computes its checksum. It checks the uploaded asset’s name, size, digest, state, and URL. Draft releases and mismatched files are rejected. It writes `updates/android.json` locally for review; it does not push changes or publish a release. The Android SDK’s `aapt` is located using local SDK settings or `--aapt`.

3. Review, commit and push `updates/android.json` to `master`. The app reads that file from the official raw GitHub URL. Regular releases preserve the previous feed's critical cutoff and message by default. Users who missed the earlier critical release still receive a critical alert and download the latest APK directly. Users at or above the cutoff receive no critical alert; newer versions remain available through manual checks. Use `--critical-below 0` only to deliberately clear the requirement for all users.

## Mark a critical update

After publishing the corrective APK, supply its **Android version code**, not its Telegram version string:

```bash
python3 Tools/updates/publish-update.py \
  --tag v13.1.1-moagram.2 \
  --apk /path/to/Moagram-13.1.1-preview.2.apk \
  --scan-report /path/to/preview.2.play-protect.json \
  --critical-below 723719 \
  --critical-message 'This release fixes an important issue. Please update.'
```

This example targets installed version codes below `723719`. The threshold cannot exceed the advertised APK’s code. Use an accurate explanation for the affected users. Commit and push the resulting JSON to activate it. To withdraw the requirement, explicitly pass `--critical-below 0` and publish that JSON. Omit the critical flags to retain the previous cutoff when advertising a newer regular APK. Optional `--notes-file` supplies plain-text release notes shown on the update page.

The app still requires user approval to download and install a critical update. The reminder frequency is fixed in the client; the feed cannot turn it into a forced update or increase it beyond once per day.

## Fork release revisions

Starting with preview 11, standalone Android version codes use `Telegram APP_VERSION_CODE * 1000 + MOAGRAM_VERSION_REVISION * 10 + ABI digit`. The universal ABI digit is `9`; revision `10` produces code `7237109` for Telegram base code `7237`, and version name `13.1.1-Moagram.11`. Revision is zero-based (`0..99`); increment it for another Moagram release on the same Telegram base, or reset it when the upstream base code increases. Previews 1–10 used a multiplier of `100`; the expanded range keeps preview 11 newer than those releases and reserves room for future previews. Do not lower a published Android version code. This keeps Telegram’s version properties available for independent upstream merges.

## Verification

`python3 Tools/tests/test-moagram-updates.py` exercises production version/URL/reminder policy and verifies real test APK signatures, wrong signers, hash/size mismatches, and a tampered APK whose metadata hash has been updated. It uses temporary public test keys. `python3 Tools/tests/test-update-feed.py` checks release/feed preparation. `python3 Tools/tests/test-update-flow.py` executes the production controller against Android/network doubles to cover critical downloads, progress notifications, permission denial/grant, process and activity recreation, cancellation, deferred notices, and installer reverification. These checks run with the existing app regression suite in Android CI. Device verification steps are listed in [the update-flow checklist](../Tools/tests/moagram-updates.md).

# In-app updates

Users choose **Settings → Moagram Updates → Check for updates**, or **Moagram Updates** on the welcome screen. Opening the page does not check for regular updates. A check reads a small public metadata file; it does not download an APK. Users separately choose **Download update**, then **Install update**, and confirm Android’s installer. Downloads can be cancelled. If Android asks to allow installations from Moagram, grant that permission and return to tap **Install update** again.

The client accepts only HTTPS APK URLs from this repository’s GitHub releases and GitHub’s release-assets CDN. Before opening the installer it verifies the exact size, SHA-256, the cryptographic APK signature, the installed app’s signing identity, package name, and a strictly newer Android version code. Failed or cancelled downloads never open the installer. APKs remain in the app’s private update cache and are shared with Android through FileProvider only for installation. Keep the original signing key for future releases; signing-key rotation is not supported by this updater.

## Critical reminders

While Moagram is open, it checks the small metadata feed at most once every six hours, independently of manual regular-update checks. A maintainer can mark a release as critical for installed version codes below a chosen threshold. Eligible users see a dismissible **Important Moagram update** notice at most once per 24 hours across all critical versions. **Later**, Back, or dismissal leaves the app usable. **Review update** opens the update page; it does not download or install anything. Notices are suppressed while the app is locked, in the background, updating, or on an Android version that cannot run the advertised APK. Failed background metadata checks stay quiet.

There are no background APK downloads, scheduled jobs, forced installs, or account/device identifiers in these requests. Notice changes can take up to six hours plus GitHub cache propagation to reach an open client. A successful notice dismissal does not block future manual checks.

Clients released before this updater was added must install a new APK once; older binaries cannot receive these notices.

## Publish a normal release

1. Build and sign the APK using the existing private key. Test it and publish it as a GitHub release with an APK asset. Preserve the original filename locally.
2. Prepare the feed using the published APK and its release tag:

```bash
python3 Tools/updates/publish-update.py \
  --tag v13.1.1-moagram.2 \
  --apk /path/to/Moagram-13.1.1-preview.2.apk
```

The tool reads the actual APK version/package/minimum Android version and computes its checksum. It checks the uploaded asset’s name, size, digest, state, and URL. Draft releases and mismatched files are rejected. It writes `updates/android.json` locally for review; it does not push changes or publish a release. The Android SDK’s `aapt` is located using local SDK settings or `--aapt`.

3. Review, commit and push `updates/android.json` to `master`. The app reads that file from the official raw GitHub URL. `critical_below_version_code: 0` disables critical reminders; normal updates remain available through manual checks.

## Mark a critical update

After publishing the corrective APK, supply its **Android version code**, not its Telegram version string:

```bash
python3 Tools/updates/publish-update.py \
  --tag v13.1.1-moagram.2 \
  --apk /path/to/Moagram-13.1.1-preview.2.apk \
  --critical-below 723719 \
  --critical-message 'This release fixes an important issue. Please update.'
```

This example targets installed version codes below `723719`. The threshold cannot exceed the advertised APK’s code. Use an accurate explanation for the affected users. Commit and push the resulting JSON to activate it. Run the normal command without critical options and publish that JSON to withdraw a notice. Optional `--notes-file` supplies plain-text release notes shown on the update page.

The app still requires user approval to download and install a critical update. The reminder frequency is fixed in the client; the feed cannot turn it into a forced update or increase it beyond once per day.

## Fork release revisions

Standalone Android version codes use `Telegram APP_VERSION_CODE * 100 + MOAGRAM_VERSION_REVISION * 10 + ABI digit`. The universal ABI digit is `9`; revision `1` produces code `723719` for Telegram base code `7237`, and version name `13.1.1-Moagram.2`. Revision is zero-based (`0..9`); increment it for another Moagram release on the same Telegram base, or reset it when the upstream base code increases. Do not lower a published Android version code. This keeps Telegram’s version properties available for independent upstream merges.

## Verification

`python3 Tools/tests/test-moagram-updates.py` exercises production version/URL/reminder policy and verifies real test APK signatures, wrong signers, hash/size mismatches, and a tampered APK whose metadata hash has been updated. It uses temporary public test keys. `python3 Tools/tests/test-update-feed.py` checks release/feed preparation. Both run with the existing app regression suite in Android CI.

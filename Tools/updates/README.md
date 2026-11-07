# Publishing Android updates

Build and scan the final signed APK as described below. Publish and verify that
same APK as a GitHub release. Then prepare the
update feed with `publish-update.py`, review the JSON, and commit/push it to
activate the release. The script only writes a local file.

Start from the current `master` feed so the inherited cutoff reflects the latest
published policy.

## Play Protect device test before release

Use a test Android device with Google Play services and Play Protect enabled.
Copy the final signed release APK to it and install it using Android's installer.
If Play Protect offers **Scan app**, let the scan finish. If no scan is offered,
open Google Play Store → profile → Play Protect → Scan after installation. Confirm
the actual installed Moagram version matches the candidate being tested.

If a scan reports a threat, blocks installation, or cannot complete, investigate
before announcing the update. Record the actual message and device used. Do not
disable Play Protect to obtain a passing result.

Only after observing the completed result, record it (replace the device,
Android version, and message with the actual observations):

```sh
python3 Tools/updates/record-play-protect-scan.py \
  --apk /path/to/latest.apk \
  --device 'DEVICE MODEL' --android-version 'ANDROID VERSION' \
  --result no-threats-found --observed-message 'ACTUAL COMPLETED SCAN RESULT' \
  --output build/releases/latest.play-protect.json
```

The recorder **does not run a scan**. This is a manual maintainer test record,
not a Google-issued certificate, safety guarantee, or exemption from future
scan prompts. `warning` and `blocked` results can also be recorded but cannot be
used to prepare an update feed. Pending or failed scans are not passing results.

`publish-update.py` requires `--scan-report`. It checks the recorded result and
matches the APK's SHA-256 and size. Rebuilding, re-signing, or otherwise changing
the APK invalidates the record; test the new file again. Keep the report with the
release's internal test records; it is not added to the public feed.

Google's [Play Protect guidance](https://developers.google.com/android/play-protect/warning-dev-guidance)
explains the difference between an unknown-app scan request and a harmful-app
warning. Ordinary security-check prompts are not removed through appeals.

## Regular release after a critical release

Omit the critical flags for a regular release:

```sh
python3 Tools/updates/publish-update.py \
  --apk /path/to/latest.apk --tag vNEXT \
  --scan-report build/releases/latest.play-protect.json
```

By default the script reads `updates/android.json` and retains its
`critical_below_version_code` and `critical_message`. The version, APK URL, hash,
size, and release notes describe the newly published APK. `--previous-feed` can
select another baseline; `--output` only selects the destination. A missing or
unreadable baseline stops preparation rather than silently disabling alerts.

For example, preview 6 is critical with cutoff `723759`. When preparing a regular
preview 7 (`723769`), keep cutoff `723759`:

| Installed version code | Behavior after the feed refreshes |
| --- | --- |
| Below `723759` | Critical alert; Download gets preview 7 directly |
| `723759` through `723768` | No critical alert; preview 7 is available in the manual update page |
| `723769` or newer | No critical alert or upgrade offer |

Automatic checks remain limited to every six hours, and critical reminders to
once per day. Users do not install intermediate releases.

Prefer critical messages that explain the fix and ask users to install the
latest version. If the previous message names an older release, update its text
while retaining the cutoff:

```sh
python3 Tools/updates/publish-update.py \
  --apk /path/to/latest.apk --tag vNEXT \
  --scan-report build/releases/latest.play-protect.json \
  --critical-message 'The latest Moagram version includes the fix for the blank update page. Please install it.'
```

## Changing the critical requirement

For a newly critical release, explicitly supply the new APK's version code and a
fresh explanation:

```sh
python3 Tools/updates/publish-update.py \
  --apk /path/to/latest.apk --tag vNEXT \
  --scan-report build/releases/latest.play-protect.json \
  --critical-below NEW_VERSION_CODE --critical-message 'Explain the critical fix.'
```

Only use `--critical-below 0` to intentionally remove the critical requirement for
all users. This also clears the inherited message. Explicit thresholds do not
require a previous feed.

Run the release-policy and controller regressions with:

```sh
python3 Tools/tests/test-update-feed.py
python3 Tools/tests/test-update-flow.py
```

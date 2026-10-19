# Install and use Moagram

[Documentation index](README.md)

## Download and verify

Open [Moagram Releases](https://github.com/MoaHabtamu/moagram/releases) and select a release. Read its notes, then download the `.apk` asset and `SHA256SUMS.txt`. The preview is based on Telegram 12.10.1; it supports Android 5.0 and later and includes ARM and x86 architectures.

On Linux, put both downloaded files in the same directory and run:

```bash
sha256sum --check SHA256SUMS.txt
```

On macOS, use `shasum -a 256 <downloaded-apk>`. On Windows PowerShell, use `Get-FileHash .\<downloaded-apk> -Algorithm SHA256`. Compare the result with the checksum file. A checksum checks file integrity; obtain both files from this repository's release page.

## Install

1. Open the downloaded APK on your Android device.
2. If Android asks, allow installation from the browser or file manager you are using.
3. Complete installation, open Moagram, and sign in with your Telegram account.

The standalone package is `org.moagram.messenger.web`, so it can coexist with the official Telegram app. Android permissions and notification delivery depend on your device settings. Check notification and battery settings if messages do not appear promptly.

## Update an existing installation

Install a newer APK over the existing app. Android requires a compatible version code and the same signing key. A build you sign yourself generally cannot update a build signed by this project's maintainer.

If Android reports a signature conflict, use a compatible build or back up any local-only data before considering an uninstall. Uninstalling clears app data, including local repeat rules and locally retained history. Telegram cloud chats and local-only app data have different storage behavior.

## Find the custom features

- **Settings → Ghost Mode:** account-specific presence and receipt controls, plus local retention options. See [Features](FEATURES.md).
- **Settings → Hidden Chats:** opening shortcuts, passcode protection, and notification preferences.
- **Chat menu → Manage Repeats:** local series, queued counts, errors, retry, and stop controls. See [Repeating messages](REPEATING_MESSAGES.md).

Try new scheduling settings in **Saved Messages** first. Already queued scheduled messages are stored on Telegram; the local repeating rule is stored in Moagram on the device that created it.

## Get help

Search [Issues](https://github.com/MoaHabtamu/moagram/issues) before reporting a problem. Include your app version, Android version, device model, and reproduction steps. For schedule problems, include the repeat interval and the exact status/error shown in Manage Repeats.

Use [Discussions](https://github.com/MoaHabtamu/moagram/discussions) for setup questions and ideas. Remove phone numbers, login codes, message content, and other personal data from screenshots or logs. Report security vulnerabilities through the [private reporting process](../SECURITY.md).

<p align="center">
  <img src="TMessagesProj/src/main/res/mipmap-xxxhdpi/ic_launcher.png" alt="Moagram icon" width="104" height="104">
</p>
<h1 align="center">Moagram</h1>
<p align="center">An unofficial Telegram client for Android, with repeating messages, hidden chats, and configurable privacy controls.</p>
<p align="center">
  <a href="https://github.com/MoaHabtamu/moagram/releases">Download preview</a> ·
  <a href="docs/README.md">Documentation</a> ·
  <a href="https://github.com/MoaHabtamu/moagram/discussions">Ask a question</a> ·
  <a href="CONTRIBUTING.md">Contribute</a>
</p>

## What is Moagram?

Moagram builds on [Telegram for Android](https://github.com/DrKLO/Telegram) with features for everyday scheduling, chat organization, and control over local app behavior. It connects to Telegram using your Telegram account. It is an independent project and is not affiliated with or endorsed by Telegram.

The development source is based on Telegram **13.1.1** and requires **Android 6.0+**, matching the new upstream minimum. Published preview APKs may use an earlier base; check each release's notes. This is an early community project: the repeat queue has automated regression coverage, while the 13.1.1 update still needs broader device and privacy testing.

## Features

| Feature | What it does |
| --- | --- |
| **Repeating messages** | Queue up to seven future occurrences per local rule on free accounts, replenished while Moagram runs and connects. |
| **Manage Repeats** | See queued counts, inspect errors, retry a paused rule, or stop a series. |
| **Ghost Mode** | Configure online-presence, typing, read-receipt, and story-view behavior per account. |
| **Hidden chats** | Hide selected chats from the normal list, choose opening shortcuts, and configure a passcode and notifications. |
| **Local history controls** | Optional retention of received deleted messages, cached media, message revisions, and story-viewer lists. |
| **Custom interface** | Moagram branding and additional chat/search controls. |

Read the [feature guide](docs/FEATURES.md) for behavior and limits. Client-side privacy controls are not a guarantee of anonymity; Telegram servers and other clients can still observe account activity.

## Try it

1. Download the APK from [Releases](https://github.com/MoaHabtamu/moagram/releases). Preview releases are marked **Pre-release**.
2. Follow the [installation guide](docs/USAGE.md), including the checksum and update instructions.
3. Sign in with your Telegram account and explore **Settings → Ghost Mode**, **Settings → Hidden Chats**, or a chat's scheduling controls.

**A repeat example:** create three Daily rules at 09:00, 09:01, and 09:02. Each rule can queue seven occurrences, for up to 21 scheduled messages across the three rules. Moagram must run and connect regularly to refill the queue; it cannot continue indefinitely if the app stays offline. [How repeats work →](docs/REPEATING_MESSAGES.md)

## Build your own

```bash
git clone --recursive --shallow-submodules https://github.com/MoaHabtamu/moagram.git
cd moagram
cp local.properties.example local.properties
# Fill in your SDK path, Telegram API credentials, and signing settings.
./gradlew :TMessagesProj_AppStandalone:assembleAfatStandalone
```

Use JDK 17 or a compatible newer JDK, Android SDK 36, NDK 27.2.12479018, and CMake 3.22.1. The [build guide](docs/BUILDING.md) covers tool installation, API credentials, signing, Firebase configuration, and troubleshooting. Personal credentials and the Moagram signing key stay outside Git.

## Documentation

| I want to… | Start here |
| --- | --- |
| Install or update the app | [Usage and installation](docs/USAGE.md) |
| Understand the custom controls | [Features](docs/FEATURES.md) |
| Set up daily or weekly messages | [Repeating messages](docs/REPEATING_MESSAGES.md) |
| Compile my own APK | [Building](docs/BUILDING.md) |
| Find the relevant code and run checks | [Development](docs/DEVELOPMENT.md) |
| Keep Moagram current with Telegram | [Automated upstream updates](docs/UPSTREAM.md#automated-telegram-updates) |
| Report a bug or propose a change | [Contributing](CONTRIBUTING.md) |
| Report a vulnerability privately | [Security policy](SECURITY.md) |

## Help improve Moagram

Questions and early ideas belong in [Discussions](https://github.com/MoaHabtamu/moagram/discussions). Use the [issue forms](https://github.com/MoaHabtamu/moagram/issues/new/choose) for reproducible bugs and concrete feature requests. Pull requests for fixes, documentation, accessibility, translations, and tests are welcome; see [CONTRIBUTING.md](CONTRIBUTING.md).

## License and attribution

The repository includes the [GNU GPL v2 license](LICENSE) inherited from Telegram. Existing file notices and third-party licenses remain in place; see [upstream and third-party notes](docs/UPSTREAM.md). Moagram is not an official Telegram release and does not provide a Telegram Premium subscription or unlock server-controlled Premium features.

## Moagram for Android

Moagram is an unofficial Android client based on [Telegram for Android](https://github.com/DrKLO/Telegram). It includes custom branding, privacy controls, hidden chats, and repeating scheduled messages.

Repeats keep up to seven future occurrences queued for each rule. The app replenishes the queue when it runs and connects; open it regularly to keep repeats going. Manage Repeats in the chat menu lets you pause or stop a rule.

### Building Moagram standalone

Copy `local.properties.example` to `local.properties`, set your Android SDK path and your own Telegram API ID/hash, and configure your signing key. The local settings file, personal keystore, and APKs are ignored by Git. Back up the signing key and its passwords securely: updates to an existing installation require the same key.

Build with the Android SDK/NDK versions listed below and JDK 17:

```bash
./gradlew :TMessagesProj_AppStandalone:assembleAfatStandalone
```

The standalone app uses package `org.moagram.messenger.web`. APKs are generated under `TMessagesProj_AppStandalone/build/outputs/apk/afat/standalone/`. See [repeat tests](Tools/tests/local-repeats.md) for verification instructions.

The original Telegram attribution and development notes follow. For Moagram, use `local.properties` for API credentials and standalone signing instead of putting personal credentials in source files. Firebase configuration is inherited from upstream; configure your own project before distributing builds.

## Telegram messenger for Android (upstream)

[Telegram](https://telegram.org) is a messaging app with a focus on speed and security. It’s superfast, simple and free.
The upstream repository contains the official source code for [Telegram App for Android](https://play.google.com/store/apps/details?id=org.telegram.messenger).

## Creating your Telegram Application

We welcome all developers to use our API and source code to create applications on our platform.
There are several things we require from **all developers** for the moment.

1. [**Obtain your own api_id**](https://core.telegram.org/api/obtaining_api_id) for your application.
2. Please **do not** use the name Telegram for your app — or make sure your users understand that it is unofficial.
3. Kindly **do not** use our standard logo (white paper plane in a blue circle) as your app's logo.
3. Please study our [**security guidelines**](https://core.telegram.org/mtproto/security_guidelines) and take good care of your users' data and privacy.
4. Please remember to publish **your** code too in order to comply with the licences.

### API, Protocol documentation

Telegram API manuals: https://core.telegram.org/api

MTproto protocol manuals: https://core.telegram.org/mtproto

### Compilation Guide

**Note**: In order to support [reproducible builds](https://core.telegram.org/reproducible-builds), this repo contains dummy release.keystore,  google-services.json and filled variables inside BuildVars.java. Before publishing your own APKs please make sure to replace all these files with your own.

You will require Android Studio 2025.1.4, Android NDK 27.2.12479018 and Android SDK 36.

1. Clone the Telegram source code with its submodules:
   ```bash
   git clone --recursive --shallow-submodules https://github.com/DrKLO/Telegram.git Telegram
   ```
   In case you forgot the `--recursive` flag, change to the `Telegram` directory and run:
   ```bash
   git submodule init && git submodule update --init --recursive --depth=1
   ```
2. Copy your release.keystore into TMessagesProj/config
3. Fill out RELEASE_KEY_PASSWORD, RELEASE_KEY_ALIAS, RELEASE_STORE_PASSWORD in gradle.properties to access your  release.keystore
4.  Go to https://console.firebase.google.com/, create two android apps with application IDs org.telegram.messenger and org.telegram.messenger.beta, turn on firebase messaging and download google-services.json, which should be copied to the same folder as TMessagesProj.
5. Open the project in the Studio (note that it should be opened, NOT imported).
6. Fill out values in TMessagesProj/src/main/java/org/telegram/messenger/BuildVars.java – there’s a link for each of the variables showing where and which data to obtain.
7. You are ready to compile Telegram.

### Localization

We moved all translations to https://translations.telegram.org/en/android/. Please use it.

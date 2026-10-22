# Build Moagram standalone

[Documentation index](README.md)

## Toolchain

| Tool | Project configuration |
| --- | --- |
| JDK | 17 or a compatible newer JDK; the existing build was also checked with JDK 21 |
| Gradle | 8.13 through the included wrapper |
| Android Gradle Plugin | 8.13.2 |
| Android SDK platform / Build Tools | 36 / 36.0.0 |
| Android NDK | 27.2.12479018 |
| CMake | 3.22.1 |
| Minimum Android version | API 23 / Android 6.0 |

Install Git and the Android SDK command-line tools, or open the repository root in a compatible Android Studio installation. Ensure `java`, `javac`, and the SDK tools are available. The first build needs internet access to download dependencies; `--offline` only works after they are cached.

With `sdkmanager` configured, the required SDK packages can be installed with:

```bash
sdkmanager "platform-tools" "platforms;android-36" "build-tools;36.0.0" "ndk;27.2.12479018" "cmake;3.22.1"
sdkmanager --licenses
```

## 1. Clone the source

```bash
git clone --recursive --shallow-submodules https://github.com/MoaHabtamu/moagram.git
cd moagram
```

If you cloned without submodules:

```bash
git submodule update --init --recursive --depth=1
```

Use the pinned submodule commits. Updating their branches independently can change the native build.

## 2. Configure local values

```bash
cp local.properties.example local.properties
```

Edit `local.properties` with your SDK path and credentials:

| Property | Purpose |
| --- | --- |
| `sdk.dir` | Absolute Android SDK directory |
| `MOAGRAM_APP_ID` | Your numeric Telegram API ID |
| `MOAGRAM_APP_HASH` | Your Telegram API hash |
| `MOAGRAM_KEYSTORE_FILE` | Signing key path, relative to the repository root or absolute |
| `MOAGRAM_STORE_PASSWORD` | Keystore password |
| `MOAGRAM_KEY_ALIAS` | Signing alias |
| `MOAGRAM_KEY_PASSWORD` | Password for that alias |

Obtain your own API credentials through [Telegram's application setup](https://core.telegram.org/api/obtaining_api_id). Do not put credentials in `BuildVars.java` or commit your local settings. These are Java properties: escape backslashes in Windows paths, or use forward slashes, and escape special characters in values where required.

The standalone package is controlled by `APP_PACKAGE` in `gradle.properties`, with a `.web` suffix. Its current value produces `org.moagram.messenger.web`.

## 3. Set up signing

For an existing installation, use its original signing key and passwords. For a new development identity, create your own key interactively:

```bash
keytool -genkeypair -v \
  -keystore TMessagesProj/config/moagram_release.keystore \
  -alias moagram -keyalg RSA -keysize 3072 -validity 10000
```

Set the matching values in `local.properties`. The personal Moagram keystore and local settings are ignored by Git. If you choose another key filename, add it to your local ignore rules before staging files. Back up your key and credentials separately; losing the key prevents signing compatible updates.

The repository also contains upstream dummy signing material for development. The Gradle configuration falls back to those public values if custom signing properties are absent. Do not distribute APKs signed with the dummy key.

## 4. Configure services for your distribution

The checked-in `google-services.json` files derive from upstream configuration. For your own distribution, configure your own Firebase project with the package IDs you will ship and replace the relevant module files, including `TMessagesProj` and `TMessagesProj_AppStandalone`. Review other service integrations in `BuildVars.java` and the [upstream notes](UPSTREAM.md).

A successful compilation does not by itself verify push notifications, maps, account login, or other service-backed features with your configuration. Test them on a device.

## 5. Build and install

```bash
./gradlew :TMessagesProj_AppStandalone:assembleAfatStandalone --console=plain
```

On Windows, use `gradlew.bat` and your shell's equivalent copy commands. The universal `afat` APK includes `armeabi-v7a`, `arm64-v8a`, `x86`, and `x86_64` native libraries.

Output directory:

```text
TMessagesProj_AppStandalone/build/outputs/apk/afat/standalone/
```

Inspect that directory for the generated APK (`app.apk` in the current setup). Install with Android's package installer or `adb install -r <apk-path>` on a connected device. Android will reject an incompatible signing key or version downgrade.

For source checks and repeat regression tests, see [Development](DEVELOPMENT.md).

## Troubleshooting

| Problem | What to check |
| --- | --- |
| SDK/NDK/CMake not found | Installed versions, `sdk.dir`, and accepted SDK licenses |
| Native build errors after cloning | Initialize submodules at their pinned commits |
| Dependencies unavailable offline | Run once with network access, without `--offline` |
| Invalid API credentials | Set your own ID/hash in the ignored `local.properties` |
| Keystore or alias error | File path, alias, and both passwords; preserve the original key for updates |
| Gradle runs out of memory | Review the JVM heap setting in `gradle.properties` and available memory |
| APK installs but a service fails | Firebase/package configuration, service credentials, connectivity, and device logs |

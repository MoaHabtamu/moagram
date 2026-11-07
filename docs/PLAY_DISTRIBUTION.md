# Preparing a Google Play edition

[Documentation index](README.md)

The standalone edition keeps its GitHub APK updater. A Google Play edition must
update through Google Play and needs its own release preparation; do not upload
the current standalone APK unchanged or describe it as Play approved.

## Account setup by the owner

Create a [Google Play Console account](https://play.google.com/console/signup)
using the Google account that will own Moagram. Google currently requires a
one-time US$25 registration fee, acceptance of its distribution agreement, and
developer identity verification. Select the accurate personal or organization
account type. Personal accounts also have testing and Android device verification
requirements before production publication. Complete the requirements shown in
your console; account creation is not production approval.

These steps require the owner's payment details and identity documents. Do not
put either in the repository or share private signing keys to complete signup.
See [Google's setup instructions](https://support.google.com/googleplay/android-developer/answer/6112435).

## Prepare the edition after account setup

1. Choose and register the Play package name before the first upload. The current
   standalone package is `org.moagram.messenger.web`. A separate Play package
   installs alongside standalone and requires a separate login; it does not
   migrate the standalone installation's data automatically. If migration within
   the same package is desired, plan signing compatibility before enrolling in
   Play App Signing.
2. Configure the Play build's upload signing identity and Play App Signing. Back up
   the existing standalone key and continue using it for standalone updates.
   The repository's public dummy signing key must not be used for publication.
3. Disable GitHub APK update checks, downloads, installation actions, and critical
   alerts in the Play edition. Route update actions through Play. The existing
   Moagram updater accepts only the standalone package, but this alone is not a
   complete Play edition: Settings and any other entry points need suitable Play
   behavior. Google's [install-packages policy](https://support.google.com/googleplay/android-developer/answer/12085295)
   does not permit using that permission for self-updates outside Play.
4. Configure Moagram-owned Firebase, authentication, and other service settings
   for the chosen package and signing certificates. The current modules still
   contain inherited upstream service configuration; see [Building](BUILDING.md).
5. Audit actual permissions, SDKs, data collection/sharing, account deletion, and
   user flows. Prepare an accurate privacy policy, Data safety declaration,
   content rating, app access instructions for review, store text, and screenshots.
   Test Telegram login, notifications, privacy controls, and purchases before
   making claims in the listing. This guide does not establish policy compliance.
6. Build and validate the signed Android App Bundle for the chosen Play variant.
   Upload to an internal test track first; inspect Play's reports and complete
   the account's required testing before requesting production access and review.

There is no production Play edition, Play listing, or Play approval created by
the standalone Play Protect notice or manual scan record. Developer registration
also does not exempt an APK from Play Protect scans.

## Standalone releases meanwhile

The installer-ready dialog and manual update page explain a possible Play Protect
scan before installation, without adding another confirmation dialog. Users can
allow the scan and act on its result.

Follow the [standalone release device test](../Tools/updates/README.md#play-protect-device-test-before-release)
for each final signed APK. A recorded result is tied to that APK's bytes and
cannot guarantee that another device will omit the scan prompt.

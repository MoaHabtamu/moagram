# Upstream and third-party notes

[Documentation index](README.md)

## Telegram

Moagram is an independent modification of [Telegram for Android](https://github.com/DrKLO/Telegram). The current client version is based on Telegram 12.10.1. Original Git history, copyright notices, and the repository's [GNU GPL v2 license](../LICENSE) are preserved.

Moagram is not affiliated with or endorsed by Telegram. Its branding, package name, and custom behavior distinguish it from official releases. Telegram account, protocol, and server restrictions still apply.

Useful upstream references:

- [Telegram API documentation](https://core.telegram.org/api)
- [Obtain your own application credentials](https://core.telegram.org/api/obtaining_api_id)
- [MTProto documentation](https://core.telegram.org/mtproto)
- [Telegram security guidelines](https://core.telegram.org/mtproto/security_guidelines)
- [Upstream reproducible build information](https://core.telegram.org/reproducible-builds)
- [Telegram translation platform](https://translations.telegram.org/en/android/)

Use the [Moagram build guide](BUILDING.md) for this fork's local credential and signing configuration. The official Telegram reproducible-build instructions do not establish reproducibility for Moagram's preview APK.

## Inherited configuration

The repository retains upstream dummy release signing material and upstream-derived Firebase/service configuration. These are not the maintainer's private Moagram signing key. Personal Telegram API credentials and the private signing key are supplied through ignored local settings and are not part of the source repository.

Before distributing a derivative, configure your own application credentials, signing identity, package IDs, and service integrations. Existing third-party keys or identifiers in upstream code are not a substitute for reviewing your own service setup.

## Native libraries and dependencies

The project includes or references components such as FFmpeg, BoringSSL, WebRTC, libvpx, dav1d, OpenH264, libyuv, JLatexMath, and rlottie/tlottie. Individual components have their own license and attribution files in their source directories. Submodule sources are listed in [`.gitmodules`](../.gitmodules).

Preserve the applicable notices when modifying or distributing those components. The root license does not replace their individual notices.

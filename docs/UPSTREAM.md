# Upstream and third-party notes

[Documentation index](README.md)

## Telegram

Moagram is an independent modification of [Telegram for Android](https://github.com/DrKLO/Telegram). The development source is based on Telegram 13.2.1 and requires Android 6.0 or later, matching upstream. Original Git history, copyright notices, and the repository's [GNU GPL v2 license](../LICENSE) are preserved.

Moagram is not affiliated with or endorsed by Telegram. Its branding, package name, and custom behavior distinguish it from official releases. Telegram account, protocol, and server restrictions still apply.

Useful upstream references:

- [Telegram API documentation](https://core.telegram.org/api)
- [Obtain your own application credentials](https://core.telegram.org/api/obtaining_api_id)
- [MTProto documentation](https://core.telegram.org/mtproto)
- [Telegram security guidelines](https://core.telegram.org/mtproto/security_guidelines)
- [Upstream reproducible build information](https://core.telegram.org/reproducible-builds)
- [Telegram translation platform](https://translations.telegram.org/en/android/)

Use the [Moagram build guide](BUILDING.md) for this fork's local credential and signing configuration. The official Telegram reproducible-build instructions do not establish reproducibility for Moagram's preview APK.

## Automated Telegram updates

**Check Telegram updates** runs daily at approximately **05:23 Africa/Addis_Ababa**
(02:23 UTC), and can be started from GitHub's **Actions** tab using **Run workflow**.
GitHub may delay scheduled runs and disables schedules in inactive public repositories
after 60 days; re-enable the workflow in Actions if necessary.

The workflow fetches the official `DrKLO/Telegram` `master` branch. Telegram's tags
can lag its published source, so the checker uses Git ancestry rather than release
tags or version strings. A Telegram app-store update can arrive before its source
is published; it becomes available to Moagram once the source is public.

- If the upstream commit is already in Moagram's `master`, nothing changes.
- Otherwise, the checker tries ordinary merges into `codex/telegram-upstream`,
  retaining Moagram commits and any maintainer fixes already on that branch.
  If `gradle.properties` conflicts, it merges independent keys using Git's
  three versions of the file. This lets Telegram version changes coexist with
  Moagram's package settings. Different edits to the same key, duplicate keys,
  or unsupported property syntax still require manual resolution.
- A successful merge opens a pull request. The same workflow calls **Android
  checks** directly on the exact candidate commit: Python/Git safety tests, update
  feed checks, Java compilation, repeat regressions, and a standalone APK build.
  Bot-triggered PR workflows are not required for this path.
- After **all** checks succeed, a separate job fast-forwards `master` to that
  exact tested merge commit. Telegram's ancestry and Moagram's commits are retained;
  GitHub marks the PR merged once its commits are reachable from `master`.
  The promotion job runs scripts from the trusted workflow commit with write
  permission; candidate builds have read permission and no personal secrets.
- If `master` changes during validation, a normal push rejects divergent changes
  atomically. The next run incorporates the new base and validates again. Changes
  to the update branch also require another run. No force pushes are used.
- Conflicts abort the merge and open or update one issue with affected files and
  recovery commands. Unchanged conflicts do not produce another notification.
  Independent `gradle.properties` keys are resolved even alongside other conflicts;
  actual overlapping code edits still need a maintainer's decision. After resolving
  and pushing, rerun the checker to validate and merge the repair.
- Network fetches and idempotent API requests retry transient failures with bounded
  backoff. A failed run can be rerun safely, including after a successful promotion.
  Previously merged PRs do not pause later updates on the reused branch.
- Failed tests, build failures, draft/closed PRs, and genuine conflicts stop the
  workflow visibly. Convert the update PR to draft or close it to pause merging.
  Signed APK releases and the published update feed remain a separate release step.

The daily workflow is enabled only for `MoaHabtamu/moagram`. Fork owners must
deliberately change that repository guard before enabling their own checker.

Moagram-only strings are kept in `TMessagesProj/src/main/res/values/moagram.xml`
so new upstream strings can be appended without colliding with fork additions.

### GitHub configuration

Actions must be enabled. Under **Settings → Actions → General → Workflow
permissions**, enable **Allow GitHub Actions to create and approve pull requests**.
The workflow requests the specific write permissions it needs; it does not approve
PR reviews. `master` must permit the bot's normal fast-forward push. Repository
branch protection is respected; a rule requiring PR-based merges blocks promotion
and is reported as a failed run. No personal access token, Telegram API secrets, or release signing key is
required. Organization policies can restrict these settings.

Regular pull requests also run **Android checks**. You can run it manually on any
branch and optionally request an APK. CI APKs use the checked-in public dummy
signing identity and omit Telegram API credentials. They are **build artifacts,
not installable updates for the signed Moagram distribution or login testing**.
Use the private local build configuration for device testing and actual releases.
Artifacts expire after seven days. Native builds can be lengthy and consume
GitHub Actions minutes; review your account's allowance.

### Reviewing an update

Inspect the latest **Check Telegram updates** run and its candidate hash. Before
publishing a signed release, review the feature interactions and test repeating messages,
hidden chats, privacy controls, login, and upgrades on a device. Update version
references in the documentation and adjust the CI toolchain if Telegram changes
SDK/NDK/CMake requirements. A clean Git merge does not prove feature compatibility.

Automatic promotion retains the prepared merge commit and Telegram's ancestry.
If merging an update PR manually, use **Create a merge commit**.
Squashing or rebasing the update discards that relationship and can cause repeat
update attempts. Closing an unmerged update PR pauses that branch: reopen it to
continue, or delete the branch to let the next run start again from `master`.
The checker reports a failure while an unmerged PR is deliberately closed.
To pause all checks, disable **Check Telegram updates** in Actions.

The implementation is in [`Tools/upstream/sync-telegram.py`](../Tools/upstream/sync-telegram.py).
Its local Git safety tests need only Python and Git:

```bash
python3 Tools/tests/test-upstream-sync.py
```

## Inherited configuration

The repository retains upstream dummy release signing material and upstream-derived Firebase/service configuration. These are not the maintainer's private Moagram signing key. Personal Telegram API credentials and the private signing key are supplied through ignored local settings and are not part of the source repository.

Before distributing a derivative, configure your own application credentials, signing identity, package IDs, and service integrations. Existing third-party keys or identifiers in upstream code are not a substitute for reviewing your own service setup.

## Native libraries and dependencies

The project includes or references components such as FFmpeg, BoringSSL, WebRTC, libvpx, dav1d, OpenH264, libyuv, JLatexMath, and rlottie/tlottie. Individual components have their own license and attribution files in their source directories. Submodule sources are listed in [`.gitmodules`](../.gitmodules).

Preserve the applicable notices when modifying or distributing those components. The root license does not replace their individual notices.

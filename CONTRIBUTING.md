# Contributing to Moagram

Thanks for helping improve Moagram. Documentation, reproducible bug reports, translations, accessibility improvements, tests, and focused code changes are welcome.

## Choose the right place

| You have… | Use |
| --- | --- |
| A setup question or an early idea | [Discussions](https://github.com/MoaHabtamu/moagram/discussions) |
| A reproducible bug | [Bug report](https://github.com/MoaHabtamu/moagram/issues/new?template=bug_report.yml) |
| A concrete feature proposal | [Feature request](https://github.com/MoaHabtamu/moagram/issues/new?template=feature_request.yml) |
| A security vulnerability | [Private security reporting](SECURITY.md) |
| A fix or documentation improvement | A pull request targeting `master` |

Search existing issues and discussions first. For larger behavior changes, open an issue or discussion so the implementation can be agreed on before you spend substantial time coding. A report or proposal does not imply a delivery commitment.

## Report a bug

Include the app version/build, Android version, device model, reproduction steps, expected behavior, actual behavior, and whether it reproduces consistently. Use a test chat where possible. For repeat problems, include the interval, first scheduled date/time, time zone, queued count, free/Premium account type, and exact Manage Repeats error.

Remove phone numbers, login codes, API credentials, session files, private messages, and other personal data from screenshots and logs. Use the private reporting path for exploitable security issues.

## Suggest a feature

Describe the problem and who it affects, your proposed behavior, and any alternatives. Explain account, offline, privacy, and compatibility implications when relevant. Keep one feature per issue so people can discuss it clearly.

## Send a pull request

1. Fork the repository and clone your fork with submodules.
2. Create a focused branch, for example `fix/repeat-recovery` or `docs/install-guide`.
3. Follow the [build guide](docs/BUILDING.md) and [development guide](docs/DEVELOPMENT.md) as needed.
4. Keep the change small enough to review; follow nearby Java/XML style and avoid unrelated formatting or vendor changes.
5. Run checks relevant to the behavior you changed. For repeat changes, run the repeat suite and describe device testing. Documentation-only changes need link and content review, not a full APK build.
6. Open a PR against `master` explaining the problem, the resulting behavior, validation, and any limitations. Link the related issue and add screenshots for visible UI changes.

Do not commit `local.properties`, personal keystores, passwords, API credentials, built APKs, user databases, or session data. Keep local-only settings out of patches. Preserve existing licenses and copyright notices; contributions are made under the repository's applicable existing licenses.

## Review and collaboration

Be specific and respectful. Reviewers may request a smaller scope, tests, or changes to match existing behavior. Mark unfinished work as a draft and describe what remains. Documentation and code should describe what the app actually does, including limitations.

Read the [community guidelines](CODE_OF_CONDUCT.md). This is a community project with no guaranteed response or release schedule.

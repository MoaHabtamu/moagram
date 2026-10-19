# Security policy

## Report a vulnerability privately

Use [GitHub's private vulnerability report form](https://github.com/MoaHabtamu/moagram/security/advisories/new) for this repository. Do not open a public issue containing exploit details, account/session data, API credentials, or another person's private content.

Include the affected commit or app build, Android version, reproduction steps using a test account, expected impact, and any suggested mitigation. Share only the minimum data needed to reproduce the issue. There is no bounty program or guaranteed response time.

If private reporting is temporarily unavailable, do not post sensitive details publicly. A general issue asking the maintainer to restore a private reporting channel is appropriate.

## Scope and support

This project is an early preview. Security fixes are developed against the current `master` branch; there is no separate long-term support commitment for older previews. The repeat regression suite checks client logic and does not constitute a security audit.

Ghost Mode and hidden chats change client behavior and local presentation. They do not guarantee anonymity or add a separate encrypted storage layer. For a problem in official Telegram or an upstream dependency rather than Moagram's changes, use that project's own reporting process as appropriate.

## Local development secrets

Keep personal API credentials and signing settings in ignored `local.properties`, and keep private signing keys outside Git. Treat logs, screenshots, session files, local databases, and retained message content as potentially sensitive. If a credential is accidentally published, revoke or rotate it promptly; deleting the visible file alone does not remove Git history.

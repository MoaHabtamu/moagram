# Active use

[Documentation index](README.md)

This counts installs that report activity in Moagram. It does not count people, physical devices, or file downloads. Reinstalling or clearing app data can create a new install ID.

| Number | What it means |
| --- | --- |
| Last 24 hours | Different install IDs reporting within the last 24 hours. |
| Last 7 days | Different install IDs reporting within the last 7 × 24 hours. |
| Last 30 days | Different install IDs reporting within the last 30 × 24 hours. |
| Installs so far | Install IDs that have checked in at least once. |

An install reports activity on foreground entry, at most once per UTC day unless the app version changes. The report contains a random install ID, event type, and app version. It does not include a Telegram account, phone number, or messages. Several accounts in one installation count as one install. Reporting is enabled by default, with no checkbox on the updates page. Previously saved opt-outs remain respected. Reports are estimates of activity: offline opens, disabled reporting, and older builds may be missing.

The 30-day chart groups reports by UTC date, while the cards use rolling time windows. Today is marked incomplete. **Show daily counts** exposes exact values on mobile and to assistive technology. Version adoption shows each version's share of installs reporting in the last 30 days, using their latest reported version; inactive installs are excluded from the denominator.

**Returned in week two** measures whether an install reports on UTC days 7–13 after its first report (day 0). Only the 30 daily cohorts with completed second weeks are eligible: first seen 43 through 14 UTC days ago. Each install counts once, even if it reports on several days. A first report can arrive after an update, so this is return after first reporting, not retention after downloading. Installs without a recorded first day are excluded; no eligible history is shown as “not enough history,” not 0%. Daily history is kept for 180 days.

Counting starts only after a build containing the counter is installed and opened. Copies people already have stay invisible until then.

## See the numbers

```bash
python3 Tools/stats/report.py
```

The command stays empty until a collector is reachable and `updates/android.json` contains its address.

## Turn collection on with Cloudflare

Cloudflare runs the collector in `Tools/stats/cloudflare`. The Python file on this computer cannot stay online for other phones. In your own terminal:

```bash
cd "Tools/stats/cloudflare"
npx wrangler login
bash setup.sh
```

The login command opens Cloudflare in the browser. Approve it for the account you want to use. If Cloudflare asks you to choose a workers.dev name, pick one. When setup finishes, it prints a line like:

```json
"usage_url": "https://moagram-usage.YOUR-NAME.workers.dev/v1/event"
```

Put that line in `updates/android.json` and push that file to `master`. The next prepared update feed keeps this address. Installs look it up from the public update feed and report activity once per UTC day, with additional reports for version changes. If the address is missing, they do not send a report.

Open the worker address in a browser and sign in. The page shows four totals, a 30-day chart with exact daily counts, version adoption, and week-two return activity. The password is not in the app. A copy stays in `Tools/stats/cloudflare/private.json` on your computer, and that file is left out of GitHub. `python3 Tools/stats/report.py` still prints the same totals.

The page stays at zero until a reporting build is installed and opened. UTC scheduling requires the updated Android build; older builds keep their local-day schedule until updated. Deploying the dashboard does not change already collected history. No Cloudflare database migration is required for these dashboard metrics.

A public address can be called by anyone. The collector accepts at most 120 brand-new install ids in an hour, and it keeps accepting installs it has already counted. Treat a sudden jump as something to check.
## Verify changes

After compiling the Android app, run `python3 Tools/tests/test-usage.py`. This checks UTC boundaries in multiple device time zones, collector counts, version adoption, retention eligibility, and the Cloudflare HTTP/dashboard paths. The Worker tests need Node.js and Python 3 and can also run alone with `node --test Tools/tests/test-usage-worker.mjs`; they use a temporary SQLite database and never contact production.

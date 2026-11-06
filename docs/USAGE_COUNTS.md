# Active use

[Documentation index](README.md)

This counts phones that open Moagram. It does not count file downloads.

| Number | What it means |
| --- | --- |
| Opened in the last day | Different phones that opened Moagram since yesterday. |
| Opened in the last 7 days | Different phones that opened Moagram this week. |
| Opened in the last 30 days | Different phones that opened Moagram this month. |
| Phones that have reported | Phones that have checked in at least once. |

A phone reports at most once a day. The report is a random install id plus the app version. It does not include a Telegram account, phone number, or messages. Several accounts on one phone count as one phone. People can turn this off under **Settings → Moagram Updates → Count this install**.

Counting starts only after a phone installs a build that contains it and then opens that build. Copies people already have stay invisible until then.

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

Put that line in `updates/android.json` and push that file to `master`. The next prepared update feed keeps this address. Phones look it up from the public update feed and report at most once a day. If the address is missing, they do not send a report.

Open the worker address in a browser and sign in. The page shows the four totals and a 30-day chart. The password is not in the app. A copy stays in `Tools/stats/cloudflare/private.json` on your computer, and that file is left out of GitHub. `python3 Tools/stats/report.py` still prints the same totals.

The published app does not contain the counter yet. The page stays at zero until a build that contains it is installed and opened.

A public address can be called by anyone. The collector accepts at most 120 brand-new install ids in an hour, and it keeps accepting phones it has already counted. Treat a sudden jump as something to check.

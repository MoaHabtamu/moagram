#!/usr/bin/env python3
"""Show how many installs reported activity in Moagram.

Counts come from the usage collector, once a build that reports them is
installed and usage_url is set in updates/android.json.
"""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import time
import urllib.error
import urllib.request

from usage_store import Store

ROOT = Path(__file__).resolve().parents[2]
FEED = ROOT / 'updates' / 'android.json'
PRIVATE = ROOT / 'Tools' / 'stats' / 'cloudflare' / 'private.json'


def classify(releases):
    rows = []
    release_downloads = update_downloads = 0
    for release in releases:
        if not isinstance(release, dict) or release.get('draft'):
            continue
        for asset in release.get('assets') or []:
            name = asset.get('name') or ''
            if not name.endswith('.apk'):
                continue
            count = asset.get('download_count') or 0
            if isinstance(count, bool) or not isinstance(count, int):
                count = 0
            kind = 'update file' if name.endswith('-update.apk') else 'release file'
            if kind == 'update file':
                update_downloads += count
            else:
                release_downloads += count
            rows.append((release.get('tag_name') or '', name, count, kind))
    return rows, release_downloads, update_downloads


def github_releases():
    # gh colorizes JSON when a pager is set, and that text is not JSON.
    env = os.environ.copy()
    env['NO_COLOR'] = '1'
    env['GH_PAGER'] = ''
    env['PAGER'] = ''
    env.pop('GH_FORCE_TTY', None)
    env.pop('CLICOLOR_FORCE', None)
    completed = subprocess.run(
        ['gh', 'api', '--paginate', 'repos/MoaHabtamu/moagram/releases'],
        check=True, text=True, capture_output=True, env=env)
    text = re.sub(r'\x1b\[[0-9;]*[A-Za-z]', '', completed.stdout).strip()
    return json.loads(text)


def print_github():
    print('GitHub file downloads')
    try:
        releases = github_releases()
    except (OSError, subprocess.CalledProcessError, json.JSONDecodeError) as error:
        print(f'  Could not read GitHub releases: {error}')
        return
    rows, release_downloads, update_downloads = classify(releases)
    if not rows:
        print('  No APK assets found.')
        return
    for tag, name, count, kind in rows:
        print(f'  {tag}  {name}  {count}  {kind}')
    print(f'  Release-file downloads: {release_downloads}')
    print(f'  Update-file downloads: {update_downloads}')
    if update_downloads == 0:
        print('  Release-file downloads include people updating, until updates use a separate -update.apk file.')


def private_settings():
    try:
        data = json.loads(PRIVATE.read_text())
    except (OSError, json.JSONDecodeError):
        return {}
    return data if isinstance(data, dict) else {}


def event_url():
    url = ''
    if FEED.exists():
        try:
            url = json.loads(FEED.read_text()).get('usage_url') or ''
        except (OSError, json.JSONDecodeError):
            url = ''
    if not isinstance(url, str) or not url.startswith('https://') or not url.endswith('/v1/event'):
        url = private_settings().get('usage_url') or ''
    return url if isinstance(url, str) else ''


def read_token():
    token = os.environ.get('MOAGRAM_USAGE_TOKEN') or private_settings().get('read_token') or ''
    return token if isinstance(token, str) else ''


def print_usage(db):
    print('Active use')
    if db is not None:
        if not db.exists():
            print(f'  No usage database at {db}')
            return
        print_summary(Store(db).summary(int(time.time() * 1000)))
        return
    url = event_url()
    if not url.startswith('https://') or not url.endswith('/v1/event'):
        print('  No usage collector is configured.')
        print('  Publish Tools/stats/cloudflare with bash setup.sh, set usage_url')
        print('  to https://YOUR-WORKER.workers.dev/v1/event, and install a build that contains the counter.')
        return
    token = read_token()
    if len(token) < 16:
        print('  The totals are private. This computer does not have the read token.')
        return
    summary_url = url[:-len('/v1/event')] + '/v1/summary'
    request = urllib.request.Request(summary_url, headers={
        'Authorization': f'Bearer {token}',
        'User-Agent': 'Moagram-Report',
    })
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            summary = json.load(response)
    except urllib.error.HTTPError as error:
        if error.code == 401:
            print('  The totals are private. The read token on this computer was rejected.')
        else:
            print(f'  Could not read the totals: HTTP {error.code}')
        return
    except (OSError, json.JSONDecodeError) as error:
        print(f'  Could not read the totals: {error}')
        return
    print_summary(summary)


def print_summary(summary):
    heard = int(summary.get('new_installs') or 0) + int(summary.get('existing_installs') or 0)
    print(f"  Installs reporting in the last 24 hours: {summary.get('active_1d', 0)}")
    print(f"  Installs reporting in the last 7 days: {summary.get('active_7d', 0)}")
    print(f"  Installs reporting in the last 30 days: {summary.get('active_30d', 0)}")
    print(f"  Installs that have reported: {heard}")
    active = summary.get('active_30d') or 0
    for version in summary.get('by_version') or []:
        count = version.get('active_30d') or 0
        if count and active:
            name = version.get('version_name') or 'Unnamed version'
            print(f"  {name} (build {version['version_code']}): {count} active installs ({100 * count / active:.1f}%)")
    retention = summary.get('week_two')
    if retention:
        eligible, returned = retention['eligible'], retention['returned']
        if eligible:
            print(f"  Returned in week two: {returned}/{eligible} ({100 * returned / eligible:.1f}%)")
        else:
            print('  Returned in week two: not enough history yet')
        print(f"  First-report cohort: {retention['cohort_start']} through {retention['cohort_end']} UTC")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--db', type=Path, help='Read a local usage database instead of usage_url')
    parser.add_argument('--downloads', action='store_true', help='Also print GitHub release-file download totals')
    args = parser.parse_args()
    print_usage(args.db)
    if args.downloads:
        print()
        print_github()


if __name__ == '__main__':
    main()

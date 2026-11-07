#!/usr/bin/env python3
"""Prepare the public update feed from an already published, signed APK release.

Writes a local JSON file for review/commit; does not publish or download APKs.
Regular releases retain the previous feed's critical cutoff and alert message.
"""
import argparse
from datetime import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
REPOSITORY = 'MoaHabtamu/moagram'
PACKAGE = 'org.moagram.messenger.web'


def validate_scan_report(report, apk):
    """Check a maintainer's manual device test record, not a Google attestation."""
    if (not isinstance(report, dict) or report.get('schema_version') != 1
            or report.get('source') != 'manual-google-play-protect'
            or report.get('result') != 'no-threats-found'):
        raise ValueError('Record a completed Play Protect scan with no threats before preparing the feed')
    if (report.get('sha256') != hashlib.sha256(apk.read_bytes()).hexdigest()
            or report.get('size') != apk.stat().st_size):
        raise ValueError('The scan report must match this exact final APK; scan again after rebuilding or signing')
    for key in ('checked_at', 'device', 'android_version', 'observed_message'):
        if not isinstance(report.get(key), str) or not report[key].strip():
            raise ValueError(f'Missing manual Play Protect test detail: {key}')
    try:
        checked_at = datetime.fromisoformat(report['checked_at'])
        if checked_at.tzinfo is None:
            raise ValueError('Missing timezone')
    except ValueError as error:
        raise ValueError('Use an ISO 8601 scan time with a timezone') from error


def create_policy(apk, tag, release, badging, critical_below=None, message=None, notes=None,
                  previous_policy=None):
    if release.get('draft') or release.get('tag_name') != tag:
        raise ValueError('Choose an already published release with the requested tag')
    if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._-]*', tag):
        raise ValueError('Unsupported release tag')
    if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._-]*\.apk', apk.name):
        raise ValueError('Use a simple APK asset filename starting with a letter or number')
    package = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging, re.M)
    minimum = re.search(r"^sdkVersion:'(\d+)'", badging, re.M)
    if not package or package[1] != PACKAGE or not minimum or int(minimum[1]) < 23:
        raise ValueError('Expected a Moagram standalone APK for Android 6.0 or later')
    code = int(package[2])
    if not 0 < code <= 2100000000 or len(package[3]) > 80 or int(minimum[1]) > 100:
        raise ValueError('Unsupported APK version metadata')
    if critical_below is None:
        if previous_policy is None:
            critical_below = 0
        else:
            if (not isinstance(previous_policy, dict) or previous_policy.get('schema_version') != 1
                    or previous_policy.get('package_name') != PACKAGE
                    or 'critical_below_version_code' not in previous_policy):
                raise ValueError('Expected a previous Moagram update feed with a critical threshold')
            critical_below = previous_policy['critical_below_version_code']
            if message is None and critical_below:
                message = previous_policy.get('critical_message', '')
    message = '' if message is None else message
    if not isinstance(message, str):
        raise ValueError('Critical message must be text')
    if type(critical_below) is not int or not 0 <= critical_below <= code:
        raise ValueError('Critical threshold must be between zero and the APK version code')
    if critical_below and not message.strip():
        raise ValueError('Explain the critical update with --critical-message')
    if len(message) > 2000:
        raise ValueError('Critical message is too long')
    size = apk.stat().st_size
    if not 0 < size <= 256 * 1024 * 1024:
        raise ValueError('Unexpected APK size')
    digest = hashlib.sha256(apk.read_bytes()).hexdigest()
    assets = [a for a in release.get('assets', []) if a['name'] == apk.name]
    if len(assets) != 1:
        raise ValueError('The release must contain exactly one asset matching this APK filename')
    asset = assets[0]
    expected_url = f'https://github.com/{REPOSITORY}/releases/download/{tag}/{apk.name}'
    if asset.get('browser_download_url') != expected_url or asset.get('state') != 'uploaded' or asset.get('size') != size or asset.get('digest') != 'sha256:' + digest:
        raise ValueError('Published APK size, URL, or SHA-256 differs from the local file')
    notes = notes if notes is not None else release.get('name', tag)
    if len(notes) > 8000:
        raise ValueError('Update notes are too long')
    policy = dict(schema_version=1, package_name=PACKAGE, version_code=code,
                  version_name=package[3], min_sdk=int(minimum[1]), apk_url=expected_url,
                  sha256=digest, size=size, critical_below_version_code=critical_below,
                  notes=notes, critical_message=message)
    usage_url = previous_policy.get('usage_url') if isinstance(previous_policy, dict) else None
    if isinstance(usage_url, str) and usage_url.startswith('https://') and ' ' not in usage_url and len(usage_url) <= 200:
        policy['usage_url'] = usage_url
    return policy


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk', type=Path, required=True)
    parser.add_argument('--tag', required=True)
    parser.add_argument('--scan-report', type=Path, required=True,
                        help='Manual Play Protect device test record for the exact final APK; see Tools/updates/README.md')
    parser.add_argument('--previous-feed', type=Path, default=ROOT / 'updates/android.json',
                        help='Previous feed whose critical cutoff/message are retained by default')
    parser.add_argument('--critical-below', type=int,
                        help='Override the inherited critical cutoff; zero explicitly disables reminders')
    parser.add_argument('--critical-message', help='Override the inherited alert message; required for a new critical cutoff')
    parser.add_argument('--notes-file', type=Path)
    parser.add_argument('--aapt', type=Path)
    parser.add_argument('--output', type=Path, default=ROOT / 'updates/android.json')
    args = parser.parse_args()
    if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._-]*', args.tag):
        parser.error('Unsupported release tag')
    try:
        validate_scan_report(json.loads(args.scan_report.read_text()), args.apk)
    except (OSError, ValueError) as error:
        parser.error(str(error))
    previous = None
    if args.critical_below is None or args.previous_feed.exists():
        try:
            previous = json.loads(args.previous_feed.read_text())
            if not isinstance(previous, dict):
                raise ValueError('Expected a JSON object')
        except (OSError, ValueError) as error:
            if args.critical_below is None:
                parser.error(f'Cannot read previous feed: {error}. Use --previous-feed or explicitly set --critical-below.')
            previous = None
    sdk = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
    if not sdk and (ROOT / 'local.properties').exists():
        sdk = next((line.split('=', 1)[1].strip() for line in (ROOT / 'local.properties').read_text().splitlines() if line.startswith('sdk.dir=')), None)
    aapt = args.aapt or (Path(sdk) / 'build-tools/36.0.0/aapt' if sdk else None)
    if not aapt or not aapt.is_file():
        parser.error('Set ANDROID_HOME or pass --aapt')
    badging = subprocess.run([str(aapt), 'dump', 'badging', str(args.apk)], check=True, text=True, capture_output=True).stdout
    request = urllib.request.Request(f'https://api.github.com/repos/{REPOSITORY}/releases/tags/{args.tag}', headers={'Accept':'application/vnd.github+json', 'User-Agent':'Moagram-release-feed'})
    with urllib.request.urlopen(request, timeout=30) as response:
        release = json.load(response)
    policy = create_policy(args.apk, args.tag, release, badging, args.critical_below, args.critical_message,
                           args.notes_file.read_text() if args.notes_file else None, previous_policy=previous)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(policy, ensure_ascii=False, indent=2) + '\n')
    print(f'Prepared {args.output}. Review, commit, and push it to master to activate the feed.')
    threshold = policy['critical_below_version_code']
    print('Critical reminders:', 'off' if not threshold else f'installed version codes below {threshold}; downloads latest version {policy["version_code"]}; at most once per day')


if __name__ == '__main__':
    main()

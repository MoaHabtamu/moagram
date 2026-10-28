#!/usr/bin/env python3
"""Prepare the public update feed from an already published, signed APK release.

Writes a local JSON file for review/commit; does not publish or download APKs.
"""
import argparse
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


def create_policy(apk, tag, release, badging, critical_below=0, message='', notes=None):
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
    return dict(schema_version=1, package_name=PACKAGE, version_code=code,
                version_name=package[3], min_sdk=int(minimum[1]), apk_url=expected_url,
                sha256=digest, size=size, critical_below_version_code=critical_below,
                notes=notes, critical_message=message)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk', type=Path, required=True)
    parser.add_argument('--tag', required=True)
    parser.add_argument('--critical-below', type=int, default=0,
                        help='Remind only users below this Android version code; zero disables reminders')
    parser.add_argument('--critical-message', default='')
    parser.add_argument('--notes-file', type=Path)
    parser.add_argument('--aapt', type=Path)
    parser.add_argument('--output', type=Path, default=ROOT / 'updates/android.json')
    args = parser.parse_args()
    if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._-]*', args.tag):
        parser.error('Unsupported release tag')
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
                           args.notes_file.read_text() if args.notes_file else None)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(policy, ensure_ascii=False, indent=2) + '\n')
    print(f'Prepared {args.output}. Review, commit, and push it to master to activate the feed.')
    print('Critical reminders:', 'off' if not args.critical_below else f'installed version codes below {args.critical_below}; at most once per day')


if __name__ == '__main__':
    main()

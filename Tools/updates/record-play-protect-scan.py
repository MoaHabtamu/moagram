#!/usr/bin/env python3
"""Record the result of a Play Protect test you already performed on a device.

This tool does not run Google Play Protect or certify an APK. Scan the final
signed APK on a Play Protect-enabled device, then record the observed result.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk', type=Path, required=True)
    parser.add_argument('--device', required=True, help='Device model used for the actual test')
    parser.add_argument('--android-version', required=True)
    parser.add_argument('--result', required=True, choices=('no-threats-found', 'warning', 'blocked'))
    parser.add_argument('--observed-message', required=True, help='Exact scan result shown on the device')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    for value in (args.device, args.android_version, args.observed_message):
        if not value.strip():
            parser.error('Device, Android version, and observed message cannot be blank')
    if args.output.resolve() == args.apk.resolve():
        parser.error('The report must not overwrite the APK')
    if args.output.exists():
        parser.error('Choose a new report path to preserve existing test records')
    try:
        digest = hashlib.sha256(args.apk.read_bytes()).hexdigest()
        size = args.apk.stat().st_size
        if not size:
            parser.error('The tested APK is empty')
        report = dict(schema_version=1, source='manual-google-play-protect',
                      apk_filename=args.apk.name, sha256=digest, size=size,
                      checked_at=datetime.now(timezone.utc).isoformat(),
                      device=args.device.strip(), android_version=args.android_version.strip(),
                      result=args.result, observed_message=args.observed_message.strip())
        args.output.parent.mkdir(parents=True, exist_ok=True)
        with args.output.open('x') as output:
            output.write(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    except OSError as error:
        parser.error(str(error))
    print(f'Recorded manual device test: {args.output}')
    print('This is a maintainer test record, not Google certification or a guarantee against future scan prompts.')


if __name__ == '__main__':
    main()

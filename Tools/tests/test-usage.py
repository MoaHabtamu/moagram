#!/usr/bin/env python3
"""Counts for fresh installs, updates, and people who open Moagram."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import uuid

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'Tools' / 'stats'))
from report import classify
from usage_store import Store, UsageError, DAY, utc_day


def event(install_id, name, version=723769, version_name='13.1.1-Moagram.7'):
    return dict(install_id=str(install_id), event=name, version_code=version, version_name=version_name)


class UsageStoreTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.store = Store(Path(self.temp.name) / 'usage.sqlite')
        self.addCleanup(self.store.close)
        self.now = 1_800_000_000_000

    def test_fresh_install_is_not_an_update(self):
        self.store.record(event(uuid.uuid4(), 'install'), self.now)
        self.store.record(event(uuid.uuid4(), 'update'), self.now)
        summary = self.store.summary(self.now)
        self.assertEqual(summary['new_installs'], 1)
        self.assertEqual(summary['existing_installs'], 1)
        self.assertEqual(summary['update_events'], 1)
        self.assertEqual(summary['active_1d'], 2)

    def test_repeat_and_later_update_stay_one_new_install(self):
        install_id = uuid.uuid4()
        self.store.record(event(install_id, 'install'), self.now)
        self.store.record(event(install_id, 'install'), self.now + 1000)
        self.store.record(event(install_id, 'update', version=723779), self.now + 2000)
        self.store.record(event(install_id, 'active', version=723779), self.now + 3 * 24 * 60 * 60 * 1000)
        summary = self.store.summary(self.now + 3 * 24 * 60 * 60 * 1000)
        self.assertEqual(summary['new_installs'], 1)
        self.assertEqual(summary['existing_installs'], 0)
        self.assertEqual(summary['update_events'], 1)
        self.assertEqual(summary['active_1d'], 1)
        self.assertEqual(summary['active_7d'], 1)
        old = self.store.summary(self.now + 40 * 24 * 60 * 60 * 1000)
        self.assertEqual(old['active_30d'], 0)
        self.assertEqual(old['new_installs'], 1)

    def test_opening_before_an_install_event_is_not_a_new_download(self):
        self.store.record(event(uuid.uuid4(), 'active'), self.now)
        summary = self.store.summary(self.now)
        self.assertEqual(summary['new_installs'], 0)
        self.assertEqual(summary['existing_installs'], 1)
        self.assertEqual(summary['active_1d'], 1)

    def test_invalid_events_and_hourly_new_install_cap(self):
        with self.assertRaises(UsageError):
            self.store.record(dict(install_id='nope', event='install', version_code=1), self.now)
        with self.assertRaises(UsageError):
            self.store.record(event(uuid.uuid4(), 'download'), self.now)
        for _ in range(120):
            self.store.record(event(uuid.uuid4(), 'install'), self.now)
        with self.assertRaises(UsageError) as error:
            self.store.record(event(uuid.uuid4(), 'install'), self.now)
        self.assertEqual(error.exception.status, 429)
        self.assertEqual(self.store.summary(self.now)['new_installs'], 120)
        self.store.record(event(uuid.uuid4(), 'install'), self.now + 60 * 60 * 1000)
        self.assertEqual(self.store.summary(self.now + 60 * 60 * 1000)['new_installs'], 121)

    def test_release_files_are_separate_from_update_files(self):
        rows, release_downloads, update_downloads = classify([
            {'draft': True, 'tag_name': 'hidden', 'assets': [{'name': 'Hidden.apk', 'download_count': 9}]},
            {'tag_name': 'v1', 'assets': [
                {'name': 'Moagram.apk', 'download_count': 4},
                {'name': 'Moagram-update.apk', 'download_count': 7},
                {'name': 'SHA256SUMS.txt', 'download_count': 3},
            ]},
        ])
        self.assertEqual(release_downloads, 4)
        self.assertEqual(update_downloads, 7)
        self.assertEqual([row[2] for row in rows], [4, 7])

    def test_versions_follow_latest_report_and_exclude_inactive_installs(self):
        install_id = uuid.uuid4()
        self.store.record(event(install_id, 'install'), self.now - 40 * DAY)
        self.store.record(event(uuid.uuid4(), 'install'), self.now - 40 * DAY)
        self.store.record(event(install_id, 'update', 723779, 'Preview 8'), self.now)
        summary = self.store.summary(self.now)
        self.assertEqual(summary['active_30d'], 1)
        self.assertEqual([(v['version_name'], v['active_30d']) for v in summary['by_version']],
                         [('Preview 8', 1), ('13.1.1-Moagram.7', 0)])

    def test_week_two_uses_complete_cohorts_and_unique_installs(self):
        today = self.now // DAY * DAY
        first = today - 14 * DAY
        for offset in (6, 7, 13, 14):
            install_id = uuid.uuid4()
            self.store.record(event(install_id, 'install'), first)
            self.store.record(event(install_id, 'active'), first + offset * DAY)
            self.store.record(event(install_id, 'active'), first + offset * DAY + 1000)
        # This second week is still in progress and must not lower retention.
        self.store.record(event(uuid.uuid4(), 'install'), today - 13 * DAY)
        # The oldest included cohort and a cohort outside the 30-day window.
        self.store.record(event(uuid.uuid4(), 'install'), today - 43 * DAY)
        self.store.record(event(uuid.uuid4(), 'install'), today - 44 * DAY)
        self.assertEqual(self.store.summary(self.now)['week_two'], {
            'eligible': 5, 'returned': 2,
            'cohort_start': utc_day(today - 43 * DAY),
            'cohort_end': utc_day(today - 14 * DAY),
        })

    def test_missing_first_day_history_is_not_treated_as_failure_to_return(self):
        first = self.now - 20 * DAY
        install_id = uuid.uuid4()
        self.store.record(event(install_id, 'install'), first)
        self.store._db.execute('DELETE FROM daily')
        self.store.record(event(install_id, 'active'), first + 8 * DAY)
        self.assertEqual(self.store.summary(self.now)['week_two']['eligible'], 0)


class UsageDecisionTests(unittest.TestCase):
    def test_client_rules(self):
        sdk = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
        if not sdk and (ROOT / 'local.properties').exists():
            for line in (ROOT / 'local.properties').read_text().splitlines():
                if line.startswith('sdk.dir='):
                    sdk = line.split('=', 1)[1].strip()
        android = Path(sdk or '') / 'platforms/android-36/android.jar'
        classes = ROOT / 'TMessagesProj/build/intermediates/javac/standalone/compileStandaloneJavaWithJavac/classes'
        if not android.is_file() or not (classes / 'org/telegram/messenger/Utilities.class').is_file():
            self.fail('Compile the app before checking usage decisions')
        with tempfile.TemporaryDirectory() as temporary:
            subprocess.run([
                'javac', '-encoding', 'UTF-8', '-cp', f'{classes}:{android}', '-d', temporary,
                str(ROOT / 'TMessagesProj/src/main/java/org/telegram/messenger/MoagramUsage.java'),
                str(ROOT / 'Tools/tests/MoagramUsageTest.java'),
            ], check=True)
            completed = subprocess.run(
                ['java', '-cp', f'{temporary}:{classes}:{android}', 'MoagramUsageTest'],
                check=True, text=True, capture_output=True)
        self.assertIn('MoagramUsage: 24 checks passed', completed.stdout)

    def test_cloudflare_worker(self):
        subprocess.run(['node', '--test', str(ROOT / 'Tools/tests/test-usage-worker.mjs')], check=True)


if __name__ == '__main__':
    unittest.main()

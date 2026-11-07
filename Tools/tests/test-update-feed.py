#!/usr/bin/env python3
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import runpy
import tempfile
import unittest
from unittest.mock import patch

spec=importlib.util.spec_from_file_location('update_feed',Path(__file__).resolve().parents[1]/'updates/publish-update.py')
feed=importlib.util.module_from_spec(spec)
spec.loader.exec_module(feed)

class FeedTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.apk=Path(self.temp.name)/'Moagram.apk'
        self.apk.write_bytes(b'fixture for release asset matching')
        self.badging="package: name='org.moagram.messenger.web' versionCode='723719' versionName='13.1.1-Moagram.2'\nsdkVersion:'23'\n"
        self.asset=dict(name=self.apk.name,size=self.apk.stat().st_size,digest='sha256:'+hashlib.sha256(self.apk.read_bytes()).hexdigest(),state='uploaded',browser_download_url='https://github.com/MoaHabtamu/moagram/releases/download/v2/Moagram.apk')
        self.release=dict(tag_name='v2',name='Moagram update',draft=False,assets=[self.asset])
    def make(self,**kwargs): return feed.create_policy(self.apk,'v2',self.release,self.badging,**kwargs)
    def scan_report(self):
        return dict(schema_version=1,source='manual-google-play-protect',result='no-threats-found',
                    sha256=self.asset['digest'].removeprefix('sha256:'),size=self.apk.stat().st_size,
                    checked_at='2026-11-03T10:00:00+00:00',device='Test fixture device',
                    android_version='14',observed_message='Fixture: no threats found')
    def test_manual_scan_report_matches_exact_apk(self):
        feed.validate_scan_report(self.scan_report(),self.apk)
    def test_manual_scan_report_rejects_rebuilt_apk(self):
        report=self.scan_report()
        self.apk.write_bytes(b'a different final release APK')
        with self.assertRaises(ValueError): feed.validate_scan_report(report,self.apk)
    def test_manual_scan_report_rejects_incomplete_or_nonclear_results(self):
        for changes in ({'result':'warning'},{'result':'blocked'},{'result':'pending'},
                        {'source':'other-scanner'},{'sha256':'0'*64},{'size':1},
                        {'schema_version':2},{'device':''},{'android_version':''},
                        {'observed_message':''},{'checked_at':'invalid'},
                        {'checked_at':'2026-11-03T10:00:00'}):
            with self.subTest(changes=changes),self.assertRaises(ValueError):
                feed.validate_scan_report({**self.scan_report(),**changes},self.apk)
        for invalid in (None,[],{},'scan passed'):
            with self.subTest(invalid=invalid),self.assertRaises(ValueError):
                feed.validate_scan_report(invalid,self.apk)
    def record_scan(self, output, *extra):
        argv=['record-play-protect-scan.py','--apk',str(self.apk),'--device','Test fixture device',
              '--android-version','14','--result','no-threats-found',
              '--observed-message','Fixture: no threats found','--output',str(output),*extra]
        with patch('sys.argv',argv),patch('sys.stdout',new_callable=io.StringIO), \
                patch('sys.stderr',new_callable=io.StringIO):
            runpy.run_path(str(Path(feed.__file__).with_name('record-play-protect-scan.py')),run_name='__main__')
    def test_recorder_produces_a_matching_manual_test_record(self):
        output=Path(self.temp.name)/'scan.json'
        self.record_scan(output)
        report=json.loads(output.read_text())
        feed.validate_scan_report(report,self.apk)
        self.assertEqual(report['source'],'manual-google-play-protect')
    def test_recorder_records_warning_but_feed_rejects_it(self):
        output=Path(self.temp.name)/'warning.json'
        self.record_scan(output,'--result','warning','--observed-message','Fixture: warning')
        with self.assertRaises(ValueError): feed.validate_scan_report(json.loads(output.read_text()),self.apk)
    def test_recorder_cannot_overwrite_apk_or_prior_test_record(self):
        original=self.apk.read_bytes()
        with self.assertRaises(SystemExit): self.record_scan(self.apk)
        self.assertEqual(self.apk.read_bytes(),original)
        output=Path(self.temp.name)/'scan.json'
        self.record_scan(output)
        saved=output.read_text()
        with self.assertRaises(SystemExit): self.record_scan(output)
        self.assertEqual(output.read_text(),saved)
    def test_first_regular_release_has_no_critical_cutoff(self):
        policy=self.make()
        self.assertEqual(policy['critical_below_version_code'],0)
        self.assertEqual(policy['version_code'],723719)
        self.assertEqual(policy['apk_url'],self.asset['browser_download_url'])
    def previous(self):
        return dict(schema_version=1, package_name=feed.PACKAGE, version_code=723709,
                    critical_below_version_code=723709, critical_message='Includes an essential fix')
    def test_usage_address_is_kept_with_the_next_feed(self):
        previous={**self.previous(), 'usage_url':'https://stats.example.com/v1/event'}
        policy=self.make(previous_policy=previous)
        self.assertEqual(policy['usage_url'], 'https://stats.example.com/v1/event')
        self.assertNotIn('usage_url', self.make(previous_policy=self.previous()))
        self.assertNotIn('usage_url', self.make(previous_policy={**self.previous(), 'usage_url':'http://stats.example.com/v1/event'}))
    def test_regular_release_preserves_earlier_critical_cutoff(self):
        previous=self.previous()
        policy=self.make(previous_policy=previous)
        self.assertEqual(policy['critical_below_version_code'],723709)
        self.assertEqual(policy['critical_message'],previous['critical_message'])
        self.assertEqual(policy['version_code'],723719)
        self.assertEqual(policy['apk_url'],self.asset['browser_download_url'])
        self.assertEqual(previous,self.previous(), 'Previous feed was modified')
        self.badging=self.badging.replace('723719','723729')
        following=self.make(previous_policy=policy)
        self.assertEqual(following['critical_below_version_code'],723709)
        self.assertEqual(following['version_code'],723729)
    def test_explicit_zero_clears_inherited_requirement(self):
        policy=self.make(previous_policy=self.previous(),critical_below=0)
        self.assertEqual(policy['critical_below_version_code'],0)
        self.assertEqual(policy['critical_message'],'')
    def test_new_critical_release_replaces_cutoff_and_requires_fresh_message(self):
        with self.assertRaises(ValueError):
            self.make(previous_policy=self.previous(),critical_below=723719)
        policy=self.make(previous_policy=self.previous(),critical_below=723719,message='Another essential fix')
        self.assertEqual(policy['critical_below_version_code'],723719)
        self.assertEqual(policy['critical_message'],'Another essential fix')
    def test_inherited_message_can_be_updated(self):
        policy=self.make(previous_policy=self.previous(),message='Install the latest version for this fix')
        self.assertEqual(policy['critical_below_version_code'],723709)
        self.assertEqual(policy['critical_message'],'Install the latest version for this fix')
    def test_invalid_previous_feed_cannot_silently_clear_cutoff(self):
        for changes in ({'schema_version':2},{'package_name':'another.app'},
                        {'critical_below_version_code':None},{'critical_below_version_code':True},
                        {'critical_below_version_code':723720},{'critical_message':''},
                        {'critical_message':False}):
            with self.subTest(changes=changes),self.assertRaises(ValueError):
                self.make(previous_policy={**self.previous(),**changes})
        previous=self.previous()
        del previous['critical_below_version_code']
        with self.assertRaises(ValueError): self.make(previous_policy=previous)
    def run_cli(self, previous_text, *extra):
        previous=Path(self.temp.name)/'previous.json'
        output=Path(self.temp.name)/'next.json'
        if previous_text is not None: previous.write_text(previous_text)
        aapt=Path(self.temp.name)/'aapt'
        aapt.touch()
        scan=Path(self.temp.name)/'scan.json'
        scan.write_text(json.dumps(self.scan_report()))
        argv=['publish-update.py','--apk',str(self.apk),'--tag','v2','--aapt',str(aapt),
              '--previous-feed',str(previous),'--scan-report',str(scan),'--output',str(output),*extra]
        with patch('sys.argv',argv),patch.object(feed.subprocess,'run') as run, \
                patch.object(feed.urllib.request,'urlopen') as fetch, \
                patch('sys.stdout',new_callable=io.StringIO),patch('sys.stderr',new_callable=io.StringIO):
            run.return_value.stdout=self.badging
            fetch.return_value.__enter__.return_value=io.StringIO(json.dumps(self.release))
            feed.main()
        return json.loads(output.read_text())
    def test_cli_inherits_cutoff_without_any_critical_flags(self):
        policy=self.run_cli(json.dumps(self.previous()))
        self.assertEqual(policy['critical_below_version_code'],723709)
        self.assertEqual(policy['critical_message'],self.previous()['critical_message'])
        self.assertEqual(policy['apk_url'],self.asset['browser_download_url'])
    def test_cli_cannot_silently_ignore_missing_or_broken_previous_feed(self):
        for text in (None,'invalid JSON','null'):
            with self.subTest(text=text),self.assertRaises(SystemExit) as error:
                self.run_cli(text)
            self.assertEqual(error.exception.code,2)
    def test_cli_can_explicitly_disable_reminders_without_previous_feed(self):
        policy=self.run_cli(None,'--critical-below','0')
        self.assertEqual(policy['critical_below_version_code'],0)
    def test_cli_rejects_missing_or_failed_scan_before_writing_feed(self):
        report=Path(self.temp.name)/'failed-scan.json'
        output=Path(self.temp.name)/'next.json'
        for record in (None,{**self.scan_report(),'result':'blocked'},
                       {**self.scan_report(),'sha256':'0'*64}):
            if record is not None: report.write_text(json.dumps(record))
            with self.subTest(record=record),self.assertRaises(SystemExit) as error:
                self.run_cli(None,'--critical-below','0','--scan-report',str(report))
            self.assertEqual(error.exception.code,2)
            self.assertFalse(output.exists())
    def test_critical_release_targets_only_older_clients(self):
        policy=self.make(critical_below=723719,message='Fixes an important issue')
        self.assertEqual(policy['critical_below_version_code'],723719)
    def test_requires_message_for_critical_notice(self):
        with self.assertRaises(ValueError): self.make(critical_below=723719)
    def test_bad_thresholds_are_rejected(self):
        for value in (-1,723720,True,1.5):
            with self.subTest(value=value),self.assertRaises(ValueError): self.make(critical_below=value,message='Reason')
    def test_draft_release_is_not_advertised(self):
        self.release['draft']=True
        with self.assertRaises(ValueError): self.make()
    def test_wrong_published_asset_is_rejected(self):
        for key,value in (('digest','sha256:'+'0'*64),('size',1),('state','uploading'),('browser_download_url','https://evil.test/Moagram.apk')):
            with self.subTest(key=key):
                original=self.asset[key]
                self.asset[key]=value
                with self.assertRaises(ValueError): self.make()
                self.asset[key]=original
    def test_wrong_package_or_android_version_is_rejected(self):
        for text in (self.badging.replace('org.moagram.messenger.web','org.telegram.messenger'),self.badging.replace("sdkVersion:'23'","sdkVersion:'21'"),'not an APK'):
            with self.subTest(text=text),self.assertRaises(ValueError): feed.create_policy(self.apk,'v2',self.release,text)
    def test_release_tag_and_asset_must_match(self):
        self.release['tag_name']='other'
        with self.assertRaises(ValueError): self.make()
        self.release['tag_name']='v2'
        self.release['assets']=[]
        with self.assertRaises(ValueError): self.make()
    def test_notes_and_message_bounds(self):
        with self.assertRaises(ValueError): self.make(notes='x'*8001)
        with self.assertRaises(ValueError): self.make(message='x'*2001)

if __name__=='__main__': unittest.main()

#!/usr/bin/env python3
import hashlib
import importlib.util
from pathlib import Path
import tempfile
import unittest

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
    def test_normal_release_never_nags(self):
        policy=self.make()
        self.assertEqual(policy['critical_below_version_code'],0)
        self.assertEqual(policy['version_code'],723719)
        self.assertEqual(policy['apk_url'],self.asset['browser_download_url'])
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

#!/usr/bin/env python3
"""Exercise production policy/signature verification with real, ephemeral signed APKs."""
from pathlib import Path
import os
import subprocess
import tempfile
import zipfile

root = Path(__file__).resolve().parents[2]
sdk = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
if not sdk:
    sdk = next(line.split('=', 1)[1].strip() for line in (root/'local.properties').read_text().splitlines() if line.startswith('sdk.dir='))
sdk = Path(sdk)
tools = sdk/'build-tools/36.0.0'
jar = next((Path(os.environ.get('GRADLE_USER_HOME', str(Path.home()/'.gradle')))/'caches/modules-2/files-2.1/com.android.tools.build/apksig/8.13.2').glob('*/*.jar'))

def run(*args):
    subprocess.run(list(map(str,args)),check=True,stdout=subprocess.PIPE,stderr=subprocess.PIPE)

with tempfile.TemporaryDirectory() as temporary:
    temp = Path(temporary)
    manifest = temp/'AndroidManifest.xml'
    manifest.write_text('''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="org.moagram.messenger.web" android:versionCode="2" android:versionName="2"><uses-sdk android:minSdkVersion="23" android:targetSdkVersion="36"/><application android:label="Update test"/></manifest>''')
    run(tools/'aapt','package','-f','-M',manifest,'-I',sdk/'platforms/android-36/android.jar','-F',temp/'unsigned.apk')
    run(tools/'apksigner','sign','--ks',root/'TMessagesProj/config/release.keystore','--ks-key-alias','androidkey','--ks-pass','pass:android','--key-pass','pass:android','--out',temp/'good.apk',temp/'unsigned.apk')
    run('keytool','-genkeypair','-keystore',temp/'other.jks','-storepass','testpass','-keypass','testpass','-alias','other','-keyalg','RSA','-keysize','2048','-validity','30','-dname','CN=Ephemeral update test')
    run(tools/'apksigner','sign','--ks',temp/'other.jks','--ks-pass','pass:testpass','--key-pass','pass:testpass','--out',temp/'other.apk',temp/'unsigned.apk')
    (temp/'tampered.apk').write_bytes((temp/'good.apk').read_bytes())
    with zipfile.ZipFile(temp/'tampered.apk','a') as apk: apk.writestr('assets/tampered.txt','must be rejected even if metadata hash matches')
    sources = root/'TMessagesProj/src/main/java/org/telegram/messenger'
    run('javac','-cp',jar,'-d',temp,sources/'MoagramUpdatePolicy.java',sources/'MoagramUpdateSecurity.java',root/'Tools/tests/MoagramUpdateTest.java')
    subprocess.run(['java','-cp',str(temp)+os.pathsep+str(jar),'MoagramUpdateTest',str(temp/'good.apk'),str(temp/'other.apk'),str(temp/'tampered.apk')],check=True)

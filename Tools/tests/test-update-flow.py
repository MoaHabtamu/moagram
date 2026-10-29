#!/usr/bin/env python3
"""Run the production update controller against deterministic Android/network doubles.

APK authenticity is covered separately by test-moagram-updates.py. This harness
exercises download, prompt, permission, and process-recreation behavior without a device.
"""
from pathlib import Path
import os
import re
import subprocess
import tempfile

root = Path(__file__).resolve().parents[2]
gradle = Path(os.environ.get('GRADLE_USER_HOME', str(Path.home() / '.gradle')))
gson = next(gradle.glob('caches/modules-2/files-2.1/com.google.code.gson/gson/*/*/gson-*.jar'))
controller = root / 'TMessagesProj/src/main/java/org/telegram/messenger/MoagramUpdateController.java'
stubs = {
    'android/content/SharedPreferences': '''public class SharedPreferences {
        private final java.util.Map<String,Object> values = new java.util.HashMap<>();
        public long getLong(String k,long d) { return (long)values.getOrDefault(k,d); }
        public boolean getBoolean(String k,boolean d) { return (boolean)values.getOrDefault(k,d); }
        public String getString(String k,String d) { return (String)values.getOrDefault(k,d); }
        public Editor edit() { return new Editor(); }
        public class Editor {
            public Editor putLong(String k,long v) { values.put(k,v); return this; }
            public Editor putBoolean(String k,boolean v) { values.put(k,v); return this; }
            public Editor putString(String k,String v) { values.put(k,v); return this; }
            public Editor remove(String k) { values.remove(k); return this; }
            public void apply() {}
        }
    }''',
    'android/content/Context': '''public class Context {
        public static final int MODE_PRIVATE=0;
        public final SharedPreferences prefs = new SharedPreferences();
        public final android.content.pm.PackageManager manager = new android.content.pm.PackageManager();
        public java.io.File cache;
        public String getPackageName() { return "org.moagram.messenger.web"; }
        public SharedPreferences getSharedPreferences(String n,int m) { return prefs; }
        public android.content.pm.PackageManager getPackageManager() { return manager; }
        public java.io.File getCacheDir() { return cache; }
    }''',
    'android/content/Intent': '''public class Intent {
        public static final String ACTION_VIEW="install";
        public static final int FLAG_GRANT_READ_URI_PERMISSION=1;
        public final String action;
        public Intent(String a) { action=a; }
        public Intent(String a,android.net.Uri u) { action=a; }
        public Intent setDataAndType(android.net.Uri u,String t) { return this; }
        public Intent addFlags(int f) { return this; }
    }''',
    'android/app/Activity': '''public class Activity extends android.content.Context {
        public final java.util.List<String> launched = new java.util.ArrayList<>();
        public boolean finishing, destroyed;
        public boolean isFinishing() { return finishing; }
        public boolean isDestroyed() { return destroyed; }
        public void startActivity(android.content.Intent i) { launched.add(i.action); }
    }''',
    'android/content/pm/PackageInfo': '''public class PackageInfo {
        public int versionCode;
        public String versionName="test", packageName="org.moagram.messenger.web";
        public ApplicationInfo applicationInfo = new ApplicationInfo();
        public Signature[] signatures = {new Signature()};
        public SigningInfo signingInfo = new SigningInfo();
        public long getLongVersionCode() { return versionCode; }
    }''',
    'android/content/pm/ApplicationInfo': 'public class ApplicationInfo { public int minSdkVersion=23; }',
    'android/content/pm/Signature': 'public class Signature { public byte[] toByteArray() { return new byte[]{1}; } }',
    'android/content/pm/SigningInfo': '''public class SigningInfo {
        public Signature[] getApkContentsSigners() { return new Signature[]{new Signature()}; }
    }''',
    'android/content/pm/PackageManager': '''public class PackageManager {
        public static final int GET_SIGNING_CERTIFICATES=1, GET_SIGNATURES=2;
        public boolean allowed;
        public int installed=1;
        public PackageInfo getPackageInfo(String p,int f) { PackageInfo i=new PackageInfo(); i.versionCode=installed; return i; }
        public PackageInfo getPackageArchiveInfo(String p,int f) { PackageInfo i=new PackageInfo(); i.versionCode=2; return i; }
        public boolean canRequestPackageInstalls() { return allowed; }
    }''',
    'android/net/Uri': 'public class Uri { public static Uri parse(String s) { return new Uri(); } }',
    'android/os/Build': 'public class Build { public static class VERSION { public static int SDK_INT=36; } }',
    'android/provider/Settings': 'public class Settings { public static final String ACTION_MANAGE_UNKNOWN_APP_SOURCES="permission"; }',
    'androidx/core/content/FileProvider': '''public class FileProvider {
        public static android.net.Uri getUriForFile(android.content.Context c,String a,java.io.File f) { return new android.net.Uri(); }
    }''',
    'org/telegram/messenger/ApplicationLoader': '''public class ApplicationLoader {
        public static android.content.Context applicationContext;
        public static boolean mainInterfacePaused;
    }''',
    'org/telegram/messenger/SharedConfig': 'public class SharedConfig { public static boolean appLocked; }',
    'org/telegram/messenger/BuildVars': 'public class BuildVars { public static String BUILD_VERSION_STRING="test"; }',
    'org/telegram/messenger/AndroidUtilities': '''public class AndroidUtilities {
        public static final java.util.concurrent.BlockingQueue<Runnable> ui = new java.util.concurrent.LinkedBlockingQueue<>();
        public static void runOnUIThread(Runnable r) { ui.add(r); }
    }''',
    'org/telegram/messenger/Utilities': '''public class Utilities {
        public static final Queue globalQueue=new Queue();
        public static class Queue { public void postRunnable(Runnable r) { r.run(); } }
    }''',
    'org/telegram/messenger/LocaleController': 'public class LocaleController { public static String getString(int id) { return "string:"+id; } }',
    'org/telegram/messenger/MoagramUpdateSecurity': '''public class MoagramUpdateSecurity {
        public static boolean reject;
        public static int verifications;
        public static java.util.concurrent.CountDownLatch gate;
        public static void verify(java.io.File f,long size,String h,java.util.List<byte[]> c,int sdk) throws Exception {
            verifications++;
            if (gate != null) gate.await();
            if (reject || !f.isFile() || f.length()!=size) throw new SecurityException();
        }
    }''',
    'org/telegram/ui/ActionBar/BaseFragment': '''public class BaseFragment {
        public Object getResourceProvider() { return null; }
        public void presentFragment(BaseFragment f) { throw new AssertionError("Download navigated to manual page"); }
    }''',
    'org/telegram/ui/MoagramUpdatesActivity': 'public class MoagramUpdatesActivity extends org.telegram.ui.ActionBar.BaseFragment {}',
    'org/telegram/ui/LaunchActivity': '''public class LaunchActivity extends android.app.Activity {
        public static org.telegram.ui.ActionBar.BaseFragment fragment=new org.telegram.ui.ActionBar.BaseFragment();
        public static org.telegram.ui.ActionBar.BaseFragment getLastFragment() { return fragment; }
    }''',
    'org/telegram/ui/ActionBar/AlertDialog': '''public class AlertDialog {
        public static AlertDialog shown;
        public String positive, message;
        android.app.Activity owner;
        Click click;
        Dismiss dismiss;
        public interface Click { void run(AlertDialog d,int w); }
        public interface Dismiss { void run(AlertDialog d); }
        public void show() { shown=this; }
        public void dismiss() { if(dismiss!=null) dismiss.run(this); if(shown==this) shown=null; }
        public void accept() { click.run(this,1); dismiss(); }
        public void setOwnerActivity(android.app.Activity a) { owner=a; }
        public android.app.Activity getOwnerActivity() { return owner; }
        public static class Builder {
            AlertDialog dialog=new AlertDialog();
            public Builder(android.app.Activity a) {}
            public Builder(android.app.Activity a,Object p) {}
            public void setTitle(String s) {}
            public void setMessage(String s) { dialog.message=s; }
            public void setPositiveButton(String s,Click c) { dialog.positive=s; dialog.click=c; }
            public void setNegativeButton(String s,Click c) {}
            public void setOnDismissListener(Dismiss d) { dialog.dismiss=d; }
            public AlertDialog create() { return dialog; }
        }
    }''',
}
ids = sorted(set(re.findall(r'R\.string\.(\w+)', controller.read_text())))
stubs['org/telegram/messenger/R'] = 'public class R { public static class string {' + ''.join(
    f'public static final int {name}={i};' for i, name in enumerate(ids)) + '} }'

with tempfile.TemporaryDirectory(prefix='moagram-update-flow-') as temporary:
    temp = Path(temporary)
    sources = []
    for name, body in stubs.items():
        path = temp / (name + '.java')
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text('package ' + name.rsplit('/', 1)[0].replace('/', '.') + ';\n' + body)
        sources.append(path)
    sources.extend([controller,
                    root / 'TMessagesProj/src/main/java/org/telegram/messenger/MoagramUpdatePolicy.java',
                    root / 'Tools/tests/jvm/org/json/JSONObject.java',
                    root / 'Tools/tests/jvm/org/json/JSONArray.java',
                    root / 'Tools/tests/MoagramUpdateFlowTest.java'])
    subprocess.run(['javac', '-cp', str(gson), '-d', str(temp), *map(str, sources)], check=True)
    subprocess.run(['java', '-cp', str(temp) + os.pathsep + str(gson), 'MoagramUpdateFlowTest', str(temp / 'cache')], check=True, timeout=30)

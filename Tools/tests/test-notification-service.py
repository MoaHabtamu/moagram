#!/usr/bin/env python3
"""Run production notification-service lifecycles with recording Android doubles.

No Telegram account or Android device is needed. The service and keep-alive job
are compiled unchanged apart from imports/nesting; startup and per-account
preference methods are extracted from production, not reimplemented here.
"""
from pathlib import Path
import re
import subprocess
import tempfile
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[2]
src = root / 'TMessagesProj/src/main/java/org/telegram'


def source(path):
    return (src / path).read_text()


def block(text, marker):
    start = text.index(marker)
    opening = text.index('{', start)
    end, depth = opening + 1, 1
    while depth:
        depth += (text[end] == '{') - (text[end] == '}')
        end += 1
    return text[start:end]


def nested(path):
    text = re.sub(r'^(package|import) .*;\s*', '', source(path), flags=re.M)
    return text.replace('public class ', 'static class ', 1)


manifest = ET.parse(root / 'TMessagesProj/src/main/AndroidManifest.xml').getroot()
android = '{http://schemas.android.com/apk/res/android}'
permissions = {p.get(android + 'name') for p in manifest.findall('uses-permission')}
assert 'android.permission.FOREGROUND_SERVICE_SPECIAL_USE' in permissions
service = next(s for s in manifest.findall('application/service')
               if s.get(android + 'name') == '.NotificationsService')
assert set(service.get(android + 'foregroundServiceType').split('|')) == {'dataSync', 'specialUse'}
assert service.get(android + 'exported') == 'false'
assert service.get(android + 'stopWithTask') == 'false'
assert any(p.get(android + 'name') == 'android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE'
           and p.get(android + 'value') for p in service.findall('property'))

java = r'''
import java.util.*;
import java.util.concurrent.CountDownLatch;
public class NotificationServiceTest {
    static int checks, init, logs, jobs, starts, plainStarts, stops, broadcasts;
    static boolean rejectStart, rejectPromotion, rejectJob;
    static List<String> events = new ArrayList<>();
    static Map<Runnable,Long> delayed = new HashMap<>();
    static AlarmManager alarm = new AlarmManager();
    static NotificationManager manager = new NotificationManager();
    static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
    static class Build {
        static class VERSION { static int SDK_INT; }
        static class VERSION_CODES { static final int M=23, O=26, Q=29; }
    }
    static class ServiceInfo {
        static final int FOREGROUND_SERVICE_TYPE_DATA_SYNC=1, FOREGROUND_SERVICE_TYPE_SPECIAL_USE=1<<30;
    }
    interface IBinder {}
    static class SystemClock { static long elapsedRealtime() { return 1000; } }
    static class SharedPreferences {
        Map<String,Boolean> values = new HashMap<>();
        boolean contains(String key) { return values.containsKey(key); }
        boolean getBoolean(String key, boolean fallback) { return values.getOrDefault(key, fallback); }
    }
    static class Context {
        static final String ALARM_SERVICE="alarm", NOTIFICATION_SERVICE="notification";
        Object getSystemService(String key) { return ALARM_SERVICE.equals(key) ? alarm : manager; }
        String getString(int id) { return "resource-" + id; }
        String getPackageName() { return "org.moagram.messenger.web"; }
        void startForegroundService(Intent i) {
            starts++; events.add("request");
            if (rejectStart) throw new IllegalStateException("FGS blocked");
        }
        void startService(Intent i) { plainStarts++; }
        void stopService(Intent i) { stops++; }
        void sendBroadcast(Intent i) { broadcasts++; }
    }
    static class Service extends Context {
        static final int START_STICKY=1, START_NOT_STICKY=2;
        int promoted, type, stopped, removed;
        Notification notification;
        public void onCreate() {}
        public int onStartCommand(Intent i,int f,int id) { return 0; }
        public IBinder onBind(Intent i) { return null; }
        public void onTaskRemoved(Intent i) {}
        public void onTimeout(int id) {}
        public void onTimeout(int id,int type) {}
        public void onDestroy() {}
        void stopSelf() { stopped++; }
        void stopForeground(boolean remove) { if (remove) removed++; }
        void startForeground(int id,Notification n) { startForeground(id,n,0); }
        void startForeground(int id,Notification n,int t) {
            if (rejectPromotion) throw new SecurityException("promotion blocked");
            promoted++; notification=n; type=t; events.add("promote");
        }
    }
    static class Intent {
        static final int FLAG_ACTIVITY_NEW_TASK=1, FLAG_ACTIVITY_CLEAR_TOP=2;
        static final String ACTION_BOOT_COMPLETED="boot", ACTION_MY_PACKAGE_REPLACED="replaced";
        Class<?> target; String action, pkg;
        Intent() {}
        Intent(Context context,Class<?> value) { target=value; }
        void addFlags(int flags) {}
        void setAction(String value) { action=value; }
        void setPackage(String value) { pkg=value; }
        String getAction() { return action; }
    }
    static class PendingIntent {
        static final int FLAG_UPDATE_CURRENT=1, FLAG_IMMUTABLE=2;
        Intent intent; int flags;
        static PendingIntent getBroadcast(Context c,int id,Intent i,int f) {
            PendingIntent p=new PendingIntent(); p.intent=i; p.flags=f; return p;
        }
        static PendingIntent getActivity(Context c,int id,Intent i,int f) { return getBroadcast(c,id,i,f); }
    }
    static class AlarmManager {
        static final int ELAPSED_REALTIME_WAKEUP=2;
        int scheduled, cancelled; long at; PendingIntent pending;
        void setAndAllowWhileIdle(int type,long time,PendingIntent p) { scheduled++; at=time; pending=p; }
        void set(int type,long time,PendingIntent p) { setAndAllowWhileIdle(type,time,p); }
        void cancel(PendingIntent p) { cancelled++; }
    }
    static class Notification { boolean silent, ongoing; int priority; PendingIntent content; }
    static class NotificationChannel {
        int importance; boolean vibration, badge=true; Object sound=new Object();
        NotificationChannel(String id,String name,int value) { importance=value; }
        void setSound(Object value,Object attrs) { sound=value; }
        void enableVibration(boolean value) { vibration=value; }
        void setShowBadge(boolean value) { badge=value; }
    }
    static class NotificationManager {
        static final int IMPORTANCE_LOW=2;
        NotificationChannel channel;
        void createNotificationChannel(NotificationChannel value) { channel=value; }
    }
    static class NotificationCompat {
        static final int PRIORITY_LOW=-1;
        static final String CATEGORY_SERVICE="service";
        static class Builder {
            Notification n=new Notification();
            Builder(Context c,String channel) {}
            Builder setSmallIcon(int i) { return this; }
            Builder setContentTitle(String s) { return this; }
            Builder setContentText(String s) { return this; }
            Builder setContentIntent(PendingIntent p) { n.content=p; return this; }
            Builder setPriority(int value) { n.priority=value; return this; }
            Builder setCategory(String value) { return this; }
            Builder setOngoing(boolean value) { n.ongoing=value; return this; }
            Builder setSilent(boolean value) { n.silent=value; return this; }
            Builder setShowWhen(boolean value) { return this; }
            Notification build() { return n; }
        }
    }
    static class R {
        static class string { static int NotificationsService=1, MoagramBackgroundConnectionActive=2; }
        static class drawable { static int notification=1; }
    }
    static class LaunchActivity {}
    static class BroadcastReceiver { public void onReceive(Context context,Intent intent) {} }
    static class SharedConfig {
        static String passcodeHash=""; static boolean appLocked;
        static void loadConfig() {} static void saveConfig() {}
    }
    static class LocalRepeatRefill { static int schedules; static void schedule() { schedules++; } }
    static class AndroidUtilities {
        static void runOnUIThread(Runnable r) { r.run(); }
        static void runOnUIThread(Runnable r,long delay) { delayed.put(r,delay); }
        static void cancelRunOnUIThread(Runnable r) { delayed.remove(r); }
    }
    static class FileLog { static void e(Exception e) { logs++; } static void d(String s) {} }
    static class BuildVars { static final boolean LOGS_ENABLED=false; }
    static class Queue {
        void postRunnable(Runnable r) { r.run(); }
        void postRunnable(Runnable r,long delay) { delayed.put(r,delay); }
        void cancelRunnable(Runnable r) { delayed.remove(r); }
    }
    static class Utilities { static Queue globalQueue=new Queue(); }
    static class JobIntentService extends Service {
        static void enqueueWork(Context context,Class<?> cls,int id,Intent intent) {
            jobs++; if (rejectJob) throw new IllegalStateException("enqueue failed");
        }
        protected void onHandleWork(Intent i) {}
    }
    static class MessagesController {
        static SharedPreferences global=new SharedPreferences();
        static SharedPreferences[] accounts={new SharedPreferences(),new SharedPreferences(),new SharedPreferences()};
        static SharedPreferences getGlobalNotificationsSettings() { return global; }
        static SharedPreferences getMainSettings(int account) { return accounts[account]; }
    }
    static class UserConfig {
        static final int MAX_ACCOUNT_COUNT=3;
        static int selectedAccount;
        static UserConfig[] accounts={new UserConfig(),new UserConfig(),new UserConfig()};
        boolean active;
        static UserConfig getInstance(int account) { return accounts[account]; }
        boolean isClientActivated() { return active; }
    }
    static class ConnectionsManager {
        static ConnectionsManager[] accounts={new ConnectionsManager(0),new ConnectionsManager(1),new ConnectionsManager(2)};
        int currentAccount, checks, resumes; boolean enabled;
        ConnectionsManager(int account) { currentAccount=account; }
        static ConnectionsManager getInstance(int account) { return accounts[account]; }
        void checkConnection() { checks++; }
        void resumeNetworkMaybe() { resumes++; }
        void setPushConnectionEnabled(boolean value) { enabled=value; }
        @PUSH_PREFERENCE@
    }
    static class ApplicationLoader {
        static Context applicationContext=new Context();
        static void postInitApplication() { init++; events.add("init"); }
        @START_SERVICE@
    }
    @NOTIFICATION_SERVICE@
    @KEEP_ALIVE_JOB@
    @START_RECEIVER@
    static void reset() {
        init=logs=jobs=starts=plainStarts=stops=broadcasts=0;
        rejectStart=rejectPromotion=rejectJob=false;
        events.clear(); delayed.clear(); alarm=new AlarmManager(); manager=new NotificationManager();
        SharedConfig.passcodeHash=""; SharedConfig.appLocked=false; LocalRepeatRefill.schedules=0;
        MessagesController.global.values.clear();
        UserConfig.selectedAccount=0;
        for (int a=0;a<3;a++) {
            MessagesController.accounts[a].values.clear(); UserConfig.accounts[a].active=a<2;
            ConnectionsManager.accounts[a]=new ConnectionsManager(a);
        }
        KeepAliveJob.finishJob();
    }
    public static void main(String[] args) {
        for (int sdk : new int[]{23,26,29,33,34,35,36}) {
            reset(); Build.VERSION.SDK_INT=sdk;
            ApplicationLoader.startPushService();
            check(sdk>=26 ? starts==1 && plainStarts==0 : starts==0 && plainStarts==1,"correct start API "+sdk);
            NotificationsService service=new NotificationsService(); service.onCreate();
            check(service.promoted==1 && init==0,"promote before account init "+sdk);
            check(service.type==(sdk>=34 ? 1<<30 : sdk>=29 ? 1 : 0),"FGS type "+sdk);
            check(service.notification.silent && service.notification.ongoing,"silent ongoing notice "+sdk);
            check(service.notification.priority==NotificationCompat.PRIORITY_LOW,"visible priority "+sdk);
            check(service.notification.content.intent.target==LaunchActivity.class,"tap opens app "+sdk);
            check((service.notification.content.flags & PendingIntent.FLAG_IMMUTABLE)!=0,"immutable intent "+sdk);
            if(sdk>=26) check(manager.channel.sound==null && !manager.channel.vibration && !manager.channel.badge,"quiet channel "+sdk);
            check(service.onStartCommand(null,0,1)==Service.START_STICKY,"sticky null-intent restart "+sdk);
            check(ConnectionsManager.accounts[0].resumes==1 && ConnectionsManager.accounts[1].resumes==1
                && ConnectionsManager.accounts[2].resumes==0,"active accounts only "+sdk);
            check(delayed.size()==1 && alarm.scheduled==0,"one heartbeat without recurring wakeup "+sdk);
            service.onStartCommand(new Intent(),0,2);
            check(delayed.size()==1,"repeated start does not duplicate heartbeat "+sdk);
            Runnable heartbeat=delayed.keySet().iterator().next(); delayed.clear(); heartbeat.run();
            check(ConnectionsManager.accounts[0].resumes==3 && delayed.size()==1,"heartbeat reconnects "+sdk);
            service.onTaskRemoved(new Intent());
            check(service.stopped==0 && broadcasts==0,"task removal leaves FGS running "+sdk);
            check(alarm.at==181000 && alarm.pending.intent.target==AppStartReceiver.class,"bounded explicit recovery alarm "+sdk);
            service.onDestroy();
            check(delayed.isEmpty() && service.removed==1 && broadcasts==0,"destroy cleans up without restart loop "+sdk);
        }
        reset(); Build.VERSION.SDK_INT=36;
        MessagesController.accounts[0].values.put("keepAliveService",false);
        check(!NotificationsService.isKeepAliveEnabled(),"legacy keep-alive false honored");
        ApplicationLoader.startPushService();
        check(stops==1 && starts==0 && jobs==0 && alarm.cancelled==1,"disabled cancels recovery and stops service");
        MessagesController.global.values.put("pushService",true);
        check(NotificationsService.isKeepAliveEnabled(),"global keep-alive overrides legacy false");
        NotificationsService disabled=new NotificationsService(); disabled.onCreate();
        MessagesController.global.values.put("pushService",false);
        check(disabled.onStartCommand(null,0,1)==Service.START_NOT_STICKY && disabled.stopped==1,"stale start does not re-enable");
        disabled.onDestroy();
        check(alarm.scheduled==0,"disabled destroy never schedules recovery");
        reset();
        MessagesController.accounts[0].values.put("backgroundConnection",false);
        check(!ConnectionsManager.accounts[0].isPushConnectionEnabled()
            && ConnectionsManager.accounts[1].isPushConnectionEnabled(),"legacy connection preference belongs to each account");
        MessagesController.global.values.put("pushConnection",true);
        check(ConnectionsManager.accounts[0].isPushConnectionEnabled(),"global connection override applies to every account");
        MessagesController.global.values.put("pushConnection",false);
        NotificationsService active=new NotificationsService(); active.onCreate(); active.onStartCommand(null,0,1);
        check(ConnectionsManager.accounts[0].resumes==0 && !ConnectionsManager.accounts[0].enabled,"disabled connection stays disabled");
        MessagesController.global.values.put("pushService",false);
        Runnable heartbeat=delayed.keySet().iterator().next(); delayed.clear(); heartbeat.run();
        check(active.stopped==1 && delayed.isEmpty(),"disabling stops heartbeat");
        reset(); rejectStart=true;
        ApplicationLoader.startPushService();
        check(logs==1 && jobs==1 && init>0,"blocked FGS start logs and enqueues bounded job");
        check(ConnectionsManager.accounts[0].resumes==1 && alarm.scheduled==1 && delayed.isEmpty(),"blocked start resumes sockets with alarm fallback only");
        check(plainStarts==0,"no prohibited plain-service fallback");
        reset(); rejectPromotion=true;
        NotificationsService rejected=new NotificationsService(); rejected.onCreate();
        check(rejected.onStartCommand(null,0,1)==Service.START_NOT_STICKY && rejected.stopped==1,"promotion failure stops unprotected service");
        check(jobs==1 && alarm.scheduled==1 && delayed.isEmpty(),"promotion failure retains bounded recovery");
        reset(); rejectJob=true; KeepAliveJob.startJob();
        check(jobs==1 && logs==1,"job enqueue failure logged");
        rejectJob=false; KeepAliveJob.startJob();
        check(jobs==2,"failed enqueue does not wedge future keep-alive jobs");
        KeepAliveJob.startJob(); check(jobs==2,"queued job not duplicated"); KeepAliveJob.finishJob();
        reset(); for (UserConfig user:UserConfig.accounts) user.active=false;
        NotificationsService.onPushServiceTick(true,true);
        check(jobs==0,"logged-out clients do not enqueue network work");
        reset(); NotificationsService timed=new NotificationsService(); timed.onTimeout(1); timed.onTimeout(1,1);
        check(timed.stopped==2,"timeout callbacks stop service");
        for (String action:new String[]{Intent.ACTION_BOOT_COMPLETED,Intent.ACTION_MY_PACKAGE_REPLACED,"org.telegram.start"}) {
            reset(); SharedConfig.passcodeHash="hash";
            Intent intent=new Intent(); intent.setAction(action);
            new AppStartReceiver().onReceive(ApplicationLoader.applicationContext,intent);
            check(starts==1 && events.indexOf("request")<events.indexOf("init"),"receiver requests service before expensive init: "+action);
            check(LocalRepeatRefill.schedules==1,"receiver preserves repeat recovery: "+action);
            check(SharedConfig.appLocked==Intent.ACTION_BOOT_COMPLETED.equals(action),"boot locks passcode: "+action);
        }
        reset(); new AppStartReceiver().onReceive(ApplicationLoader.applicationContext,null);
        Intent unknown=new Intent(); unknown.setAction("unknown");
        new AppStartReceiver().onReceive(ApplicationLoader.applicationContext,unknown);
        check(starts==0 && init==0,"receiver ignores null and unknown intents");
        System.out.println("Notification service: "+checks+" lifecycle checks passed; manifest checks passed");
    }
}
'''
replacements = {
    'PUSH_PREFERENCE': block(source('tgnet/ConnectionsManager.java'), 'public boolean isPushConnectionEnabled()'),
    'START_SERVICE': block(source('messenger/ApplicationLoader.java'), 'public static void startPushService()'),
    'NOTIFICATION_SERVICE': nested('messenger/NotificationsService.java'),
    'KEEP_ALIVE_JOB': nested('messenger/KeepAliveJob.java'),
    'START_RECEIVER': nested('messenger/AppStartReceiver.java'),
}
for key, value in replacements.items():
    java = java.replace('@' + key + '@', value)
with tempfile.TemporaryDirectory(prefix='moagram-notification-test-') as directory:
    test = Path(directory) / 'NotificationServiceTest.java'
    test.write_text(java)
    subprocess.run(['javac', '-d', directory, str(test)], check=True)
    subprocess.run(['java', '-cp', directory, 'NotificationServiceTest'], check=True)

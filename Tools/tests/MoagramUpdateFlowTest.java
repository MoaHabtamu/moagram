import android.content.Context;
import org.telegram.messenger.*;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.ActionBar.AlertDialog;

import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** Integration tests of the production controller; Android and HTTPS are test doubles. */
public class MoagramUpdateFlowTest {
    private static final List<MoagramUpdateController> controllers = new ArrayList<>();
    private static final byte[] apk = new byte[128 * 1024];
    private static final String hash = String.join("", Collections.nCopies(64, "a"));
    private static final MoagramUpdatePolicy update = new MoagramUpdatePolicy(1, MoagramUpdatePolicy.PACKAGE,
            2, "2.0", 23, "https://github.com/MoaHabtamu/moagram/releases/download/v2/app.apk",
            hash, apk.length, 2, "Release notes", "Critical fix");
    private static volatile int requests;
    private static CountDownLatch hold;
    private static File directory;

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static void until(BooleanSupplier done) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!done.getAsBoolean() && System.nanoTime() < deadline) {
            Runnable task = AndroidUtilities.ui.poll(50, TimeUnit.MILLISECONDS);
            if (task != null) task.run();
        }
        require(done.getAsBoolean(), "Timed out waiting for controller");
    }
    private static MoagramUpdateController controller() throws Exception {
        Constructor<MoagramUpdateController> constructor = MoagramUpdateController.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        MoagramUpdateController controller = constructor.newInstance();
        controllers.add(controller);
        return controller;
    }
    private static Context reset(String name) throws Exception {
        while (!AndroidUtilities.ui.isEmpty()) AndroidUtilities.ui.remove().run();
        Context context = new Context();
        context.cache = new File(directory, name);
        context.cache.mkdirs();
        ApplicationLoader.applicationContext = context;
        ApplicationLoader.mainInterfacePaused = false;
        SharedConfig.appLocked = false;
        AlertDialog.shown = null;
        MoagramUpdateSecurity.reject = false;
        MoagramUpdateSecurity.gate = null;
        hold = null;
        return context;
    }
    private static void prompt(MoagramUpdateController controller, LaunchActivity activity) throws Exception {
        Method method = MoagramUpdateController.class.getDeclaredMethod("prompt", LaunchActivity.class, MoagramUpdatePolicy.class);
        method.setAccessible(true);
        method.invoke(controller, activity, update);
    }
    private static void download(MoagramUpdateController controller, LaunchActivity activity) throws Exception {
        controller.onActivityResumed(activity);
        controller.offer(update);
        controller.download();
        until(controller::ready);
    }
    private static void criticalDownloadAndPermission() throws Exception {
        Context context = reset("critical");
        MoagramUpdateController controller = controller();
        LaunchActivity activity = new LaunchActivity();
        controller.onActivityResumed(activity);
        prompt(controller, activity);
        require(AlertDialog.shown != null, "Critical alert missing");
        require(AlertDialog.shown.positive.equals(LocaleController.getString(R.string.MoagramDownloadUpdate)), "Critical action must say download");
        int before = requests;
        AlertDialog.shown.accept();
        require(controller.downloading(), "Critical action did not start download directly");
        int[] notifications = {0};
        Runnable listener = () -> notifications[0]++;
        controller.addListener(listener);
        until(controller::ready);
        controller.removeListener(listener);
        require(requests == before + 1, "Unexpected download request count");
        require(controller.downloadedBytes() == update.size, "Completion must report full progress");
        require(notifications[0] > 0, "Shared UI listeners were not notified");
        require(AlertDialog.shown.message.equals(LocaleController.getString(R.string.MoagramUpdatePermissionInfo)), "Permission explanation missing");
        AlertDialog.shown.accept();
        require(activity.launched.equals(Arrays.asList("permission")), "Must open this app's installation permission settings");
        controller.onActivityPaused(activity);
        controller.onActivityResumed(activity);
        require(!controller.busy() && activity.launched.size() == 1, "Denied permission must not loop or install");
        context.manager.allowed = true;
        controller.onActivityResumed(activity);
        controller.onActivityResumed(activity);
        until(() -> activity.launched.size() == 2);
        require(activity.launched.get(1).equals("install"), "Installer did not resume after permission granted");
        controller.onActivityResumed(activity);
        require(activity.launched.size() == 2, "Installer reopened after returning from it");
        prompt(controller, activity);
        require(AlertDialog.shown == null, "Critical download prompt repeated for an already downloaded release");
        require(requests == before + 1, "Permission flow downloaded the APK again");
    }
    private static void permissionSurvivesProcessRecreation() throws Exception {
        Context context = reset("restart");
        MoagramUpdateController first = controller();
        LaunchActivity old = new LaunchActivity();
        download(first, old);
        AlertDialog.shown.accept();
        first.onActivityPaused(old);
        context.manager.allowed = true;
        int before = requests;
        int verifications = MoagramUpdateSecurity.verifications;
        MoagramUpdateController replacement = controller();
        LaunchActivity activity = new LaunchActivity();
        replacement.onActivityResumed(activity);
        require(replacement.checking(), "Saved APK must be reverified before becoming ready");
        until(() -> activity.launched.contains("install"));
        require(replacement.ready(), "Downloaded APK was lost across recreation");
        require(requests == before, "Restart redownloaded an existing APK");
        require(MoagramUpdateSecurity.verifications >= verifications + 2, "Restore and installer must each verify the APK");
        require(!context.prefs.getBoolean("pending_install", false), "Pending install was not consumed");
    }
    private static void activityRecreationDuringInstallerVerification() throws Exception {
        Context context = reset("rotation");
        MoagramUpdateController controller = controller();
        LaunchActivity old = new LaunchActivity();
        download(controller, old);
        AlertDialog.shown.accept();
        controller.onActivityPaused(old);
        context.manager.allowed = true;
        MoagramUpdateSecurity.gate = new CountDownLatch(1);
        controller.onActivityResumed(old);
        require(controller.installing(), "Installer verification did not start");
        controller.onActivityPaused(old);
        old.destroyed = true;
        LaunchActivity replacement = new LaunchActivity();
        controller.onActivityResumed(replacement);
        MoagramUpdateSecurity.gate.countDown();
        until(() -> replacement.launched.contains("install"));
        require(old.launched.equals(Arrays.asList("permission")), "Installer targeted the destroyed activity");
    }
    private static void missingOrInstalledApkIsNotRestored() throws Exception {
        for (boolean installed : new boolean[]{false, true}) {
            Context context = reset(installed ? "already-installed" : "missing");
            MoagramUpdateController first = controller();
            LaunchActivity old = new LaunchActivity();
            download(first, old);
            AlertDialog.shown.accept();
            first.onActivityPaused(old);
            if (installed) context.manager.installed = 2;
            else Files.delete(new File(context.cache, "moagram-updates/" + hash + ".apk").toPath());
            context.manager.allowed = true;
            MoagramUpdateController replacement = controller();
            LaunchActivity activity = new LaunchActivity();
            replacement.onActivityResumed(activity);
            until(() -> !replacement.busy());
            require(!replacement.ready() && activity.launched.isEmpty(), "Invalid saved APK opened installer");
            require(context.prefs.getString("ready_update", null) == null, "Stale download state was retained");
            require(!context.prefs.getBoolean("pending_install", false), "Stale install request was retained");
        }
    }
    private static void cancelledDownloadCanRestart() throws Exception {
        reset("cancel");
        MoagramUpdateController controller = controller();
        controller.onActivityResumed(new LaunchActivity());
        controller.offer(update);
        hold = new CountDownLatch(1);
        int before = requests;
        controller.download();
        until(() -> requests == before + 1);
        controller.cancelDownload();
        hold = null;
        controller.download();
        until(controller::ready);
        require(requests == before + 2 && controller.error() == null, "Cancellation corrupted the next download");
    }
    private static void deferredNoticeAndInstallReverification() throws Exception {
        Context context = reset("background");
        MoagramUpdateController controller = controller();
        LaunchActivity activity = new LaunchActivity();
        controller.onActivityResumed(activity);
        controller.offer(update);
        controller.onActivityPaused(activity);
        ApplicationLoader.mainInterfacePaused = true;
        controller.download();
        until(controller::ready);
        require(AlertDialog.shown == null, "Ready alert appeared while app was paused");
        ApplicationLoader.mainInterfacePaused = false;
        SharedConfig.appLocked = true;
        controller.onActivityResumed(activity);
        require(AlertDialog.shown == null, "Ready alert appeared over passcode");
        SharedConfig.appLocked = false;
        controller.onActivityResumed(activity);
        require(AlertDialog.shown != null, "Ready notice was lost while paused/locked");
        AlertDialog.shown.dismiss();
        context.manager.allowed = true;
        MoagramUpdateSecurity.reject = true;
        controller.install(activity);
        until(() -> !controller.busy());
        require(!controller.ready() && controller.error() != null && activity.launched.isEmpty(), "Installer accepted an APK that failed reverification");
    }
    public static void main(String[] args) throws Exception {
        directory = new File(args[0]);
        URL.setURLStreamHandlerFactory(protocol -> !protocol.equals("https") ? null : new URLStreamHandler() {
            @Override protected URLConnection openConnection(URL url) {
                return new HttpURLConnection(url) {
                    final CountDownLatch gate = hold;
                    @Override public int getResponseCode() { requests++; return 200; }
                    @Override public String getHeaderField(String name) { return name.equals("Content-Length") ? String.valueOf(apk.length) : null; }
                    @Override public InputStream getInputStream() throws IOException {
                        if (gate != null) try { gate.await(); } catch (InterruptedException e) { throw new IOException(e); }
                        return new ByteArrayInputStream(apk);
                    }
                    @Override public void disconnect() { if (gate != null) gate.countDown(); }
                    @Override public void connect() {}
                    @Override public boolean usingProxy() { return false; }
                };
            }
        });
        try {
            criticalDownloadAndPermission();
            permissionSurvivesProcessRecreation();
            activityRecreationDuringInstallerVerification();
            missingOrInstalledApkIsNotRestored();
            cancelledDownloadCanRestart();
            deferredNoticeAndInstallReverification();
            System.out.println("Update flow tests passed: critical download, shared progress, permission denial/grant, process recreation, stale APKs, cancellation, deferred notices, reverification");
        } finally {
            for (MoagramUpdateController controller : controllers) {
                Field field = MoagramUpdateController.class.getDeclaredField("worker");
                field.setAccessible(true);
                ((ExecutorService) field.get(controller)).shutdownNow();
            }
        }
    }
}

package org.telegram.messenger;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import androidx.core.content.FileProvider;
import org.json.JSONObject;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.MoagramUpdatesActivity;
import java.io.*;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** No background APK downloads. Only critical metadata is checked on foreground resumes. */
public final class MoagramUpdateController {
    private static final MoagramUpdateController INSTANCE = new MoagramUpdateController();
    public static MoagramUpdateController getInstance() { return INSTANCE; }
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
    private boolean checking, criticalChecking, installing;
    private Download download;
    private MoagramUpdatePolicy policy, criticalPolicy;
    private long criticalFetchedAt;
    private File readyApk;
    private String error;
    private long bytes;
    private AlertDialog criticalDialog;
    private static final class Download {
        volatile boolean cancelled;
        volatile HttpURLConnection connection;
    }
    private Context context() { return ApplicationLoader.applicationContext; }
    private SharedPreferences prefs() { return context().getSharedPreferences("moagram_updates", Context.MODE_PRIVATE); }
    public static boolean supported() {
        return ApplicationLoader.applicationContext != null
                && MoagramUpdatePolicy.PACKAGE.equals(ApplicationLoader.applicationContext.getPackageName());
    }
    public long installedCode() {
        try { return code(context().getPackageManager().getPackageInfo(context().getPackageName(), 0)); }
        catch (Exception e) { return Long.MAX_VALUE; }
    }
    public String installedVersion() {
        try { return context().getPackageManager().getPackageInfo(context().getPackageName(), 0).versionName; }
        catch (Exception e) { return BuildVars.BUILD_VERSION_STRING; }
    }
    private static long code(PackageInfo info) { return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode; }
    public void addListener(Runnable listener) { listeners.add(listener); }
    public void removeListener(Runnable listener) { listeners.remove(listener); }
    private void changed() { for (Runnable listener : listeners) listener.run(); }
    public boolean busy() { return checking || download != null || installing; }
    public boolean downloading() { return download != null; }
    public boolean checking() { return checking; }
    public boolean ready() { return readyApk != null && readyApk.isFile(); }
    public long downloadedBytes() { return bytes; }
    public MoagramUpdatePolicy policy() { return policy; }
    public String error() { return error; }
    public void offer(MoagramUpdatePolicy update) {
        if (busy()) return;
        if (policy == null || !policy.sha256.equals(update.sha256)) readyApk = null;
        policy = update;
        error = null;
        changed();
    }
    private HttpURLConnection open(String initial, boolean metadata, Download job) throws Exception {
        String address = initial;
        for (int hop = 0; hop < 6; hop++) {
            if (metadata ? !MoagramUpdatePolicy.FEED.equals(address) : !MoagramUpdatePolicy.allowedDownloadUrl(address))
                throw new SecurityException("Untrusted update URL");
            if (job != null && job.cancelled) throw new InterruptedIOException();
            HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
            if (job != null) job.connection = connection;
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(20000);
            connection.setRequestProperty("User-Agent", "Moagram-Android-Updater");
            connection.setRequestProperty("Accept-Encoding", "identity");
            int response;
            try { response = connection.getResponseCode(); }
            catch (Exception e) { connection.disconnect(); throw e; }
            if (response == 200) return connection;
            String location = connection.getHeaderField("Location");
            connection.disconnect();
            if (metadata || !(response == 301 || response == 302 || response == 303 || response == 307 || response == 308) || location == null)
                throw new IOException("Update server unavailable");
            address = new URL(new URL(address), location).toString();
        }
        throw new IOException("Too many redirects");
    }
    private MoagramUpdatePolicy fetch() throws Exception {
        HttpURLConnection connection = open(MoagramUpdatePolicy.FEED, true, null);
        try (InputStream input = connection.getInputStream(); ByteArrayOutputStream body = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (body.size() + read > 65536) throw new IOException("Update metadata too large");
                body.write(buffer, 0, read);
            }
            JSONObject json = new JSONObject(body.toString("UTF-8"));
            return new MoagramUpdatePolicy(json.getInt("schema_version"), json.getString("package_name"),
                    json.getLong("version_code"), json.getString("version_name"), json.getInt("min_sdk"),
                    json.getString("apk_url"), json.getString("sha256"), json.getLong("size"),
                    json.getLong("critical_below_version_code"), json.getString("notes"), json.getString("critical_message"));
        } finally { connection.disconnect(); }
    }
    public void check() {
        if (busy() || !supported()) return;
        checking = true;
        error = null;
        changed();
        worker.execute(() -> {
            MoagramUpdatePolicy found = null;
            try { found = fetch(); } catch (Exception ignored) { }
            final MoagramUpdatePolicy result = found;
            AndroidUtilities.runOnUIThread(() -> {
                checking = false;
                if (result == null) error = LocaleController.getString(R.string.MoagramUpdateCheckFailed);
                else offer(result);
                changed();
            });
        });
    }
    public void checkCritical(LaunchActivity activity) {
        if (!supported() || SharedConfig.appLocked) return;
        long now = System.currentTimeMillis();
        if (criticalPolicy != null && now >= criticalFetchedAt && now - criticalFetchedAt < MoagramUpdatePolicy.CHECK_INTERVAL)
            prompt(activity, criticalPolicy);
        if (criticalChecking || !MoagramUpdatePolicy.intervalElapsed(now, prefs().getLong("last_critical_check", 0), MoagramUpdatePolicy.CHECK_INTERVAL)) return;
        criticalChecking = true;
        prefs().edit().putLong("last_critical_check", now).apply();
        WeakReference<LaunchActivity> reference = new WeakReference<>(activity);
        worker.execute(() -> {
            MoagramUpdatePolicy found = null;
            try { found = fetch(); } catch (Exception ignored) { }
            final MoagramUpdatePolicy result = found;
            AndroidUtilities.runOnUIThread(() -> {
                criticalChecking = false;
                criticalPolicy = result;
                criticalFetchedAt = System.currentTimeMillis();
                LaunchActivity current = reference.get();
                if (current != null && result != null) prompt(current, result);
            });
        });
    }
    private void prompt(LaunchActivity activity, MoagramUpdatePolicy update) {
        long now = System.currentTimeMillis();
        if (activity.isFinishing() || activity.isDestroyed() || ApplicationLoader.mainInterfacePaused
                || SharedConfig.appLocked || busy() || criticalDialog != null
                || !update.criticalFor(installedCode()) || !update.compatible(Build.VERSION.SDK_INT)
                || !MoagramUpdatePolicy.intervalElapsed(now, prefs().getLong("last_critical_prompt", 0), MoagramUpdatePolicy.REMINDER_INTERVAL)) return;
        BaseFragment fragment = LaunchActivity.getLastFragment();
        if (fragment == null || fragment instanceof MoagramUpdatesActivity) return;
        AlertDialog.Builder builder = new AlertDialog.Builder(activity, fragment.getResourceProvider());
        builder.setTitle(LocaleController.getString(R.string.MoagramCriticalUpdate));
        builder.setMessage((update.criticalMessage.isEmpty() ? LocaleController.getString(R.string.MoagramCriticalUpdateInfo) : update.criticalMessage)
                + "\n\n" + LocaleController.getString(R.string.MoagramCriticalReminderInfo));
        builder.setPositiveButton(LocaleController.getString(R.string.MoagramReviewUpdate), (dialog, which) -> fragment.presentFragment(new MoagramUpdatesActivity(update)));
        builder.setNegativeButton(LocaleController.getString(R.string.MoagramUpdateLater), null);
        builder.setOnDismissListener(dialog -> criticalDialog = null);
        criticalDialog = builder.create();
        criticalDialog.setOwnerActivity(activity);
        try {
            criticalDialog.show();
            prefs().edit().putLong("last_critical_prompt", now).apply();
        } catch (Exception ignored) { criticalDialog = null; }
    }
    public void onActivityPaused(Activity activity) {
        if (criticalDialog != null && criticalDialog.getOwnerActivity() == activity) criticalDialog.dismiss();
    }
    private List<byte[]> installedSigners() throws Exception {
        PackageManager manager = context().getPackageManager();
        PackageInfo info = manager.getPackageInfo(context().getPackageName(), Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES);
        Signature[] signers = Build.VERSION.SDK_INT >= 28 ? info.signingInfo.getApkContentsSigners() : info.signatures;
        List<byte[]> certificates = new ArrayList<>();
        for (Signature signature : signers) certificates.add(signature.toByteArray());
        return certificates;
    }
    private void verify(File file, MoagramUpdatePolicy update) throws Exception {
        MoagramUpdateSecurity.verify(file, update.size, update.sha256, installedSigners(), Build.VERSION.SDK_INT);
        PackageInfo archive = context().getPackageManager().getPackageArchiveInfo(file.getAbsolutePath(), 0);
        if (archive == null || !context().getPackageName().equals(archive.packageName)
                || code(archive) != update.versionCode || code(archive) <= installedCode()
                || Build.VERSION.SDK_INT >= 24 && archive.applicationInfo.minSdkVersion > Build.VERSION.SDK_INT)
            throw new SecurityException("APK package or version differs");
    }
    public void download() {
        final MoagramUpdatePolicy update = policy;
        if (busy() || update == null || !update.newerThan(installedCode()) || !update.compatible(Build.VERSION.SDK_INT)) return;
        final Download job = new Download();
        download = job;
        readyApk = null;
        error = null;
        bytes = 0;
        changed();
        worker.execute(() -> {
            File directory = new File(context().getCacheDir(), "moagram-updates");
            File partial = new File(directory, "download.part");
            File target = new File(directory, update.sha256 + ".apk");
            File completed = null;
            try {
                if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create update cache");
                if (target.exists()) {
                    try { verify(target, update); completed = target; } catch (Exception ignored) { target.delete(); }
                }
                if (completed == null) {
                    File[] oldFiles = directory.listFiles();
                    if (oldFiles != null) for (File old : oldFiles) if (old.isFile()) old.delete();
                    if (directory.getUsableSpace() < update.size + 16L * 1024 * 1024) throw new IOException("Insufficient storage");
                    job.connection = open(update.apkUrl, false, job);
                    String length = job.connection.getHeaderField("Content-Length");
                    long declared = length == null ? -1 : Long.parseLong(length);
                    if (declared != -1 && declared != update.size) throw new IOException("Unexpected APK size");
                    long total = 0, lastProgress = 0;
                    try (InputStream input = job.connection.getInputStream(); FileOutputStream output = new FileOutputStream(partial)) {
                        byte[] buffer = new byte[65536];
                        int read;
                        while ((read = input.read(buffer)) != -1) {
                            if (job.cancelled) throw new InterruptedIOException();
                            total += read;
                            if (total > update.size) throw new IOException("APK exceeds expected size");
                            output.write(buffer, 0, read);
                            if (System.currentTimeMillis() - lastProgress > 250) {
                                final long received = total;
                                AndroidUtilities.runOnUIThread(() -> { if (download == job) { bytes = received; changed(); } });
                                lastProgress = System.currentTimeMillis();
                            }
                        }
                    }
                    if (job.cancelled) throw new InterruptedIOException();
                    verify(partial, update);
                    if (job.cancelled) throw new InterruptedIOException();
                    if (!partial.renameTo(target)) throw new IOException("Cannot finish update file");
                    completed = target;
                }
            } catch (Exception ignored) { }
            finally {
                partial.delete();
                if (job.connection != null) job.connection.disconnect();
            }
            final File verified = completed;
            AndroidUtilities.runOnUIThread(() -> {
                if (download != job) return;
                download = null;
                readyApk = verified;
                error = verified == null ? LocaleController.getString(R.string.MoagramUpdateDownloadFailed) : null;
                changed();
            });
        });
    }
    public void cancelDownload() {
        Download job = download;
        if (job == null) return;
        job.cancelled = true;
        Utilities.globalQueue.postRunnable(() -> { if (job.connection != null) job.connection.disconnect(); });
        download = null;
        bytes = 0;
        error = null;
        changed();
    }
    public void install(Activity activity) {
        if (busy() || !ready() || policy == null) return;
        if (Build.VERSION.SDK_INT >= 26 && !context().getPackageManager().canRequestPackageInstalls()) {
            try { activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + context().getPackageName()))); }
            catch (Exception ignored) { error = LocaleController.getString(R.string.MoagramUpdateInstallFailed); changed(); }
            return;
        }
        File file = readyApk;
        MoagramUpdatePolicy update = policy;
        installing = true;
        error = null;
        changed();
        WeakReference<Activity> reference = new WeakReference<>(activity);
        worker.execute(() -> {
            boolean valid;
            try { verify(file, update); valid = true; } catch (Exception ignored) { valid = false; }
            final boolean verified = valid;
            AndroidUtilities.runOnUIThread(() -> {
                installing = false;
                Activity current = reference.get();
                if (!verified) { readyApk = null; error = LocaleController.getString(R.string.MoagramUpdateDownloadFailed); }
                else if (current != null && !current.isFinishing() && !current.isDestroyed()) {
                    try {
                        Uri uri = FileProvider.getUriForFile(current, context().getPackageName() + ".provider", file);
                        Intent intent = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        current.startActivity(intent);
                    } catch (Exception ignored) { error = LocaleController.getString(R.string.MoagramUpdateInstallFailed); }
                }
                changed();
            });
        });
    }
}

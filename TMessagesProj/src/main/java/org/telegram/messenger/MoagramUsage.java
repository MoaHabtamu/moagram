package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.os.Build;

import org.json.JSONObject;
import org.telegram.ui.Components.ForegroundDetector;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Calendar;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Counts fresh installs separately from updates, and records that the app was opened.
 * A random install id and the app version are sent once at least one account is signed in.
 * Telegram account, phone number, and message contents are not.
 */
public final class MoagramUsage {
    static final String PREFS = "moagram_usage";
    static final long ACTIVE_REFRESH_MS = 6L * 60 * 60 * 1000;
    static final long FEED_REFRESH_MS = 24L * 60 * 60 * 1000;
    static final long TRANSIENT_RETRY_MS = 15L * 60 * 1000;
    static final long DISABLED_RETRY_MS = 6L * 60 * 60 * 1000;
    static final long WAKEUP_RETRY_DELAY_MS = 30L * 1000;
    private static final AtomicBoolean SENDING = new AtomicBoolean();
    private static final AtomicBoolean RETRY_SCHEDULED = new AtomicBoolean();

    private MoagramUsage() {}

    /** Only non-debug standalone installs with at least one signed-in account report usage. */
    public static boolean eligible(boolean debugBuild, String packageName, boolean hasSignedInAccount) {
        return !debugBuild && MoagramUpdatePolicy.PACKAGE.equals(packageName) && hasSignedInAccount;
    }

    /** Null means nothing needs to be sent. */
    public static String choose(boolean acknowledged, boolean freshPackage, int versionCode, int reportedVersion, int today, int lastPingDay) {
        return choose(acknowledged, freshPackage, versionCode, reportedVersion, today, lastPingDay, 0L, 0L);
    }

    /** Null means nothing needs to be sent. Refreshes active status every 6 hours while foregrounded. */
    public static String choose(boolean acknowledged, boolean freshPackage, int versionCode,
                                int reportedVersion, int today, int lastPingDay,
                                long now, long lastPingAt) {
        if (!acknowledged) return freshPackage ? "install" : "update";
        if (versionCode > reportedVersion) return "update";
        if (lastPingDay != today) return "active";
        if (lastPingAt > 0 && now > lastPingAt && now - lastPingAt >= ACTIVE_REFRESH_MS) return "active";
        return null;
    }

    /** A package Android has not replaced since it was first installed. */
    public static boolean freshInstall(long firstInstallTime, long lastUpdateTime) {
        return lastUpdateTime >= firstInstallTime && lastUpdateTime - firstInstallTime <= 120_000L;
    }

    public static boolean allowedUrl(String value) {
        if (value == null || value.length() > 200) return false;
        try {
            URI uri = new URI(value);
            return "https".equals(uri.getScheme()) && uri.getHost() != null && uri.getUserInfo() == null
                    && uri.getPort() == -1 && uri.getRawQuery() == null && uri.getRawFragment() == null
                    && "/v1/event".equals(uri.getRawPath());
        } catch (Exception ignored) {
            return false;
        }
    }

    public static boolean sharing() {
        Context context = ApplicationLoader.applicationContext;
        return context != null && prefs(context).getBoolean("enabled", true);
    }

    public static void onForeground() {
        Context context = ApplicationLoader.applicationContext;
        if (context == null || !sharing() || !SENDING.compareAndSet(false, true)) return;
        Utilities.globalQueue.postRunnable(() -> {
            boolean networkFailure = false;
            try {
                send(context);
            } catch (Throwable e) {
                networkFailure = true;
                FileLog.e(e);
            } finally {
                SENDING.set(false);
            }
            if (networkFailure) {
                scheduleForegroundRetry();
            }
        });
    }

    private static void scheduleForegroundRetry() {
        if (!RETRY_SCHEDULED.compareAndSet(false, true)) return;
        Utilities.globalQueue.postRunnable(() -> {
            RETRY_SCHEDULED.set(false);
            ForegroundDetector detector = ForegroundDetector.getInstance();
            if (detector != null && !detector.isBackground()) {
                onForeground();
            }
        }, WAKEUP_RETRY_DELAY_MS);
    }

    private static boolean hasSignedInAccount() {
        try {
            return UserConfig.getActivatedAccountsCount() > 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void send(Context context) throws Exception {
        if (!sharing() || !eligible(BuildConfig.DEBUG_VERSION, context.getPackageName(), hasSignedInAccount())) return;
        PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
        int version = (int) (Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode);
        SharedPreferences preferences = prefs(context);
        long now = System.currentTimeMillis();
        if (preferences.getLong("retry_after", 0) > now) return;
        String id = preferences.getString("install_id", "");
        if (id.isEmpty()) {
            id = UUID.randomUUID().toString();
            preferences.edit().putString("install_id", id).apply();
        }
        int day = utcDay(now);
        int reported = preferences.getInt("reported_version", -1);
        String event = choose(reported >= 0, freshInstall(info.firstInstallTime, info.lastUpdateTime),
                version, reported, day, preferences.getInt("last_ping_day", 0),
                now, preferences.getLong("last_ping_at", 0L));
        if (event == null) return;
        String endpoint = resolveUsageUrl(preferences, now);
        if (!allowedUrl(endpoint)) return;
        JSONObject body = new JSONObject();
        body.put("install_id", id);
        body.put("event", event);
        body.put("version_code", version);
        body.put("version_name", info.versionName == null ? "" : info.versionName);
        if (!post(endpoint, body.toString())) {
            preferences.edit().putLong("retry_after", now + TRANSIENT_RETRY_MS).apply();
            return;
        }
        preferences.edit()
                .putInt("reported_version", version)
                .putInt("last_ping_day", day)
                .putLong("last_ping_at", now)
                .remove("retry_after")
                .apply();
        if (BuildVars.LOGS_ENABLED) FileLog.d("Moagram usage recorded: " + event);
    }

    /**
     * Uses the cached collector URL for up to 24 hours and falls back to it if GitHub is unreachable.
     * An HTTP 200 feed with an empty usage_url explicitly disables collection until the next check.
     */
    private static String resolveUsageUrl(SharedPreferences preferences, long now) throws Exception {
        String cached = preferences.getString("usage_url", "");
        long checkedAt = preferences.getLong("usage_url_checked_at", 0L);
        if (allowedUrl(cached) && now >= checkedAt && now - checkedAt < FEED_REFRESH_MS) {
            return cached;
        }
        try {
            FeedResult result = fetchFeedUsageUrl();
            if (result.ok) {
                if (allowedUrl(result.url)) {
                    preferences.edit()
                            .putString("usage_url", result.url)
                            .putLong("usage_url_checked_at", now)
                            .apply();
                    return result.url;
                }
                // Feed returned HTTP 200 with no usage_url: respect the remote kill-switch.
                preferences.edit()
                        .remove("usage_url")
                        .putLong("usage_url_checked_at", now)
                        .putLong("retry_after", now + DISABLED_RETRY_MS)
                        .apply();
                return "";
            }
        } catch (Exception e) {
            if (allowedUrl(cached)) {
                return cached;
            }
            throw e;
        }
        if (allowedUrl(cached)) {
            return cached;
        }
        preferences.edit().putLong("retry_after", now + TRANSIENT_RETRY_MS).apply();
        return "";
    }

    private static final class FeedResult {
        final boolean ok;
        final String url;

        FeedResult(boolean ok, String url) {
            this.ok = ok;
            this.url = url;
        }
    }

    private static FeedResult fetchFeedUsageUrl() throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(MoagramUpdatePolicy.FEED).openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(15000);
        connection.setRequestProperty("User-Agent", "Moagram-Android-Usage");
        connection.setRequestProperty("Accept-Encoding", "identity");
        try {
            if (connection.getResponseCode() != 200) return new FeedResult(false, "");
            return new FeedResult(true, new JSONObject(readLimited(connection.getInputStream())).optString("usage_url", ""));
        } finally {
            connection.disconnect();
        }
    }

    private static boolean post(String endpoint, String body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(15000);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("User-Agent", "Moagram-Android-Usage");
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        try {
            connection.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(bytes);
            }
            return connection.getResponseCode() == 204;
        } finally {
            connection.disconnect();
        }
    }

    private static String readLimited(InputStream input) throws Exception {
        try (InputStream source = input; ByteArrayOutputStream body = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[2048];
            int read;
            while ((read = source.read(buffer)) != -1) {
                if (body.size() + read > 65536) throw new java.io.IOException("Usage feed is too large");
                body.write(buffer, 0, read);
            }
            return body.toString("UTF-8");
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static int utcDay(long timestamp) {
        Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        calendar.setTimeInMillis(timestamp);
        return calendar.get(Calendar.YEAR) * 10000
                + (calendar.get(Calendar.MONTH) + 1) * 100
                + calendar.get(Calendar.DAY_OF_MONTH);
    }
}

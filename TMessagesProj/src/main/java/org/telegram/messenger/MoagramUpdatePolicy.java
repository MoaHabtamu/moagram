package org.telegram.messenger;

import java.net.URI;
import java.util.Locale;

/** Distribution metadata; contains no account or device identifiers. */
public final class MoagramUpdatePolicy {
    public static final String PACKAGE = "org.moagram.messenger.web";
    public static final String FEED = "https://raw.githubusercontent.com/MoaHabtamu/moagram/master/updates/android.json";
    public static final long MAX_APK_BYTES = 256L * 1024 * 1024;
    public static final long REMINDER_INTERVAL = 24L * 60 * 60 * 1000;
    public static final long CHECK_INTERVAL = 6L * 60 * 60 * 1000;
    public final long versionCode, size, criticalBelow;
    public final int minSdk;
    public final String versionName, apkUrl, sha256, notes, criticalMessage;

    public MoagramUpdatePolicy(int schema, String packageName, long code, String name,
                               int minSdk, String url, String hash, long size,
                               long criticalBelow, String notes, String criticalMessage) {
        if (schema != 1 || !PACKAGE.equals(packageName) || code <= 0 || code > 2100000000L
                || name == null || name.trim().isEmpty() || name.length() > 80
                || minSdk < 23 || minSdk > 100 || size <= 0 || size > MAX_APK_BYTES
                || criticalBelow < 0 || criticalBelow > code || hash == null
                || !hash.matches("[0-9a-fA-F]{64}") || !isReleaseApkUrl(url)
                || notes == null || notes.length() > 8000
                || criticalMessage == null || criticalMessage.length() > 2000) {
            throw new IllegalArgumentException("Invalid Moagram update metadata");
        }
        versionCode = code;
        versionName = name;
        this.minSdk = minSdk;
        apkUrl = url;
        sha256 = hash.toLowerCase(Locale.US);
        this.size = size;
        this.criticalBelow = criticalBelow;
        this.notes = notes;
        this.criticalMessage = criticalMessage;
    }

    public boolean newerThan(long installed) { return versionCode > installed; }
    public boolean compatible(int sdk) { return sdk >= minSdk; }
    public boolean criticalFor(long installed) { return newerThan(installed) && installed < criticalBelow; }
    public static boolean intervalElapsed(long now, long then, long interval) {
        return then <= 0 || now >= then && now - then >= interval;
    }
    public static boolean isReleaseApkUrl(String value) {
        try {
            URI uri = new URI(value);
            String path = uri.getRawPath();
            return "https".equals(uri.getScheme()) && "github.com".equals(uri.getHost())
                    && uri.getPort() == -1 && uri.getUserInfo() == null
                    && uri.getRawQuery() == null && uri.getRawFragment() == null
                    && path != null && path.matches("/MoaHabtamu/moagram/releases/download/[A-Za-z0-9][A-Za-z0-9._-]*/[A-Za-z0-9][A-Za-z0-9._-]*\\.apk");
        } catch (Exception ignored) { return false; }
    }
    public static boolean allowedDownloadUrl(String value) {
        try {
            URI uri = new URI(value);
            return "https".equals(uri.getScheme()) && uri.getUserInfo() == null
                    && uri.getPort() == -1 && uri.getRawFragment() == null
                    && (isReleaseApkUrl(value) || "release-assets.githubusercontent.com".equals(uri.getHost()));
        } catch (Exception ignored) { return false; }
    }
}

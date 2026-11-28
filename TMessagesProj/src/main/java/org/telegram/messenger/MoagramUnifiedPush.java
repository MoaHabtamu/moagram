/*
 * This is the source code of Moagram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.messenger;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.SystemClock;

import org.unifiedpush.android.connector.UnifiedPush;

import java.util.List;

/**
 * {@link MoagramPush} transport through a UnifiedPush distributor (for example ntfy),
 * for phones without Google Play services. Endpoints and messages arrive in
 * {@link MoagramUnifiedPushService}.
 */
public final class MoagramUnifiedPush implements PushListenerController.IPushListenerServiceProvider {

    public static final MoagramUnifiedPush INSTANCE = new MoagramUnifiedPush();

    static final String INSTANCE_NAME = "default";

    private MoagramUnifiedPush() {
    }

    public static boolean hasDistributor() {
        try {
            return !UnifiedPush.getDistributors(ApplicationLoader.applicationContext).isEmpty();
        } catch (Throwable e) {
            FileLog.e(e);
            return false;
        }
    }

    /** True when Moagram could use UnifiedPush on this phone. */
    public static boolean shouldUse() {
        return MoagramPush.isConfigured() && MoagramPush.isUserEnabled() && hasDistributor();
    }

    /** True while a distributor is installed and has acknowledged Moagram's registration. */
    static boolean isLinked() {
        if (!shouldUse()) {
            return false;
        }
        try {
            return UnifiedPush.getAckDistributor(ApplicationLoader.applicationContext) != null;
        } catch (Throwable e) {
            FileLog.e(e);
            return false;
        }
    }

    public static String distributorName() {
        try {
            String pkg = UnifiedPush.getAckDistributor(ApplicationLoader.applicationContext);
            if (pkg == null) {
                return null;
            }
            PackageManager pm = ApplicationLoader.applicationContext.getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Throwable e) {
            return null;
        }
    }

    static void unregister() {
        try {
            if (UnifiedPush.getAckDistributor(ApplicationLoader.applicationContext) != null) {
                UnifiedPush.unregister(ApplicationLoader.applicationContext, INSTANCE_NAME);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    @Override
    public boolean hasServices() {
        return shouldUse();
    }

    @Override
    public String getLogTitle() {
        return "UnifiedPush";
    }

    @Override
    public int getPushType() {
        return PushListenerController.PUSH_TYPE_WEB;
    }

    @Override
    public void onRequestPushToken() {
        Utilities.globalQueue.postRunnable(() -> {
            try {
                Context context = ApplicationLoader.applicationContext;
                SharedConfig.pushStringGetTimeStart = SystemClock.elapsedRealtime();
                if (MoagramPush.ensureKeys() == null) {
                    MoagramPush.onLost(MoagramPush.TRANSPORT_UNIFIEDPUSH);
                    PushListenerController.sendRegistrationToServer(getPushType(), null);
                    return;
                }
                if (UnifiedPush.getAckDistributor(context) == null) {
                    String saved = UnifiedPush.getSavedDistributor(context);
                    List<String> distributors = UnifiedPush.getDistributors(context);
                    if (saved == null || !distributors.contains(saved)) {
                        if (distributors.isEmpty()) {
                            MoagramPush.onLost(MoagramPush.TRANSPORT_UNIFIEDPUSH);
                            return;
                        }
                        UnifiedPush.saveDistributor(context, distributors.get(0));
                    }
                }
                // The distributor answers through MoagramUnifiedPushService.onNewEndpoint.
                UnifiedPush.register(context, INSTANCE_NAME, "Moagram notifications", null);
            } catch (Throwable e) {
                FileLog.e(e);
            }
        });
    }
}

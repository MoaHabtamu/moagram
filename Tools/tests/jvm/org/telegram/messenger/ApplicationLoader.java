package org.telegram.messenger;

import android.content.Context;

/** Allows the real TL serializers to run in a JVM without starting Android. */
public final class ApplicationLoader {
    public static Context applicationContext;

    public static boolean isAndroidTestEnvironment() {
        return true;
    }
}

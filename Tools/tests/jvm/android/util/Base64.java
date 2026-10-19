package android.util;

/** JVM adapter used only by the command-line protocol tests. */
public final class Base64 {
    public static final int NO_WRAP = 2;

    public static String encodeToString(byte[] input, int flags) {
        return java.util.Base64.getEncoder().encodeToString(input);
    }

    public static byte[] decode(String input, int flags) {
        return java.util.Base64.getDecoder().decode(input);
    }
}

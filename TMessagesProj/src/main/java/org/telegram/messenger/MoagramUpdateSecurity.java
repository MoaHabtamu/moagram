package org.telegram.messenger;

import com.android.apksig.ApkVerifier;
import java.io.File;
import java.io.FileInputStream;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Verifies the actual APK signature, not just certificate metadata in its archive. */
public final class MoagramUpdateSecurity {
    public static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[65536];
            int read;
            while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
        }
        StringBuilder value = new StringBuilder();
        for (byte b : digest.digest()) value.append(String.format(java.util.Locale.US, "%02x", b & 255));
        return value.toString();
    }
    public static void verify(File apk, long size, String sha256, List<byte[]> installedSigners, int sdk) throws Exception {
        if (apk.length() != size || !sha256(apk).equals(sha256)) throw new SecurityException("APK checksum mismatch");
        ApkVerifier.Result result = new ApkVerifier.Builder(apk)
                .setMinCheckedPlatformVersion(sdk).setMaxCheckedPlatformVersion(sdk).build().verify();
        if (!result.isVerified()) throw new SecurityException("APK signature is invalid");
        Set<String> expected = new HashSet<>(), actual = new HashSet<>();
        for (byte[] signer : installedSigners) expected.add(java.util.Arrays.toString(signer));
        for (X509Certificate signer : result.getSignerCertificates()) actual.add(java.util.Arrays.toString(signer.getEncoded()));
        if (expected.isEmpty() || !expected.equals(actual)) throw new SecurityException("APK signing identity differs");
    }
}

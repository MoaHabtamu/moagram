import org.telegram.messenger.MoagramUpdatePolicy;
import org.telegram.messenger.MoagramUpdateSecurity;
import com.android.apksig.ApkVerifier;
import java.io.File;
import java.util.*;

public class MoagramUpdateTest {
    static int checks;
    static final String URL = "https://github.com/MoaHabtamu/moagram/releases/download/v1/Moagram.apk";
    static final String HASH = String.join("", Collections.nCopies(64, "a"));
    static void check(boolean value, String reason) { checks++; if (!value) throw new AssertionError(reason); }
    interface Action { void run() throws Exception; }
    static void rejects(Action action, String reason) throws Exception {
        boolean rejected = false;
        try { action.run(); } catch (Exception expected) { rejected = true; }
        check(rejected, reason);
    }
    static MoagramUpdatePolicy policy(long code, long critical, String url, String hash, long size) {
        return new MoagramUpdatePolicy(1, MoagramUpdatePolicy.PACKAGE, code, "2", 24, url, hash, size, critical, "notes", "Fix a critical issue");
    }
    public static void main(String[] args) throws Exception {
        MoagramUpdatePolicy p = policy(20, 15, URL, HASH, 100);
        check(p.newerThan(19) && !p.newerThan(20) && !p.newerThan(21), "version ordering and no downgrades");
        check(p.criticalFor(14) && !p.criticalFor(15) && !p.criticalFor(20), "critical threshold is exclusive");
        check(!p.compatible(23) && p.compatible(24), "Android compatibility");
        check(!policy(20, 0, URL, HASH, 100).criticalFor(1), "normal updates never nag");
        long day = MoagramUpdatePolicy.REMINDER_INTERVAL;
        check(MoagramUpdatePolicy.intervalElapsed(day, 0, day), "first prompt");
        check(!MoagramUpdatePolicy.intervalElapsed(day, 1, day), "less than 24 hours");
        check(MoagramUpdatePolicy.intervalElapsed(day + 1, 1, day), "24 hour boundary");
        check(!MoagramUpdatePolicy.intervalElapsed(1, day, day), "clock rollback must not repeat prompt");
        for (String url : new String[]{"http://github.com/MoaHabtamu/moagram/releases/download/v1/Moagram.apk", URL+"?x=1", URL+"#x", URL.replace("github.com", "github.com.evil.test"), URL.replace("github.com", "evil.test@github.com"), URL.replace("MoaHabtamu", "attacker"), URL.replace("/v1/", "/../"), URL.replace("/v1/", "/%2e%2e/"), URL.replace("github.com", "github.com:443")}) {
            rejects(() -> policy(20, 0, url, HASH, 100), "untrusted asset URL accepted: " + url);
        }
        check(MoagramUpdatePolicy.allowedDownloadUrl("https://release-assets.githubusercontent.com/asset?signature=test"), "GitHub signed CDN redirect");
        check(!MoagramUpdatePolicy.allowedDownloadUrl("http://release-assets.githubusercontent.com/asset"), "redirect downgrade");
        check(!MoagramUpdatePolicy.allowedDownloadUrl("https://release-assets.githubusercontent.com.evil.test/asset"), "redirect host spoofing");
        check(!MoagramUpdatePolicy.allowedDownloadUrl("https://evil.test/asset"), "arbitrary redirect");
        rejects(() -> policy(0, 0, URL, HASH, 100), "invalid version");
        rejects(() -> policy(20, 21, URL, HASH, 100), "threshold beyond release");
        rejects(() -> policy(20, -1, URL, HASH, 100), "negative threshold");
        rejects(() -> policy(20, 0, URL, "bad", 100), "invalid hash");
        rejects(() -> policy(20, 0, URL, HASH, 0), "empty APK");
        rejects(() -> policy(20, 0, URL, HASH, MoagramUpdatePolicy.MAX_APK_BYTES+1), "oversized APK");
        rejects(() -> new MoagramUpdatePolicy(2, MoagramUpdatePolicy.PACKAGE,20,"2",23,URL,HASH,100,0,"",""), "unknown schema");
        rejects(() -> new MoagramUpdatePolicy(1,"org.telegram.messenger",20,"2",23,URL,HASH,100,0,"",""), "wrong distribution");
        File good = new File(args[0]), other = new File(args[1]), tampered = new File(args[2]);
        List<byte[]> signers = Collections.singletonList(new ApkVerifier.Builder(good).build().verify().getSignerCertificates().get(0).getEncoded());
        String digest = MoagramUpdateSecurity.sha256(good);
        MoagramUpdateSecurity.verify(good,good.length(),digest,signers,34);
        check(true,"valid APK cryptographic signature");
        MoagramUpdateSecurity.verify(good,good.length(),digest,signers,23);
        check(true,"Android 6 signature compatibility");
        rejects(() -> MoagramUpdateSecurity.verify(good,good.length()+1,digest,signers,34), "truncated or oversized response");
        rejects(() -> MoagramUpdateSecurity.verify(good,good.length(),HASH,signers,34), "bad digest");
        rejects(() -> MoagramUpdateSecurity.verify(good,good.length(),digest,Collections.emptyList(),34), "missing installed identity");
        rejects(() -> MoagramUpdateSecurity.verify(other,other.length(),MoagramUpdateSecurity.sha256(other),signers,34), "valid APK signed by somebody else");
        rejects(() -> MoagramUpdateSecurity.verify(tampered,tampered.length(),MoagramUpdateSecurity.sha256(tampered),signers,34), "tampered APK with matching metadata checksum");
        System.out.println("Moagram update policy and signature checks passed: " + checks);
    }
}

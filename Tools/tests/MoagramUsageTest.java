public class MoagramUsageTest {
    private static int checks;

    public static void main(String[] args) {
        check("install".equals(org.telegram.messenger.MoagramUsage.choose(false, true, 10, -1, 20261106, 0)), "fresh package");
        check("update".equals(org.telegram.messenger.MoagramUsage.choose(false, false, 10, -1, 20261106, 0)), "package that was already updated");
        check("update".equals(org.telegram.messenger.MoagramUsage.choose(true, true, 11, 10, 20261106, 20261106)), "newer version");
        check("active".equals(org.telegram.messenger.MoagramUsage.choose(true, true, 10, 10, 20261106, 20261105)), "another day");
        check(org.telegram.messenger.MoagramUsage.choose(true, true, 10, 10, 20261106, 20261106) == null, "already sent today");
        check(org.telegram.messenger.MoagramUsage.choose(true, true, 9, 10, 20261106, 20261106) == null, "older version is not an update");
        check(org.telegram.messenger.MoagramUsage.freshInstall(1_000L, 1_000L), "same install and update time");
        check(org.telegram.messenger.MoagramUsage.freshInstall(1_000L, 50_000L), "short installer delay");
        check(!org.telegram.messenger.MoagramUsage.freshInstall(1_000L, 130_000L), "package was replaced");
        check(org.telegram.messenger.MoagramUsage.allowedUrl("https://stats.example.com/v1/event"), "https collector");
        check(!org.telegram.messenger.MoagramUsage.allowedUrl("http://stats.example.com/v1/event"), "plain http");
        check(!org.telegram.messenger.MoagramUsage.allowedUrl("https://user:pw@stats.example.com/v1/event"), "credentials in the URL");
        check(!org.telegram.messenger.MoagramUsage.allowedUrl("https://stats.example.com/v1/event?x=1"), "query string");
        check(!org.telegram.messenger.MoagramUsage.allowedUrl("https://stats.example.com/collect"), "unexpected path");
        check(!org.telegram.messenger.MoagramUsage.allowedUrl(""), "empty address");
        System.out.println("MoagramUsage: " + checks + " checks passed");
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}

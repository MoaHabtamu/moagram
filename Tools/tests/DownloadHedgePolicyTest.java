package org.telegram.messenger;

public class DownloadHedgePolicyTest {
    private static int checks;

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    public static void main(String[] args) {
        DownloadHedgePolicy policy = new DownloadHedgePolicy();
        check(!policy.isStraggler(60_000), "nothing is a straggler before any chunk has completed");

        policy.onChunkCompleted(100, 2);
        check(policy.stragglerDelayMs() == DownloadHedgePolicy.MIN_DELAY_MS, "fast chunks clamp to the minimum delay");
        check(!policy.isStraggler(1999) && policy.isStraggler(2000), "straggler boundary follows the delay");

        DownloadHedgePolicy slow = new DownloadHedgePolicy();
        slow.onChunkCompleted(10000, 0);
        check(slow.stragglerDelayMs() == DownloadHedgePolicy.MAX_DELAY_MS, "slow networks clamp below tgnet's 25 s socket idle timeout");

        DownloadHedgePolicy mid = new DownloadHedgePolicy();
        mid.onChunkCompleted(1000, 0);
        check(mid.stragglerDelayMs() == 3000, "delay is 3x the average chunk time");
        mid.onChunkCompleted(1800, 0);
        check(mid.stragglerDelayMs() == 3300, "average follows new samples gradually");
        mid.onChunkCompleted(-5, 0);
        check(mid.stragglerDelayMs() == 3300, "negative durations are ignored");

        DownloadHedgePolicy tiny = new DownloadHedgePolicy();
        tiny.onChunkCompleted(0, 0);
        tiny.onChunkCompleted(0, 0);
        check(tiny.stragglerDelayMs() == DownloadHedgePolicy.MIN_DELAY_MS, "zero-length samples never reset the policy to 'no samples'");

        DownloadHedgePolicy pick = new DownloadHedgePolicy();
        check(pick.pickConnection(new int[]{3}, 0, -1) == -1, "a single socket cannot be hedged");
        check(pick.pickConnection(new int[]{2, 0, 1, 0}, 1, -1) == 3, "skips the straggler's socket and picks the least loaded");
        check(pick.pickConnection(new int[]{1, 1, 1, 1}, 0, -1) == 1, "ties fall back to the first other socket");
        pick.onChunkCompleted(500, 2);
        check(pick.pickConnection(new int[]{1, 1, 1, 1}, 0, -1) == 2, "ties prefer the socket that last delivered");
        check(pick.pickConnection(new int[]{1, 0, 1, 1}, 0, -1) == 1, "load beats the healthy-socket preference");
        check(pick.pickConnection(new int[]{1, 1, 1, 1}, 2, -1) != 2, "never picks the straggler's own socket");
        check(pick.pickConnection(new int[]{1, 0, 1, 1}, 0, 1) == 2, "skips the socket where an earlier duplicate failed");
        check(pick.pickConnection(new int[]{0, 0}, 0, 1) == -1, "no socket left after excluding straggler and failed sockets");

        System.out.println("Download hedge policy: " + checks + " checks passed");
    }
}

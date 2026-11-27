package org.telegram.messenger;

/**
 * Decides when an in-flight file chunk is a straggler and which download socket should carry
 * a duplicate ("hedged") request for it.
 *
 * Near the end of a download, and whenever the earliest missing chunk blocks sequential writes,
 * a single chunk stuck on a slow or half-dead socket holds up the whole file. tgnet does not
 * resend it while that socket stays connected, so the wait lasts until the socket recovers or
 * its 25 second idle timeout reconnects it. Re-requesting the chunk on another socket once it is
 * clearly slower than its siblings lets whichever copy arrives first complete the file.
 */
final class DownloadHedgePolicy {
    static final long MIN_DELAY_MS = 2000;
    static final long MAX_DELAY_MS = 12000;
    static final int DELAY_MULTIPLIER = 3;
    static final int MAX_IN_FLIGHT = 4;
    static final int MAX_PER_CHUNK = 2;

    private long averageChunkMs;
    private int lastHealthyConnection = -1;

    /** Records a successful chunk, timed from when it was written to the socket. */
    void onChunkCompleted(long durationMs, int connectionIndex) {
        if (durationMs < 0) {
            return;
        }
        // Never 0 once a sample exists: 0 means "no samples yet".
        averageChunkMs = Math.max(1, averageChunkMs == 0 ? durationMs : (averageChunkMs * 7 + durationMs) / 8);
        lastHealthyConnection = connectionIndex;
    }

    /** No delay is known, and nothing is a straggler, until one chunk has completed. */
    long stragglerDelayMs() {
        if (averageChunkMs == 0) {
            return Long.MAX_VALUE;
        }
        return Math.max(MIN_DELAY_MS, Math.min(MAX_DELAY_MS, averageChunkMs * DELAY_MULTIPLIER));
    }

    boolean isStraggler(long ageMs) {
        return ageMs >= stragglerDelayMs();
    }

    /**
     * Picks the least-loaded socket other than the straggler's own, preferring the socket that
     * most recently delivered a chunk. A socket on which an earlier duplicate failed is skipped too.
     * Returns -1 when no other socket exists.
     */
    int pickConnection(int[] inFlightPerConnection, int stragglerConnection, int failedConnection) {
        int best = -1;
        for (int i = 0; i < inFlightPerConnection.length; i++) {
            if (i == stragglerConnection || i == failedConnection) {
                continue;
            }
            if (best == -1 || inFlightPerConnection[i] < inFlightPerConnection[best]
                    || inFlightPerConnection[i] == inFlightPerConnection[best] && i == lastHealthyConnection) {
                best = i;
            }
        }
        return best;
    }
}

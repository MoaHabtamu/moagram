package org.telegram.messenger;

/** Fixed intervals, matching Telegram's repeat picker (months are fixed day counts). */
public final class RepeatSchedule {
    public static final int QUEUE_SIZE = 7;
    public static final int QUEUE_LIMIT = 90; // Leave room for ordinary scheduled messages.
    public static final int MIN_LEAD_SECONDS = 60;
    public static final long HORIZON_SECONDS = 364L * 86400;

    private RepeatSchedule() {}

    public static boolean isValidInterval(int seconds, boolean testBackend) {
        return seconds == 86400 || seconds == 7 * 86400 || seconds == 14 * 86400
                || seconds == 30 * 86400 || seconds == 91 * 86400
                || seconds == 182 * 86400 || seconds == 365 * 86400
                || testBackend && (seconds == 60 || seconds == 300);
    }

    /** Skip missed occurrences, retaining the original cadence; never send a backlog now. */
    public static long nextFutureDate(long nextDate, int interval, long now) {
        if (interval <= 0) throw new IllegalArgumentException("Invalid repeat interval");
        long earliest = now + MIN_LEAD_SECONDS;
        if (nextDate < earliest) {
            nextDate += ((earliest - nextDate + interval - 1) / interval) * interval;
        }
        return nextDate;
    }

    public static boolean canQueue(long date, long now, int seriesCount, int dialogCount) {
        return date >= now + MIN_LEAD_SECONDS && date <= now + HORIZON_SECONDS
                && date <= Integer.MAX_VALUE && seriesCount < QUEUE_SIZE && dialogCount < QUEUE_LIMIT;
    }
}

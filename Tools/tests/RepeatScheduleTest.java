import org.telegram.messenger.RepeatSchedule;

/** Run with javac/java, without an Android runtime or a logged-in account. */
public class RepeatScheduleTest {
    public static void main(String[] args) {
        long now = 1800000000L;
        int day = 86400;
        equal(now + day, RepeatSchedule.nextFutureDate(now + day, day, now));
        equal(now + day, RepeatSchedule.nextFutureDate(now, day, now));
        equal(now + 60, RepeatSchedule.nextFutureDate(now + 60, day, now));
        equal(now + 59 + day, RepeatSchedule.nextFutureDate(now + 59, day, now));
        equal(now + 1234, RepeatSchedule.nextFutureDate(now - 400L * day + 1234, day, now));
        equal(now + 365L * day, RepeatSchedule.nextFutureDate(now, 365 * day, now));
        check(RepeatSchedule.canQueue(now + day, now, 6, 89));
        check(!RepeatSchedule.canQueue(now + day, now, 7, 89));
        check(!RepeatSchedule.canQueue(now + day, now, 6, 90));
        check(!RepeatSchedule.canQueue(now + 59, now, 0, 0));
        check(RepeatSchedule.canQueue(now + RepeatSchedule.HORIZON_SECONDS, now, 0, 0));
        check(!RepeatSchedule.canQueue(now + RepeatSchedule.HORIZON_SECONDS + 1, now, 0, 0));
        check(!RepeatSchedule.canQueue((long) Integer.MAX_VALUE + 1, Integer.MAX_VALUE - 100L, 0, 0));
        check(RepeatSchedule.isValidInterval(day, false));
        check(RepeatSchedule.isValidInterval(365 * day, false));
        check(!RepeatSchedule.isValidInterval(0, false));
        check(!RepeatSchedule.isValidInterval(-day, false));
        check(!RepeatSchedule.isValidInterval(60, false));
        check(RepeatSchedule.isValidInterval(60, true));
        check(RepeatSchedule.isValidInterval(300, true));
        try {
            RepeatSchedule.nextFutureDate(now, 0, now);
            throw new AssertionError("Zero interval accepted");
        } catch (IllegalArgumentException expected) {}
        System.out.println("RepeatSchedule: 21 checks passed");
    }

    private static void equal(long expected, long actual) {
        if (expected != actual) throw new AssertionError("Expected " + expected + ", got " + actual);
    }

    private static void check(boolean condition) {
        if (!condition) throw new AssertionError("Schedule policy check failed");
    }
}

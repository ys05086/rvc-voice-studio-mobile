package dev.virtualmic;

import org.junit.Test;
import static org.junit.Assert.*;

public class BridgeTimingTest {
    @Test public void continuousSessionSurvivesFiveMinutesAndMultipleDays() {
        long start = 1000000000L;
        assertTrue(start + 301_000_000_000L < BridgeTiming.deadline(start, 0));
        assertTrue(start + 7L * 86400 * 1000000000 < BridgeTiming.deadline(start, 0));
        assertEquals(start + 300_000_000_000L, BridgeTiming.deadline(start, 300));
    }
    @Test public void frameValidationFollowsCaptureBeyondOneHour() {
        long captured = 48000L * 7200;
        assertTrue(BridgeTiming.validPosition(captured - 24000, captured));
        assertTrue(BridgeTiming.validPosition(-24000, 0));
        assertFalse(BridgeTiming.validPosition(-48001, 0));
        assertFalse(BridgeTiming.validPosition(captured + 48001, captured));
    }
    @Test public void latencyWindowRetainsOnlyRecentSamples() {
        BridgeTiming.Window w = new BridgeTiming.Window(100);
        for (int i = 0; i < 100000; i++) w.add(i);
        w.add(Double.NaN);
        assertEquals(100, w.size());
        assertEquals(99900, w.percentile(0), 0);
        assertEquals(99999, w.percentile(1), 0);
    }
}

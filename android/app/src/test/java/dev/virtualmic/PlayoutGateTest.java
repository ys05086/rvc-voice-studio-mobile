package dev.virtualmic;

import org.junit.Test;
import static org.junit.Assert.*;

public class PlayoutGateTest {
    @Test public void delayedFirstInferenceDoesNotStartEmptyPlayback() {
        PlayoutGate gate = new PlayoutGate(600, 4800);
        assertFalse(gate.canTake(500_000_000L, 12960));
        assertFalse(gate.canTake(900_000_000L, 0));
        assertTrue(gate.canTake(950_000_000L, 12960));
        assertEquals(0, gate.recoveries());
    }
    @Test public void underrunRefillsBeforeResumingInsteadOfRepeatedTinyBursts() {
        PlayoutGate gate = new PlayoutGate(0, 4800);
        assertTrue(gate.canTake(0, 12960));
        assertFalse(gate.canTake(0, 120));
        assertTrue(gate.hasPlayed());
        assertFalse(gate.canTake(0, 480));
        assertFalse(gate.canTake(0, 2400));
        assertTrue(gate.canTake(0, 4800));
        assertEquals(1, gate.recoveries());
    }
    @Test public void newInputCannotReusePreviousSessionReadiness() {
        PlayoutGate gate = new PlayoutGate(600, 4800);
        assertTrue(gate.canTake(900_000_000L, 12960));
        gate.reset();
        assertFalse(gate.hasPlayed());
        assertFalse(gate.canTake(900_000_000L, 0));
        assertTrue(gate.canTake(950_000_000L, 12960));
    }
}

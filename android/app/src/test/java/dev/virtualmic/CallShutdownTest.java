package dev.virtualmic;

import org.junit.Test;
import static org.junit.Assert.*;

public class CallShutdownTest {
    static class Fake implements CallShutdown.Controls {
        boolean present = true, failRequest, failRead, failBlock;
        int disconnectAfter = 2, pauses;
        String events = "";
        public boolean callPresent() throws Exception {
            if (failRead) throw new Exception("read failed");
            return present;
        }
        public void blockOriginal() throws Exception {
            events += "block ";
            if (failBlock) throw new Exception("mute failed");
        }
        public void endCall() throws Exception {
            events += "end ";
            if (failRequest) throw new Exception("denied");
        }
        public void pause() { if (++pauses >= disconnectAfter) present = false; }
        public void restore() { events += "restore "; }
    }
    @Test public void faultDisconnectsBeforeRestoring() throws Exception {
        Fake call = new Fake();
        CallShutdown.finish(call, false);
        assertEquals("block end restore ", call.events);
        assertFalse(call.present);
    }
    @Test public void explicitStopKeepsCallAndRestores() throws Exception {
        Fake call = new Fake();
        CallShutdown.finish(call, true);
        assertEquals("restore ", call.events);
        assertTrue(call.present);
    }
    @Test public void rejectedDisconnectDoesNotRestore() {
        Fake call = new Fake(); call.failRequest = true;
        assertThrows(Exception.class, () -> CallShutdown.finish(call, false));
        assertEquals("block end ", call.events);
    }
    @Test public void acceptedButStuckDisconnectDoesNotRestore() {
        Fake call = new Fake(); call.disconnectAfter = 100;
        assertThrows(IllegalStateException.class, () -> CallShutdown.finish(call, false));
        assertEquals("block end ", call.events);
    }
    @Test public void unreadableCallStateDoesNotRestore() {
        Fake call = new Fake(); call.failRead = true;
        assertThrows(Exception.class, () -> CallShutdown.finish(call, false));
        assertEquals("", call.events);
    }
    @Test public void failedMuteStillRequestsHangupAndDoesNotRestore() {
        Fake call = new Fake(); call.failBlock = true;
        assertThrows(Exception.class, () -> CallShutdown.finish(call, false));
        assertEquals("block end ", call.events);
    }
    @Test public void endedCallRestoresWithoutAnotherHangup() throws Exception {
        Fake call = new Fake(); call.present = false;
        CallShutdown.finish(call, false);
        assertEquals("restore ", call.events);
    }
}

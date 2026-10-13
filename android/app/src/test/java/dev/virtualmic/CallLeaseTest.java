package dev.virtualmic;

import org.junit.Test;
import static org.junit.Assert.*;

public class CallLeaseTest {
    @Test public void stalledWorkerExpiresAtDeadline() {
        CallLease lease = new CallLease(100);
        assertFalse(lease.expired(100 + CallLease.TIMEOUT_NS - 1));
        assertTrue(lease.expired(100 + CallLease.TIMEOUT_NS));
    }
    @Test public void heartbeatExtendsLeaseButEofCannotBeRevived() {
        CallLease lease = new CallLease(0);
        lease.beat(2_000_000_000L);
        assertFalse(lease.expired(4_000_000_000L));
        lease.end(); lease.beat(4_000_000_001L);
        assertTrue(lease.expired(4_000_000_002L));
    }
    @Test public void onlyExplicitStopBeforeFailureIsNormal() {
        CallLease lease = new CallLease(0);
        lease.stop(100);
        lease.end();
        assertTrue(lease.normalStop());
        assertTrue(lease.expired(101));
    }
    @Test public void eofCannotBecomeNormalStop() {
        CallLease lease = new CallLease(0);
        lease.end(); lease.stop(100);
        assertFalse(lease.normalStop());
    }
    @Test public void lateHeartbeatAndStopCannotReviveTimedOutLease() {
        CallLease lease = new CallLease(0);
        lease.beat(CallLease.TIMEOUT_NS);
        lease.stop(CallLease.TIMEOUT_NS + 1);
        assertTrue(lease.expired(CallLease.TIMEOUT_NS + 2));
        assertFalse(lease.normalStop());
    }
}

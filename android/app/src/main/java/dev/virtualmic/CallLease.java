package dev.virtualmic;

/** Only an explicit stop received before failure allows the original voice to return. */
final class CallLease {
    static final long TIMEOUT_NS = 3_000_000_000L;
    private long last;
    private boolean ended, normalStop;
    CallLease(long now) { last = now; }
    synchronized void beat(long now) { if (!expired(now)) last = now; }
    synchronized void end() { ended = true; }
    synchronized void stop(long now) {
        if (!expired(now)) { normalStop = true; ended = true; }
    }
    synchronized boolean normalStop() { return normalStop; }
    synchronized boolean expired(long now) {
        if (now - last >= TIMEOUT_NS) ended = true;
        return ended;
    }
}

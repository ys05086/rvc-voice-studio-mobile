package dev.virtualmic;

/** Do not spend an output block until fresh converted audio is actually available. */
public final class PlayoutGate {
    private final long delayNanos;
    private final int reserveFrames;
    private boolean ready, played;
    private int recoveries;

    public PlayoutGate(int delayMs, int reserveFrames) {
        if (delayMs < 0 || reserveFrames < 480) throw new IllegalArgumentException();
        delayNanos = delayMs * 1_000_000L;
        this.reserveFrames = reserveFrames;
    }
    public boolean canTake(long elapsedNanos, int queuedFrames) {
        if (ready && queuedFrames < 480) { ready = false; recoveries++; }
        if (!ready && elapsedNanos >= delayNanos && queuedFrames >= reserveFrames) ready = true;
        if (ready) played = true;
        return ready;
    }
    public void reset() { ready = false; played = false; }
    public boolean isReady() { return ready; }
    public boolean hasPlayed() { return played; }
    public int recoveries() { return recoveries; }
}

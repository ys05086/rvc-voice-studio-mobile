package dev.virtualmic;

import java.util.Arrays;

/** Time and diagnostic storage must remain bounded independently of session length. */
public final class BridgeTiming {
    private BridgeTiming() {}
    public static long deadline(long startNanos, int seconds) {
        if (seconds < 0) throw new IllegalArgumentException("Negative duration");
        return seconds == 0 ? Long.MAX_VALUE : Math.addExact(startNanos, seconds * 1_000_000_000L);
    }
    public static boolean validPosition(long position, long suppliedFrames) {
        return position >= -48000 && position <= suppliedFrames + 48000L;
    }
    public static final class Window {
        private final double[] values;
        private int count, next;
        public Window(int capacity) {
            if (capacity < 1) throw new IllegalArgumentException("Empty window");
            values = new double[capacity];
        }
        public void add(double value) {
            if (!Double.isFinite(value)) return;
            values[next] = value; next = (next + 1) % values.length;
            count = Math.min(count + 1, values.length);
        }
        public int size() { return count; }
        public double percentile(double fraction) {
            if (count == 0) return Double.NaN;
            double[] sorted = Arrays.copyOf(values, count);
            Arrays.sort(sorted);
            return sorted[(int) Math.round((count - 1) * fraction)];
        }
    }
}

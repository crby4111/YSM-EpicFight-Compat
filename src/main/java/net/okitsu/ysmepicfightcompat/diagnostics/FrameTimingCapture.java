package net.okitsu.ysmepicfightcompat.diagnostics;

import java.util.Arrays;

/** Bounded frame-interval capture. A menu/loading gap invalidates a steady-scene benchmark. */
public final class FrameTimingCapture {
    private static final int MAX_SAMPLES = 250_000;
    private final long startedAt, warmupNanos, sampleNanos;
    private final double[] milliseconds = new double[MAX_SAMPLES];
    private int count, interruptedFrames;
    private long previous;
    private boolean hasPrevious, complete, overflow;

    public FrameTimingCapture(long startedAt, int warmupSeconds, int sampleSeconds) {
        if (warmupSeconds < 0 || warmupSeconds > 120 || sampleSeconds < 1 || sampleSeconds > 600)
            throw new IllegalArgumentException("Invalid capture duration");
        this.startedAt = startedAt;
        warmupNanos = warmupSeconds * 1_000_000_000L;
        sampleNanos = sampleSeconds * 1_000_000_000L;
    }

    public void frame(long now, boolean stableScene) {
        if (complete) return;
        // nanoTime has an arbitrary origin and may wrap. Compare elapsed durations,
        // not absolute timestamps, so the warm-up and deadline survive that wrap.
        long elapsed = now - startedAt;
        if (elapsed < 0) {
            interruptedFrames++;
            hasPrevious = false;
            return;
        }
        if (elapsed < warmupNanos) {
            hasPrevious = false;
            return;
        }
        boolean reachedEnd = elapsed >= warmupNanos + sampleNanos;
        if (!stableScene) {
            interruptedFrames++;
            hasPrevious = false;
            complete = reachedEnd;
            return;
        }
        if (hasPrevious) {
            long interval = now - previous;
            if (interval <= 0) {
                interruptedFrames++;
            } else if (count == milliseconds.length) {
                overflow = true;
                complete = true;
                return;
            } else {
                milliseconds[count++] = interval / 1_000_000.0;
            }
        }
        previous = now;
        hasPrevious = true;
        complete |= reachedEnd;
    }

    public boolean complete() {
        return complete;
    }

    public int samples() {
        return count;
    }

    public Result result(boolean stoppedEarly) {
        double[] raw = Arrays.copyOf(milliseconds, count);
        double[] sorted = raw.clone();
        Arrays.sort(sorted);
        int slow = 0;
        for (double value : raw) if (value >= 100) slow++;
        double median = count == 0 ? 0 : (sorted[(count - 1) / 2] + sorted[count / 2]) / 2;
        double p95 = count == 0 ? 0 : sorted[Math.max(0, (int) Math.ceil(count * .95) - 1)];
        return new Result(complete && !stoppedEarly && !overflow && interruptedFrames == 0 && count >= 100,
                stoppedEarly, overflow, interruptedFrames, count, median, p95, slow, raw);
    }

    public record Result(boolean valid, boolean stoppedEarly, boolean overflow,
                         int interruptedFrames, int samples, double medianMilliseconds,
                         double p95Milliseconds, int framesAtLeast100ms, double[] frameMilliseconds) {
        public Result {
            frameMilliseconds = frameMilliseconds.clone();
        }

        @Override public double[] frameMilliseconds() {
            return frameMilliseconds.clone();
        }
    }
}

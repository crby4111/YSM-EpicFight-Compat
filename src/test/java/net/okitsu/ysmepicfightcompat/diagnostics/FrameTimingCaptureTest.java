package net.okitsu.ysmepicfightcompat.diagnostics;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FrameTimingCaptureTest {
    @Test
    void warmupIsExcludedEvenSamplePercentilesAreCorrectAndDeadlineFrameSurvivesNanoTimeWrap() {
        long startedAt = Long.MAX_VALUE - 500_000_000L;
        var capture = new FrameTimingCapture(startedAt, 1, 6);
        capture.frame(startedAt + 500_000_000L, false);
        long now = startedAt + 1_000_000_000L;
        capture.frame(now, true);
        for (int intervalMilliseconds = 1; intervalMilliseconds <= 99; intervalMilliseconds++) {
            now += intervalMilliseconds * 1_000_000L;
            capture.frame(now, true);
        }
        capture.frame(startedAt + 7_000_000_000L, true);
        var result = capture.result(false);
        assertTrue(result.valid());
        assertEquals(100, result.samples());
        assertEquals(50.5, result.medianMilliseconds());
        assertEquals(95, result.p95Milliseconds());
        assertEquals(1, result.framesAtLeast100ms());
        assertEquals(1_050, result.frameMilliseconds()[99]);
    }

    @Test
    void menuGapInvalidatesRunAndDoesNotBecomeAFakeHugeFrame() {
        var capture = new FrameTimingCapture(0, 0, 2);
        capture.frame(0, true);
        capture.frame(10_000_000, true);
        capture.frame(20_000_000, false);
        capture.frame(900_000_000, true);
        capture.frame(910_000_000, true);
        for (long now = 920_000_000; now <= 2_000_000_000L; now += 10_000_000)
            capture.frame(now, true);
        var result = capture.result(false);
        assertFalse(result.valid());
        assertEquals(1, result.interruptedFrames());
        assertEquals(111, result.samples());
        assertEquals(10, result.medianMilliseconds());
        assertEquals(10, result.p95Milliseconds());
        assertEquals(0, result.framesAtLeast100ms());
    }

    @Test
    void stopAndTooFewFramesCannotPassAndResultOwnsItsSamples() {
        var capture = new FrameTimingCapture(0, 0, 1);
        capture.frame(0, true);
        capture.frame(150_000_000, true);
        var result = capture.result(true);
        assertFalse(result.valid());
        assertEquals(1, result.framesAtLeast100ms());
        double[] values = result.frameMilliseconds();
        values[0] = 0;
        assertEquals(150, result.frameMilliseconds()[0]);
        capture.frame(1_000_000_000L, true);
        assertFalse(capture.result(false).valid());
    }

    @Test
    void sampleOverflowInvalidatesTheRunAndStaysWithinItsMemoryBound() {
        var capture = new FrameTimingCapture(0, 0, 600);
        capture.frame(0, true);
        for (int index = 1; index <= 250_001 && !capture.complete(); index++)
            capture.frame(index * 1_000_000L, true);

        var result = capture.result(false);
        assertTrue(result.overflow());
        assertFalse(result.valid());
        assertEquals(250_000, result.samples());
        assertEquals(250_000, result.frameMilliseconds().length);
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

public final class Nintendo3DsPerformanceCollectorTest {
    @Test
    public void reportsBoundedAggregatePercentilesAndCanReset() {
        Nintendo3DsPerformanceCollector collector = new Nintendo3DsPerformanceCollector();
        Nintendo3DsCoreFrameReport report = frameReport();
        for (long millis : new long[]{10, 20, 30, 40, 50}) {
            collector.recordFrame(millis * 1_000_000L, report);
        }

        Nintendo3DsPerformanceSnapshot snapshot = collector.snapshot(
                Nintendo3DsPerformanceProfile.BALANCED,
                new Nintendo3DsDevicePerformanceState(123, 2, false),
                new Nintendo3DsRegenerableCachePolicy.Report(1, 10, 2, 20, 3, 30));

        assertEquals(5, snapshot.getMeasuredFrames());
        assertEquals(5, snapshot.getPercentileSampleFrames());
        assertEquals(30.0, snapshot.getAverageFrameMillis(), 0.0);
        assertEquals(30.0, snapshot.getP50FrameMillis(), 0.0);
        assertEquals(50.0, snapshot.getP95FrameMillis(), 0.0);
        assertEquals(50.0, snapshot.getP99FrameMillis(), 0.0);
        assertEquals(50.0, snapshot.getMaximumFrameMillis(), 0.0);
        assertEquals(55.555, snapshot.getEffectiveSpeedPercent(), 0.001);
        assertEquals(2, snapshot.getPresentedFrames());
        assertEquals(0, snapshot.getAudioDroppedFrames());
        assertEquals(123, snapshot.getDeviceState().getProcessPssKilobytes());
        assertEquals(60, snapshot.getCacheReport().getTotalBytes());

        collector.reset();
        Nintendo3DsPerformanceSnapshot reset = collector.snapshot(
                Nintendo3DsPerformanceProfile.PERFORMANCE,
                new Nintendo3DsDevicePerformanceState(0, -1, true),
                Nintendo3DsRegenerableCachePolicy.Report.empty());
        assertEquals(0, reset.getMeasuredFrames());
        assertEquals(0.0, reset.getAverageFrameMillis(), 0.0);
        assertEquals(0.0, reset.getP99FrameMillis(), 0.0);
    }

    @Test
    public void rejectsNonPositiveDuration() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new Nintendo3DsPerformanceCollector().recordFrame(0, frameReport()));
    }

    private static Nintendo3DsCoreFrameReport frameReport() {
        return new Nintendo3DsCoreFrameReport(new String[]{
                "device", "1", "3", "279", "37", "5",
                "2", "2", "1090", "44", "3", "8", "4096", "400", "480",
                "1", "1", "1", "1", "1", "1", "32728", "545", "0", "545",
                "2", "128", "96", "32", "257", "16384", "-8192",
                "16", "12", "-24575", "8192", "82", "16486", "1",
                "4", "24", "60", "0.25", "-0.5", "-0.75", "1.5", "-2.5", "3.5",
                "1", "1", "0", "1", "20", "48000", "1", "4096", "128", "0",
                "3968", "1280", "balanced", "11", "2", "1", "59.831225"
        });
    }
}

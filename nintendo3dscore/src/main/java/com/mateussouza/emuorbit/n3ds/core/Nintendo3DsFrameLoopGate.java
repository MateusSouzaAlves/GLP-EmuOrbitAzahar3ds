// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

/** Admission only: stopping a loop never interrupts or pretends to close its native owner. */
final class Nintendo3DsFrameLoopGate {
    static final class Run {
        private Run() { }
    }

    private Run current;
    private Run latest;

    synchronized Run start() {
        if (current != null) return null;
        current = new Run();
        latest = current;
        return current;
    }

    synchronized boolean isCurrent(Run run) {
        return run != null && current == run;
    }

    synchronized boolean isLatest(Run run) {
        return run != null && latest == run;
    }

    synchronized void stop() {
        current = null;
        latest = null;
    }

    synchronized void finish(Run run) {
        if (current == run) current = null;
        // Keep latest until stop/start so a queued UI failure can still be
        // delivered AFTER finally, but never into a newer run.
    }
}

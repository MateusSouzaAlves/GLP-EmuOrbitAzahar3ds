// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

import android.content.Context;
import android.os.Build;
import android.os.Debug;
import android.os.PowerManager;

/** Privacy-preserving local device sample used by the Nintendo 3DS performance gate. */
public final class Nintendo3DsDevicePerformanceState {
    public static final int THERMAL_STATUS_UNAVAILABLE = -1;

    private final long processPssKilobytes;
    private final int thermalStatus;
    private final boolean powerSaveMode;

    Nintendo3DsDevicePerformanceState(
            long processPssKilobytes,
            int thermalStatus,
            boolean powerSaveMode) {
        this.processPssKilobytes = Math.max(0, processPssKilobytes);
        this.thermalStatus = Math.max(THERMAL_STATUS_UNAVAILABLE, thermalStatus);
        this.powerSaveMode = powerSaveMode;
    }

    static Nintendo3DsDevicePerformanceState capture(Context context) {
        int thermal = THERMAL_STATUS_UNAVAILABLE;
        boolean powerSave = false;
        if (context != null) {
            PowerManager powerManager = context.getSystemService(PowerManager.class);
            if (powerManager != null) {
                powerSave = powerManager.isPowerSaveMode();
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    thermal = powerManager.getCurrentThermalStatus();
                }
            }
        }
        return new Nintendo3DsDevicePerformanceState(Debug.getPss(), thermal, powerSave);
    }

    public long getProcessPssKilobytes() { return processPssKilobytes; }
    public int getThermalStatus() { return thermalStatus; }
    public boolean isPowerSaveMode() { return powerSaveMode; }
}

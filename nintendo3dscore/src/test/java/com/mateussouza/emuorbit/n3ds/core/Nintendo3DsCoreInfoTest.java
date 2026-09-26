// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Set;

public final class Nintendo3DsCoreInfoTest {
    @Test
    public void parsesValidatedBootstrapEvidence() {
        Nintendo3DsCoreInfo info = new Nintendo3DsCoreInfo(new String[]{
                "Azahar", "fbd3fb0", "3ds|3DSX|cci|cxi|app",
                "1", "1", "4", "1", "1", "1"
        });

        assertEquals("Azahar", info.getLibraryName());
        assertEquals("fbd3fb0", info.getLibraryVersion());
        assertEquals(Set.of("3ds", "3dsx", "cci", "cxi", "app"),
                info.getValidExtensions());
        assertTrue(info.needsFullPath());
        assertEquals(1, info.getApiVersion());
        assertEquals(4, info.getEnvironmentCallbackCount());
        assertTrue(info.isCoreOptionsRegistered());
        assertTrue(info.isControllerInfoRegistered());
        assertTrue(info.isVfsInterfaceRequested());
    }

    @Test
    public void extensionsCannotBeMutatedByConsumers() {
        Nintendo3DsCoreInfo info = new Nintendo3DsCoreInfo(new String[]{
                "Azahar", "1", "3ds|3dsx|cci|cxi",
                "1", "1", "3", "1", "1", "0"
        });

        assertThrows(UnsupportedOperationException.class,
                () -> info.getValidExtensions().add("zip"));
        assertFalse(info.isVfsInterfaceRequested());
    }

    @Test
    public void rejectsMalformedNativeWireValues() {
        assertThrows(IllegalArgumentException.class,
                () -> new Nintendo3DsCoreInfo(new String[]{"Azahar"}));
        assertThrows(IllegalArgumentException.class,
                () -> new Nintendo3DsCoreInfo(new String[]{
                        "Azahar", "1", "3ds", "maybe", "1", "1", "1", "1", "0"
                }));
    }
}

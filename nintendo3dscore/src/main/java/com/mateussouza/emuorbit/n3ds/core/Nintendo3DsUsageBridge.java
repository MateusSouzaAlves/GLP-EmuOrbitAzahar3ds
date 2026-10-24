// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

import androidx.activity.ComponentActivity;
import java.lang.reflect.Method;

/** Optional content-free base service. Missing in the self-contained core harness. */
final class Nintendo3DsUsageBridge {
    private volatile Object handle;
    private volatile Method event;
    Nintendo3DsUsageBridge(ComponentActivity activity) {
        try {
            Class<?> service = Class.forName("com.mateussouza.emuorbit.advance.data.analytics.UsageAnalytics");
            handle = service.getMethod("attachForFeature",ComponentActivity.class).invoke(null,activity);
            event = service.getMethod("featureEvent",Object.class,int.class);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError absent) { handle = null;event = null; }
    }
    void frame() { send(0); }
    void foreground(boolean active) { send(active ? 1 : 2); }
    void running(boolean active) { send(active ? 3 : 4); }
    void failed() { send(5); }
    void finish() { send(6); }
    private void send(int action) {
        Method method = event;Object target = handle;
        if (method == null || target == null) return;
        try { method.invoke(null,target,action); }
        catch (ReflectiveOperationException | RuntimeException | LinkageError absent) { event = null;handle = null; }
    }
}

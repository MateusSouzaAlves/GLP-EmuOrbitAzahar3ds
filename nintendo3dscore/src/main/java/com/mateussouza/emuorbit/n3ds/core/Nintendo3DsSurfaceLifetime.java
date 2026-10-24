// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Lifetime of an APP-OWNED render target, never a borrowed SurfaceView resource.
 * A lease is acknowledged only after actual native producer close / PixelCopy completion.
 * Caller timeout, Activity destruction and executor shutdown are NOT acknowledgements.
 * The release closure must strongly own the Java Surface and its compositor consumer. */
final class Nintendo3DsSurfaceLifetime {
    // Retain even after a failed/stalled native close loses its Java host. GC must not
    // release the consumer while a producer may still use its ANativeWindow reference.
    private static final Set<Nintendo3DsSurfaceLifetime> RETAINED_RETIREMENTS=ConcurrentHashMap.newKeySet();
    private final Runnable releaseOwnedResources;
    private int borrowers;
    private boolean retired,releaseClaimed,released;
    private Throwable releaseFailure;

    Nintendo3DsSurfaceLifetime(Runnable releaseOwnedResources) {
        this.releaseOwnedResources=Objects.requireNonNull(releaseOwnedResources);
    }

    synchronized Lease borrow() {
        if(retired)throw new IllegalStateException("Cannot borrow a retired 3DS render target");
        borrowers=Math.addExact(borrowers,1);
        return new Lease();
    }

    void retire() {
        boolean releaseNow;
        synchronized(this) {
            if(retired)return;
            retired=true;
            RETAINED_RETIREMENTS.add(this);
            releaseNow=claimRelease();
        }
        if(releaseNow)release();
    }

    synchronized boolean isRetired() { return retired; }
    synchronized boolean isReleased() { return released; }
    synchronized Throwable getReleaseFailure() { return releaseFailure; }

    private boolean claimRelease() {
        if(!retired||borrowers!=0||releaseClaimed)return false;
        releaseClaimed=true; // Never retry possibly partially released native handles.
        return true;
    }

    private void release() {
        try { releaseOwnedResources.run(); }
        catch(RuntimeException|Error failure) {
            synchronized(this) {releaseFailure=failure;}
            throw failure; // Retain the closure/consumer; never convert failure into success.
        }
        synchronized(this) {released=true;}
        RETAINED_RETIREMENTS.remove(this);
    }

    final class Lease implements AutoCloseable {
        private final AtomicBoolean acknowledged=new AtomicBoolean();
        @Override public void close() {
            if(!acknowledged.compareAndSet(false,true))return;
            boolean releaseNow;
            synchronized(Nintendo3DsSurfaceLifetime.this) {
                borrowers--;
                releaseNow=claimRelease();
            }
            if(releaseNow)release();
        }
    }
}

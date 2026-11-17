/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.nativeext;

import java.util.Objects;

/** The permission gate of one synchronous JNI-to-Java callback: the callback must run on the
 * declared thread for the declared owner site, hold a read permit for the touched world state, not
 * request a write, and stay inside the bounded re-entry depth; a violation is refused at the Java
 * entry with a partial fault instead of touching the world unprotected. */
public final class NativeSyncCallbackGate {

    /** One synchronous callback request from the native step. */
    public record Callback(String engineId, long stepOrdinal, String threadName, String ownerSite,
                           boolean readPermit, boolean writeRequested) {

        public Callback {
            Objects.requireNonNull(engineId, "engineId");
            Objects.requireNonNull(threadName, "threadName");
            Objects.requireNonNull(ownerSite, "ownerSite");
        }
    }

    /** The verdict of one callback entry. */
    public enum Verdict {
        ALLOWED,
        THREAD_MISMATCH,
        OWNER_MISMATCH,
        NO_READ_PERMIT,
        WRITE_FORBIDDEN,
        REENTRY_EXCEEDED,
        NOT_LOADED_NA
    }

    private final NativeEngineOwner.Presence presence;
    private final String expectedOwnerSite;
    private final String expectedThread;
    private final int maxReentry;
    private int depth;
    private long refused;
    private boolean partialFault;

    public NativeSyncCallbackGate(NativeEngineOwner.Presence presence, String expectedOwnerSite,
                                  String expectedThread, int maxReentry) {
        if (maxReentry < 0) {
            throw new IllegalArgumentException("a callback reentry bound cannot be negative");
        }
        this.presence = presence;
        this.expectedOwnerSite = expectedOwnerSite;
        this.expectedThread = expectedThread;
        this.maxReentry = maxReentry;
    }

    /** Lets one callback in only inside the declared thread, owner, read permit and reentry bound. */
    public synchronized Verdict enter(Callback callback) {
        Objects.requireNonNull(callback, "callback");
        if (presence != NativeEngineOwner.Presence.LOADED) {
            return Verdict.NOT_LOADED_NA;
        }
        if (!expectedThread.equals(callback.threadName())) {
            refused++;
            return Verdict.THREAD_MISMATCH;
        }
        if (!expectedOwnerSite.equals(callback.ownerSite())) {
            refused++;
            return Verdict.OWNER_MISMATCH;
        }
        if (!callback.readPermit()) {
            refused++;
            partialFault = true;
            return Verdict.NO_READ_PERMIT;
        }
        if (callback.writeRequested()) {
            refused++;
            partialFault = true;
            return Verdict.WRITE_FORBIDDEN;
        }
        if (depth >= maxReentry) {
            refused++;
            return Verdict.REENTRY_EXCEEDED;
        }
        depth++;
        return Verdict.ALLOWED;
    }

    /** Leaves one admitted callback; an unmatched exit is refused. */
    public synchronized boolean exit() {
        if (depth <= 0) {
            return false;
        }
        depth--;
        return true;
    }

    public synchronized int depth() {
        return depth;
    }

    public synchronized long refused() {
        return refused;
    }

    public synchronized boolean partialFault() {
        return partialFault;
    }
}

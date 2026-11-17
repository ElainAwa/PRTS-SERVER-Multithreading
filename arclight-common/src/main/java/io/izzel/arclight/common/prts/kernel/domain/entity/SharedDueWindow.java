/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity;

/**
 * The shared due window of one server tick: the host freezes the rows once, and every owner domain
 * that runs in that same tick takes the very same window instead of the first take consuming it.
 * The window stays valid until the next tick's begin replaces it; a tick without a window answers
 * null. The cache is written and read on the server thread only.
 */
public final class SharedDueWindow {

    private EntityDueCapture.Window shared;

    /** Drops the window of an older tick; a new tick starts without a shared window. */
    public void begin(long serverTick) {
        if (shared != null && shared.serverTick() != serverTick) {
            shared = null;
        }
    }

    /** Publishes the window one tick froze; a repeated publish of the same tick keeps the first. */
    public void publish(EntityDueCapture.Window window) {
        if (window != null && shared == null) {
            shared = window;
        }
    }

    /**
     * Returns the shared window of the given server tick to every caller of that tick. A different
     * tick, or a window that was never published, answers null; the window is never consumed.
     */
    public EntityDueCapture.Window take(long serverTick) {
        return shared != null && shared.serverTick() == serverTick ? shared : null;
    }

    /** Whether one window is published for any tick. */
    public boolean published() {
        return shared != null;
    }

    /** Drops the published window; allowed only at a tick boundary. */
    public void reset() {
        shared = null;
    }
}

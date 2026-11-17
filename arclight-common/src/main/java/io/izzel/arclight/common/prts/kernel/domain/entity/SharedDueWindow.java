/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity;

/** The due window shared by the owner domains of one server tick; server thread only. */
final class SharedDueWindow {

    private EntityDueCapture.Window shared;

    /** Drops a window of an older tick. */
    void begin(long serverTick) {
        if (shared != null && shared.serverTick() != serverTick) {
            shared = null;
        }
    }

    /** Publishes the window one tick froze; the first publish of the tick wins. */
    void publish(EntityDueCapture.Window window) {
        if (window != null && shared == null) {
            shared = window;
        }
    }

    /** Returns the window of that tick to every caller of the tick; never consumed. */
    EntityDueCapture.Window take(long serverTick) {
        return shared != null && shared.serverTick() == serverTick ? shared : null;
    }

    boolean published() {
        return shared != null;
    }

    void reset() {
        shared = null;
    }
}

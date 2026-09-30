/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.sites;

/**
 * Which thread a write attempt came from.
 *
 * <p>The server thread is the host execution site: it owns the world it ticks, so its local writes
 * take the short path. Every other thread is a worker for this purpose, whether the host started it
 * or a mod did.</p>
 */
public enum ThreadOrigin {

    /** The thread the host ticks the server on. */
    MAIN("main"),
    /** Any other thread. */
    WORKER("worker");

    private final String key;

    ThreadOrigin(String key) {
        this.key = key;
    }

    /** @return the stable name this origin is published under */
    public String key() {
        return key;
    }
}

/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.sites;

/** Which thread a write attempt came from. The server thread is the host execution site: it owns
 * the world it ticks, so its local writes take the short path. */
public enum ThreadOrigin {

    MAIN("main"),
    WORKER("worker");

    private final String key;

    ThreadOrigin(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}

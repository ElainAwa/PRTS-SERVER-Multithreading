/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.sites;

/** The three points a write can be handed over, each with its own counter. */
public enum WritePath {

    KERNEL_COMMIT("kernel_commit"),
    BLOCK_WRITE("block_write"),
    PLATFORM_WRITE("platform_write");

    private final String key;

    WritePath(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}

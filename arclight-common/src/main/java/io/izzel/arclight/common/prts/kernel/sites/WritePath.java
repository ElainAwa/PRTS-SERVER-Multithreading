/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.sites;

/**
 * A place where the world can be written, as far as this layer watches it.
 *
 * <p>The set is deliberately short: the kernel judges writes at the three points a write can be
 * handed over, and each point has its own counter so a reader can tell which one a write came
 * from. Adding a point means adding a row here and a caller at the seam, nothing else.</p>
 */
public enum WritePath {

    /** The commit segment: the one moment a deferred write is applied. */
    KERNEL_COMMIT("kernel_commit"),
    /** The block write path this build already hooks into the level. */
    BLOCK_WRITE("block_write"),
    /** The platform API entry a plugin or an event writes a block through. */
    PLATFORM_WRITE("platform_write");

    private final String key;

    WritePath(String key) {
        this.key = key;
    }

    /** @return the stable name this path is published under */
    public String key() {
        return key;
    }
}

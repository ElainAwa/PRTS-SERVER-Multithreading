/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.support;

public final class PrtsSyntheticLevelWorld {

    private PrtsSyntheticLevelWorld() {
    }

    public static <T> T orNull(T craftWorld) {
        return craftWorld;
    }

    public static <T> T require(T craftWorld, String dimension) {
        if (craftWorld == null) {
            throw new UnsupportedOperationException("World " + dimension
                + " does not have a CraftWorld. This method should not be invoked on this world for any reason.");
        }
        return craftWorld;
    }
}

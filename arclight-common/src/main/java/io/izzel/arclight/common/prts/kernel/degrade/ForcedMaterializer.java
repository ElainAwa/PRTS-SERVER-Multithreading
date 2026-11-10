/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.degrade;

/** The A-ring action behind one wait site: materialize/converge when its bound is exceeded. */
@FunctionalInterface
public interface ForcedMaterializer {
    boolean materialize(String waitSite);
}

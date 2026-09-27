/* SPDX-License-Identifier: GPL-3.0-or-later */
/**
 * PRTS configuration layer.
 *
 * <p>Generates, reads, snapshots and reloads {@code prts-config/**}: one file per category, named
 * after the category, with optional entries disabled by default. A category can also be toggled
 * with {@code -Darclight.prts.<category>=false}, which wins over the files.</p>
 *
 * <p>Settings that belong to a kernel seam are kept in {@code kernel.yml} only; every other
 * category file must stay free of kernel-seam configuration.</p>
 */
package io.izzel.arclight.common.prts.config;

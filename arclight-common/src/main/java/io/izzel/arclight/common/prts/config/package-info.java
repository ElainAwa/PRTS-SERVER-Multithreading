/* SPDX-License-Identifier: GPL-3.0-or-later */
/**
 * The PRTS configuration layer: generates, reads, snapshots and reloads {@code prts-config/**},
 * one file per category. A category can also be toggled with
 * {@code -Darclight.prts.<category>=false}, which wins over the files; kernel-seam settings live
 * in {@code kernel.yml} only.
 */
package io.izzel.arclight.common.prts.config;

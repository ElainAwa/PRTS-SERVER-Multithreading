/* SPDX-License-Identifier: GPL-3.0-or-later */
/**
 * Keyed shared random streams: deterministic per-object streams derived from a stable key, a
 * persistent stream table, and the ownership contract for entity-owned sources. The package does
 * not touch the host level random source and publishes no configuration or reading key; real
 * consumers are wired by the domain work that follows.
 */
package io.izzel.arclight.common.prts.kernel.random;

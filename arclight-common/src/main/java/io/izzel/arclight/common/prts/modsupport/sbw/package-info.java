/* SPDX-License-Identifier: GPL-3.0-or-later */
/**
 * SuperbWarfare interoperability: the projectile, motion, particle and payload patches of that mod.
 *
 * <p>Every class here is loaded only while that mod is present and none of them compiles against a
 * mod type; the four patches share one compatibility helper and one set of switches, so the whole
 * patch set of that mod reads as one unit. Only mixins live here, because Mixin refuses to load
 * them from anywhere else. Design reference: the release-facing mod compatibility rounds.</p>
 */
package io.izzel.arclight.common.prts.modsupport.sbw;

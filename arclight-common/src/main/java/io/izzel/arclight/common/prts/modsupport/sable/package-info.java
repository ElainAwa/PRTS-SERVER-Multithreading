/* SPDX-License-Identifier: GPL-3.0-or-later */
/**
 * Sable interoperability: the physics pipeline, rope and voxel cache patches of that physics mod.
 *
 * <p>The three patches share one concern - the native physics calls and the voxel caches around
 * them must not be entered by two threads at once - and they share the lock and cache helpers that
 * carry it. Keeping them together is what lets a reader follow that one concurrency question
 * across the mod's call sites. Design reference: the native concurrency section.</p>
 */
package io.izzel.arclight.common.prts.modsupport.sable;

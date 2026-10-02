/* SPDX-License-Identifier: GPL-3.0-or-later */
/**
 * Sable interoperability: the physics pipeline, rope and voxel cache patches, which share one
 * concern - the native physics calls and the voxel caches must not be entered by two threads
 * at once - and the lock helpers that carry it.
 */
package io.izzel.arclight.common.prts.modsupport.sable;

/* SPDX-License-Identifier: GPL-3.0-or-later */
/** The save contract: the six-level watermark of one chunk revision with the evidence each level
 * needs, the single writer of one region file that refuses an old encoding, and the manifest vector
 * frozen at the linearization point and verified again by a restart readback. The package is a
 * dormant contract layer: it writes no world and starts no instance. */
package io.izzel.arclight.common.prts.kernel.domain.chunk.save;

/* SPDX-License-Identifier: GPL-3.0-or-later */
/** The block-entity contract: one BE update is bound to the world incarnation, the block position,
 * the host block revision and the BE lifecycle generation; an old update never lands on the object
 * that replaced it, and the inventories, tanks and buffers of a device network are shared resources
 * with a conservation transaction instead of chunk-local slots. */
package io.izzel.arclight.common.prts.kernel.domain.be;

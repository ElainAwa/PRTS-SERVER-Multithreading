/* SPDX-License-Identifier: GPL-3.0-or-later */
/** The deterministic tick contract of the world: scheduled ticks keep their arrival tick and
 * priority order, redstone pulses and neighbour notifications travel as a witness that neither
 * folds a short pulse nor replays a sent notification, and random ticks follow the fixed
 * region/chunk stream order with one shared mutex for a block entity, BE or redstone writer. */
package io.izzel.arclight.common.prts.kernel.domain.tick;

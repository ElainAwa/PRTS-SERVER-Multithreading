/* SPDX-License-Identifier: GPL-3.0-or-later */
/**
 * The platform side of the kernel scaffolding: the bridge, and nothing else.
 *
 * <p>The listener reads the world identities out of the platform tick event and hands them to the
 * shared module; it holds no logic, no state and no decision. That keeps the four pieces testable
 * without the game class path and lets the platform layer be replaced without touching them.</p>
 */
package io.izzel.arclight.neoforge.prts.kernel;

/* SPDX-License-Identifier: GPL-3.0-or-later */
/**
 * The platform side of the kernel scaffolding: the tick and command bridges, and nothing else. The
 * listener reads the world identities out of the platform event and hands them to the shared module,
 * which owns the logic, so these bridges can be replaced without touching it.
 */
package io.izzel.arclight.neoforge.prts.kernel;

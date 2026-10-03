/* SPDX-License-Identifier: GPL-3.0-or-later */
/**
 * The assembly of the domains this build runs: it constructs the entity domain, binds it to the
 * kernel module and hands the module nothing but a handle, so the kernel neither constructs nor
 * names a domain implementation. Separate because construction is the one place that must know both
 * sides, while the kernel and the domain must not know each other. Design reference: the domain
 * assembly section.
 */
package io.izzel.arclight.common.prts.kernel.wiring;

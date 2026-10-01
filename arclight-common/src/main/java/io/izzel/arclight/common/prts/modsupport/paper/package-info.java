/* SPDX-License-Identifier: GPL-3.0-or-later */
/**
 * The plugin-visible Paper API surface: version readouts, inventory opening and a pickup event.
 *
 * <p>Nothing here belongs to a single mod: the patches make plugins compiled against the Paper API
 * resolve and run on this platform, and they are grouped so the whole added surface can be
 * reviewed at once instead of being spread over unrelated packages. Design reference: the Paper
 * API compatibility section of the port screening work.</p>
 */
package io.izzel.arclight.common.prts.modsupport.paper;

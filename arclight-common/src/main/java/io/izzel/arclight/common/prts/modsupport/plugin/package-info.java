/* SPDX-License-Identifier: GPL-3.0-or-later */
/**
 * Plugin loading and reload bridging: chat class preloading and the reload command switch.
 *
 * <p>Both patches run where the server builds its plugin machinery rather than inside a mod, and
 * both are operator-visible: one resolves the chat classes before the plugin loaders are built,
 * the other can take the Bukkit reload commands away. They are not part of the plugin-facing API
 * surface, which is why they are separate from it. Design reference: the plugin lifecycle
 * section of the port screening work.</p>
 */
package io.izzel.arclight.common.prts.modsupport.plugin;

/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.papermc.paper.plugin.bootstrap;

/**
 * Entry a paper plugin would use before it is enabled. This server does not call it.
 */
public interface PluginBootstrap {

    /**
     * Runs before the plugin is enabled.
     */
    void bootstrap(BootstrapContext context);
}

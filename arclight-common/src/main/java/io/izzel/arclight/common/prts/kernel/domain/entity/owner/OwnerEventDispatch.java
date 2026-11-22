/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.owner;

import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.AuthorNagException;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.RegisteredListener;

import java.util.logging.Level;
import java.util.logging.Logger;

/** Repeats the Bukkit fireEvent loop for a declared owner-sync chain on the current thread. It never
 * calls the plugin-manager thread gate; registration order, disabled-plugin skip, cancellation and
 * the per-listener logging/continue semantics follow the host loop. */
public final class OwnerEventDispatch {

    /** What the local loop did: delivered, skipped or failed listeners. */
    public record Outcome(int delivered, int skipped, int failed) {
    }

    private static final Logger FALLBACK = Logger.getLogger("Minecraft");

    private OwnerEventDispatch() {
    }

    /** Dispatches one event the caller's admission already declared owner-sync. */
    public static Outcome dispatch(Event event) {
        if (event == null) {
            return new Outcome(0, 0, 0);
        }
        HandlerList handlers = event.getHandlers();
        if (handlers == null) {
            return new Outcome(0, 0, 0);
        }
        int delivered = 0;
        int skipped = 0;
        int failed = 0;
        for (RegisteredListener registration : handlers.getRegisteredListeners()) {
            Plugin plugin = registration.getPlugin();
            if (plugin == null || !plugin.isEnabled()) {
                skipped++;
                continue;
            }
            try {
                registration.callEvent(event);
                delivered++;
            } catch (AuthorNagException nag) {
                failed++;
                logNag(plugin, nag);
            } catch (Throwable failure) {
                failed++;
                logger().log(Level.SEVERE, "Could not pass event " + event.getEventName()
                    + " to " + pluginName(plugin), failure);
            }
        }
        return new Outcome(delivered, skipped, failed);
    }

    private static void logNag(Plugin plugin, AuthorNagException nag) {
        if (!plugin.isNaggable()) {
            return;
        }
        plugin.setNaggable(false);
        PluginDescriptionFile description = plugin.getDescription();
        logger().log(Level.SEVERE, String.format(
            "Nag author(s): '%s' of '%s' about the following: %s",
            description == null ? "" : description.getAuthors(),
            description == null ? "" : description.getFullName(), nag.getMessage()));
    }

    private static String pluginName(Plugin plugin) {
        PluginDescriptionFile description = plugin == null ? null : plugin.getDescription();
        return description == null ? "" : description.getFullName();
    }

    private static Logger logger() {
        Server server = Bukkit.getServer();
        return server == null ? FALLBACK : server.getLogger();
    }
}

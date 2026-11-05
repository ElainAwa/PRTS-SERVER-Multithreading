/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.rcon;

import org.bukkit.command.RemoteConsoleCommandSender;
import org.bukkit.event.server.RemoteServerCommandEvent;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The real Bukkit remote-command event still carries cancel and rewrite semantics; the owner
 * unit is the only place that constructs it. */
class RconCommandBukkitEventTest {

    @Test
    void realRemoteCommandEventKeepsCancelAndRewrite() {
        RemoteConsoleCommandSender sender = (RemoteConsoleCommandSender) Proxy.newProxyInstance(
            getClass().getClassLoader(), new Class<?>[]{RemoteConsoleCommandSender.class},
            (proxy, method, args) -> null);
        RemoteServerCommandEvent event = new RemoteServerCommandEvent(sender, "say hi");
        assertFalse(event.isCancelled());
        event.setCommand("say rewritten");
        assertEquals("say rewritten", event.getCommand());
        event.setCancelled(true);
        assertTrue(event.isCancelled());
    }
}

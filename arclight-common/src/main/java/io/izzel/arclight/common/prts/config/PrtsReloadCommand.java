/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.config;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;

import java.util.List;

/**
 * {@code /prts reload}: re-reads {@code prts-config/**} without restarting the server.
 *
 * <p>PRTS category: fixes (shared infrastructure, see docs/PRTS-CONVENTIONS.md, C-004).</p>
 */
public class PrtsReloadCommand extends Command {

    /**
     * Creates the command with its label, usage and aliases.
     */
    public PrtsReloadCommand() {
        super("prts", "PRTS base control", "/prts reload", List.of("prtsbase"));
    }

    @Override
    public boolean execute(CommandSender sender, String label, String[] args) {
        if (args.length == 1 && "reload".equalsIgnoreCase(args[0])) {
            PrtsConfigManager.reload();
            sender.sendMessage("[PRTS] prts-config reloaded: " + PrtsConfigManager.snapshot());
            return true;
        }
        sender.sendMessage("[PRTS] usage: /prts reload");
        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
        return args.length == 1 ? List.of("reload") : List.of();
    }
}

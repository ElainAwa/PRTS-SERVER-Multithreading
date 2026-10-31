/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.observe;

import io.izzel.arclight.common.prts.config.PrtsCommandExtension;
import io.izzel.arclight.common.prts.kernel.KernelModule;
import io.izzel.arclight.common.prts.kernel.config.KernelSettings;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.craftbukkit.v.CraftWorld;

import java.util.ArrayList;
import java.util.List;

/** The extension is what keeps the command layer and the kernel apart: the configuration package
 * owns the command and the extension point, this class implements the point, and neither imports
 * the other's internals. */
public final class KernelCommandExtension implements PrtsCommandExtension {

    private static final String NAME = "kernel";
    private static final String SELFTEST = "selftest";
    private static final String RESET = "reset";
    private static final String DRAIN = "drain";
    private static final String PEEK = "peek";
    private static final String PREFIX = "[PRTS] kernel ";

    private KernelCommandExtension() {
    }

    public static PrtsCommandExtension extension() {
        return new KernelCommandExtension();
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public List<String> subcommands() {
        return List.of(DRAIN, PEEK, RESET, SELFTEST);
    }

    @Override
    public List<String> run(List<String> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return export();
        }
        if (arguments.size() == 1 && RESET.equalsIgnoreCase(arguments.get(0))) {
            return reset();
        }
        if (arguments.size() == 1 && SELFTEST.equalsIgnoreCase(arguments.get(0))) {
            return selftest();
        }
        if (arguments.size() == 1 && DRAIN.equalsIgnoreCase(arguments.get(0))) {
            return drain();
        }
        if (arguments.size() == 2 && PEEK.equalsIgnoreCase(arguments.get(0))) {
            return peek(arguments.get(1));
        }
        return List.of("[PRTS] kernel usage: /prts kernel [" + DRAIN + "|" + PEEK
            + " <world> <x> <y> <z>|" + RESET + "|" + SELFTEST + "]");
    }

    @Override
    public List<String> complete(List<String> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return List.of(DRAIN, PEEK, RESET, SELFTEST);
        }
        return List.of();
    }

    @Override
    public List<String> statusLines() {
        return KernelStatusLines.status(KernelModule.instance());
    }

    /** Ops face for a non-loading block peek: the world must already be registered; a missing
     * chunk is answered UNKNOWN, never by loading it. */
    private static List<String> peek(String raw) {
        String[] parts = raw == null ? new String[0] : raw.trim().split("\\s+");
        if (parts.length != 4) {
            return List.of(PREFIX + "peek usage: /prts kernel peek <world> <x> <y> <z>");
        }
        try {
            World world = Bukkit.getWorld(parts[0]);
            if (world == null) {
                return List.of(PREFIX + "peek world=" + parts[0] + " status=no_world result="
                    + PeekBlockService.describe(new PeekBlockService.Result(parts[0], null, null,
                        PeekBlockService.Status.NO_WORLD, "no-world"))
                    + " demand=" + PeekBlockService.demandCount());
            }
            ServerLevel level = ((CraftWorld) world).getHandle();
            BlockPos pos = new BlockPos(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]),
                Integer.parseInt(parts[3]));
            PeekBlockService.Result result = PeekBlockService.peek(level, pos, null);
            return List.of(PREFIX + "peek world=" + result.worldId() + " " + PeekBlockService.describe(result)
                + " demand=" + PeekBlockService.demandCount()
                + " overflow=" + PeekBlockService.demandOverflow());
        } catch (NumberFormatException invalid) {
            return List.of(PREFIX + "peek usage: /prts kernel peek <world> <x> <y> <z>");
        }
    }

    private static List<String> export() {
        List<String> lines = new ArrayList<>();
        lines.add("[PRTS] kernel export (observation requests; zero values are published too)");
        for (String line : KernelReadings.forensicReadings(KernelModule.instance())) {
            lines.add(PREFIX + line);
        }
        return lines;
    }

    /** One session of the command face asks the kernel for a whole reset of its readings. The request
     * is answered where it is made when the command runs on the thread the chain is owned by, and the
     * owner answers it at the next safe point otherwise. */
    private static List<String> reset() {
        if (!KernelSettings.enabled()) {
            return List.of(PREFIX + "reset: the category is off, nothing was asked for");
        }
        KernelModule module = KernelModule.instance();
        KernelModule.ResetRequest request =
            module.requestReadingsReset(KernelModule.HOST_COMMAND_RESET_SOURCE);
        return List.of(PREFIX + "reset source=" + request.source() + " carriedOut="
            + request.carriedOut() + " handedOff=" + request.handedOff() + " reason="
            + request.reason() + " queueDepth=" + module.resetQueueDepth() + " trace="
            + module.commitResetTrace());
    }

    /** One session asks the kernel for its ordered end flush. It is not terminal: the next tick may
     * still report and flush. When claims are disabled the module answers zero without touching a
     * log, and a claim point with nothing buffered answers zero too. */
    private static List<String> drain() {
        int delivered = KernelModule.instance().drainClaimReports();
        return List.of(PREFIX + "drain delivered=" + delivered
            + " (end flush; non-terminal; 0 when claims are off or no reports are buffered)");
    }

    private static List<String> selftest() {
        List<String> lines = new ArrayList<>();
        for (String line : KernelSelfCheck.run()) {
            lines.add(PREFIX + line);
        }
        return lines;
    }
}

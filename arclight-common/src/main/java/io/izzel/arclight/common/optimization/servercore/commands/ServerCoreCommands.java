/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 *
 * This file adapts code from ServerCore by Wesley1808
 * (https://github.com/Wesley1808/ServerCore), licensed under GPL-3.0.
 * Original code Copyright (c) Wesley1808.
 */

package io.izzel.arclight.common.optimization.servercore.commands;

import com.mojang.brigadier.Command;
import io.izzel.arclight.common.compat.prts.PRTSFeaturesConfig;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.izzel.arclight.common.optimization.pathfinding.AsyncPathfindingManager;
import io.izzel.arclight.common.optimization.eventbridge.EventBusStats;
import io.izzel.arclight.common.optimization.ownership.BlockEntityAffinity;
import io.izzel.arclight.common.optimization.ownership.EntityAffinity;
import io.izzel.arclight.common.optimization.drain.BlockEntityTickStats;
import io.izzel.arclight.common.optimization.parallel.DimensionTickManager;
import io.izzel.arclight.common.optimization.parallel.RegionTickManager;
import io.izzel.arclight.common.optimization.servercore.ServerCoreConfig;
import io.izzel.arclight.common.optimization.servercore.dynamic.DynamicManager;
import io.izzel.arclight.common.optimization.servercore.dynamic.DynamicSetting;
import io.izzel.arclight.common.optimization.ownership.CrossRefProbe;
import io.izzel.arclight.common.optimization.ownership.WorldAccessGuard;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import static com.mojang.brigadier.arguments.IntegerArgumentType.getInteger;
import static com.mojang.brigadier.arguments.IntegerArgumentType.integer;
import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;
import io.izzel.arclight.common.optimization.chunksystem.ChunkLoadStats;
import io.izzel.arclight.common.optimization.drain.RoutedDrainStats;
import io.izzel.arclight.common.optimization.chunksystem.ChunkSystemScheduler;
import io.izzel.arclight.common.optimization.light.LightChainDiag;
import io.izzel.arclight.common.optimization.ownership.ClassAffinityLedger;
import io.izzel.arclight.common.optimization.ownership.ThreadPolicy;
import io.izzel.arclight.common.optimization.pathfinding.VillagerPathBudget;
import io.izzel.arclight.common.optimization.chunksystem.ChunkStageTiming;
import io.izzel.arclight.common.optimization.eventbridge.EventAttributionStats;
import io.izzel.arclight.common.optimization.ownership.HostApiAttribution;

/**
 * /servercore 根命令（移植自 ServerCore ServerCoreCommand）。
 * 子命令：reload / settings &lt;动态项&gt; &lt;值&gt; / status；sc 为别名重定向。
 */
public class ServerCoreCommands {
    public static final String VERSION = "PRTS-1.21.1";
    private static final String VALUE = "value";

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        if (!ServerCoreConfig.commands().commandsEnabled()) return;
        var node = literal("servercore");

        node.then(reloadConfig());
        node.then(settings());
        node.then(crossref());
        // GAP 遥测探针读数（M4 插桩）：只新增子命令，既有子命令/输出格式不动
        node.then(eventAttr());
        node.then(topApi());
        node.then(eventStorm());

        if (ServerCoreConfig.commands().statusCommandEnabled()) {
            node.then(literal("status").executes(ctx -> getStatus(ctx.getSource())));
        }

        dispatcher.register(node);
        dispatcher.register(literal("sc").redirect(node.build()));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> reloadConfig() {
        return literal("reload")
                .requires(Permission.require("command.config", 2))
                .executes(ctx -> reload(ctx.getSource()));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> settings() {
        var settings = literal("settings").requires(Permission.require("command.settings", 2));
        for (DynamicSetting setting : DynamicSetting.values()) {
            settings.then(literal(setting.name().toLowerCase())
                    .then(argument(VALUE, integer(setting.getLowerBound(), setting.getUpperBound()))
                            .executes(ctx -> modifyDynamic(ctx.getSource(), getInteger(ctx, VALUE), setting))
                    )
            );
        }
        return settings;
    }

    private static int modifyDynamic(CommandSourceStack source, int value, DynamicSetting setting) {
        DynamicManager manager = DynamicManager.getInstance(source.getServer());
        // dynamic 关闭时管理器不存在，拒绝改动而非假成功
        if (manager == null) {
            source.sendFailure(Component.literal("Dynamic performance settings are disabled in servercore.yml.").withStyle(ChatFormatting.RED));
            return 0;
        }
        setting.set(value, manager);
        source.sendSuccess(() -> Formatter.parse("<c:#secondary>%s <c:#primary>has been set to <c:#secondary>%s".formatted(
                setting.getFormattedName(), setting.getFormattedValue()
        ), source.getServer()), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int reload(CommandSourceStack source) {
        boolean success = ServerCoreConfig.reload();
        if (success) {
            source.sendSuccess(() -> Component.literal("Config reloaded!").withStyle(ChatFormatting.GREEN), false);
        } else {
            source.sendFailure(Component.literal("Failed to reload config! Check the logs for more info.").withStyle(ChatFormatting.RED));
        }
        return Command.SINGLE_SUCCESS;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> crossref() {
        var cmd = literal("crossref").requires(Permission.require("command.config", 2));
        cmd.executes(ctx -> getCrossRef(ctx.getSource(), 10));
        cmd.then(argument("count", integer(1, 50))
                .executes(ctx -> getCrossRef(ctx.getSource(), getInteger(ctx, "count"))));
        cmd.then(literal("reset").executes(ctx -> {
            CrossRefProbe.reset();
            ctx.getSource().sendSuccess(() -> Component.literal("CrossRef probe state reset.").withStyle(ChatFormatting.GREEN), false);
            return Command.SINGLE_SUCCESS;
        }));
        return cmd;
    }

    /**
     * GAP-4 读数：三桶栈采样 + per-mod 监听器时间 + 模组自有线程归属。
     * 口径见 {@code EventAttributionStats}／{@code ModThreadCensus} 类注释（四桶只作派生展示，
     * {@code unclassified = 100 − 三桶和} 为残差，FIND-g05/S-6）。
     */
    private static LiteralArgumentBuilder<CommandSourceStack> eventAttr() {
        var cmd = literal("eventattr").requires(Permission.require("command.config", 2));
        cmd.executes(ctx -> sendReport(ctx.getSource(), "EventAttribution (GAP-4)",
                EventAttributionStats.statusText(10)));
        cmd.then(argument("count", integer(1, 50))
                .executes(ctx -> sendReport(ctx.getSource(), "EventAttribution (GAP-4)",
                        EventAttributionStats.statusText(getInteger(ctx, "count")))));
        return cmd;
    }

    /**
     * GAP-5 读数：宿主 API 调用频次 + 调用者归因（自 {@code sample-every} 采样）。
     * {@code self} 占比属 spark 口径，本探针显式打 {@code self=na}（不得冒充）。
     */
    private static LiteralArgumentBuilder<CommandSourceStack> topApi() {
        var cmd = literal("topapi").requires(Permission.require("command.config", 2));
        cmd.executes(ctx -> sendReport(ctx.getSource(), "HostApiAttribution (GAP-5)",
                HostApiAttribution.statusText(20)));
        cmd.then(argument("count", integer(1, 100))
                .executes(ctx -> sendReport(ctx.getSource(), "HostApiAttribution (GAP-5)",
                        HostApiAttribution.statusText(getInteger(ctx, "count")))));
        return cmd;
    }

    /** GAP-6 读数：模组级风暴生成者 Top-N（复用 GAP-4 的 per-mod 归因）。 */
    private static LiteralArgumentBuilder<CommandSourceStack> eventStorm() {
        var cmd = literal("eventstorm").requires(Permission.require("command.config", 2));
        cmd.executes(ctx -> sendReport(ctx.getSource(), "EventStorm (GAP-6)",
                EventAttributionStats.stormText(20)));
        cmd.then(argument("count", integer(1, 100))
                .executes(ctx -> sendReport(ctx.getSource(), "EventStorm (GAP-6)",
                        EventAttributionStats.stormText(getInteger(ctx, "count")))));
        return cmd;
    }

    private static int sendReport(CommandSourceStack source, String title, String body) {
        source.sendSuccess(() -> Component.literal("=== " + title + " ===\n" + body), false);
        return Command.SINGLE_SUCCESS;
    }

    /** FIND-g03 探针生效面：每个新键一行 {@code <key>=<value>}，关闭也打 {@code =false}。 */
    private static String probeText() {
        return ChunkStageTiming.probeText()
                + " " + EventAttributionStats.probeText()
                + " " + HostApiAttribution.probeText();
    }

    private static int getCrossRef(CommandSourceStack source, int limit) {
        long tick = source.getServer().getTickCount();
        java.util.List<String> lines = CrossRefProbe.report(tick, limit);
        source.sendSuccess(() -> Component.literal("=== CrossRef Probe ===\n" + String.join("\n", lines)), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int getStatus(CommandSourceStack source) {
        CommandConfig config = ServerCoreConfig.commands();
        source.sendSuccess(() -> {
            MutableComponent component = Component.empty();
            Component title = Component.literal("ServerCore").withColor(config.tertiaryValue());
            if (source.isPlayer()) {
                Formatter.addLines(component, 14, config.primaryValue(), title);
            } else {
                component.append(title);
            }

            component.append(Formatter.parse("\n<dark_gray>» <c:#primary>Version: <c:#secondary>%s".formatted(
                    VERSION
            ), source.getServer()));

            for (DynamicSetting setting : DynamicSetting.values()) {
                component.append(Formatter.parse("\n<dark_gray>» <c:#primary>%s: <c:#secondary>%s".formatted(
                        setting.getFormattedName(), setting.getFormattedValue()
                ), source.getServer()));
            }

            component.append(Formatter.parse("\n<dark_gray>» <c:#primary>EventBus: <c:#secondary>%s".formatted(
                    EventBusStats.statusText()
            ), source.getServer()));

            component.append(Formatter.parse("\n<dark_gray>» <c:#primary>ThreadPolicy: <c:#secondary>%s".formatted(
                    WorldAccessGuard.statusText()
            ), source.getServer()));

            component.append(Formatter.parse("\n<dark_gray>» <c:#primary>Barrier: <c:#secondary>%s".formatted(
                    DimensionTickManager.barrierStatusText()
            ), source.getServer()));

            component.append(Formatter.parse("\n<dark_gray>» <c:#primary>DimensionTps: <c:#secondary>%s".formatted(
                    DimensionTickManager.dimensionTpsText()
            ), source.getServer()));

            component.append(Formatter.parse("\n<dark_gray>» <c:#primary>CrossRef: <c:#secondary>%s".formatted(
                    CrossRefProbe.statusText()
            ), source.getServer()));

            component.append(Formatter.parse("\n<dark_gray>» <c:#primary>Probation: <c:#secondary>%s".formatted(
                    io.izzel.arclight.common.optimization.ownership.ClassAffinityLedger.probationStatusText()
            ), source.getServer()));

            component.append(Formatter.parse("\n<dark_gray>» <c:#primary>AsyncPathfinding: <c:#secondary>%s".formatted(
                    AsyncPathfindingManager.statusText()
            ), source.getServer()));

            component.append(Formatter.parse("\n<dark_gray>» <c:#primary>VillagerPathBudget: <c:#secondary>%s".formatted(
                    io.izzel.arclight.common.optimization.pathfinding.VillagerPathBudget.statusText()
            ), source.getServer()));

            component.append(Formatter.parse("\n<dark_gray>» <c:#primary>Journal: <c:#secondary>%s".formatted(
                    RegionTickManager.journalStatusText(source.getServer())
            ), source.getServer()));

            component.append(Formatter.parse("\n<dark_gray>» <c:#primary>Determinism: <c:#secondary>%s".formatted(
                    PRTSFeaturesConfig.determinismMode ? "on (barrier-global)" : "off (region-local)"
            ), source.getServer()));

            component.append(Formatter.parse("\n<dark_gray>» <c:#primary>ChunkLoading: <c:#secondary>%s".formatted(
                    io.izzel.arclight.common.optimization.chunksystem.ChunkLoadStats.statusText()
            ), source.getServer()));

            component.append(Formatter.parse("\n<dark_gray>» <c:#primary>ChunkSystem: <c:#secondary>%s".formatted(
                    io.izzel.arclight.common.optimization.chunksystem.ChunkSystemScheduler.statusText()
            ), source.getServer()));

            component.append(Formatter.parse("\n<dark_gray>» <c:#primary>LightChain: <c:#secondary>%s".formatted(
                    io.izzel.arclight.common.optimization.light.LightChainDiag.statusText(source.getServer())
            ), source.getServer()));

            component.append(Formatter.parse("\n<dark_gray>» <c:#primary>RoutedDrain: <c:#secondary>%s".formatted(
                    io.izzel.arclight.common.optimization.drain.RoutedDrainStats.statusText(12)
            ), source.getServer()));

            component.append(Formatter.parse("\n<dark_gray>» <c:#primary>RegionLoad: <c:#secondary>%s".formatted(
                    io.izzel.arclight.common.optimization.parallel.RegionTickManager.regionLoadStatusText(source.getServer())
            ), source.getServer()));

            component.append(Formatter.parse("\n<dark_gray>» <c:#primary>EntityAddDefer: <c:#secondary>%s".formatted(
                    io.izzel.arclight.common.optimization.parallel.RegionTickManager.entityAddStatusText()
            ), source.getServer()));

            component.append(Formatter.parse("\n<dark_gray>» <c:#primary>BlockEntityTicks: <c:#secondary>%s".formatted(
                    BlockEntityTickStats.statusText(6)
            ), source.getServer()));

            component.append(Formatter.parse("\n<dark_gray>» <c:#primary>BEPolicy: <c:#secondary>%s".formatted(
                    BlockEntityAffinity.statusText()
            ), source.getServer()));

            component.append(Formatter.parse("\n<dark_gray>» <c:#primary>EntityPolicy: <c:#secondary>%s".formatted(
                    EntityAffinity.statusText()
            ), source.getServer()));

            // ===== GAP 遥测探针（M4 插桩）：只新增行，既有行格式一律不动 =====
            if (PRTSFeaturesConfig.chunkStepTelemetryEnabled) {
                component.append(Formatter.parse("\n<dark_gray>» <c:#primary>ChunkStages: <c:#secondary>%s".formatted(
                        ChunkStageTiming.statusText()
                ), source.getServer()));
            }

            // FIND-g03：探针生效面（关闭也打 =false，用于区分「开关关」与「jar 没换」）
            component.append(Formatter.parse("\n<dark_gray>» <c:#primary>Probe: <c:#secondary>%s".formatted(
                    probeText()
            ), source.getServer()));
            return component;
        }, false);
        return Command.SINGLE_SUCCESS;
    }
}

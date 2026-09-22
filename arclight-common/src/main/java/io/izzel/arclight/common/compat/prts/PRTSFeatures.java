package io.izzel.arclight.common.compat.prts;

import io.izzel.arclight.common.compat.prts.feature.EntityClear;
import io.izzel.arclight.common.compat.prts.feature.MenuBenchmark;
import io.izzel.arclight.common.compat.prts.feature.PRTSThreadCost;
import io.izzel.arclight.common.compat.prts.feature.WatchMohist;
import io.izzel.arclight.common.optimization.collision.CollisionBatchStats;
import io.izzel.arclight.common.optimization.entityspatial.EntitySpatialIndexStats;
import io.izzel.arclight.common.optimization.light.LightEngineStats;
import io.izzel.arclight.common.optimization.eventbridge.EventBridgeStats;
import io.izzel.arclight.common.optimization.eventbridge.EventShortcircuitStats;
import io.izzel.arclight.common.optimization.menubroadcast.MenuBroadcastStats;
import io.izzel.arclight.common.optimization.poi.PoiQueryStats;
import java.util.ArrayList;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.craftbukkit.v.CraftServer;

/** PRTS 轻量防卡功能的统一启动/停止聚合。 */
public class PRTSFeatures {

    private static final Logger LOGGER = LogManager.getLogger("PRTS-Features");

    public static void start() {
        PRTSFeaturesConfig.init();
        io.izzel.arclight.common.optimization.ownership.LearnedRoutePersistence.loadOnStartup();  // 启动时加载 learned routes
        EntityClear.start();
        WatchMohist.start();
        registerCommands();
        LOGGER.info("[PRTS-Features] started | EntityClear(item={},monster={}) Watchdog={} genIntakePerTick={}",
                PRTSFeaturesConfig.clearItemEnabled, PRTSFeaturesConfig.clearMonsterEnabled,
                PRTSFeaturesConfig.watchdogEnabled, PRTSFeaturesConfig.generationTasksPerTick);
    }

    public static void tick() {
        WatchMohist.update();
        // worker 线程写统计，主线程每 tick 驱动周期汇总日志。
        CraftServer craft = (CraftServer) Bukkit.getServer();
        long serverTick = craft == null || craft.getServer() == null ? 0L : craft.getServer().getTickCount();
        LightEngineStats.tick(serverTick);
        EntitySpatialIndexStats.tick(serverTick);
        PoiQueryStats.tick(serverTick);
        CollisionBatchStats.tick(serverTick);
        MenuBroadcastStats.tick(serverTick);
        EventBridgeStats.tick(serverTick);
        EventShortcircuitStats.tick(serverTick);
        // GAP-4 周期线程普查（M4 插桩；默认关，节流在 ModThreadCensus 内）
        io.izzel.arclight.common.optimization.eventbridge.EventAttributionStats.tick(serverTick);
    }

    public static void stop() {
        PRTSFeaturesConfig.persistLearnedRoutes();
        EntityClear.stop();
        WatchMohist.stop();
    }

    private static void registerCommands() {
        Command cmd = new Command("prtsfeatures", "PRTS 诊断命令", "/prtsfeatures threadcost", new ArrayList<>()) {
            @Override
            public boolean execute(CommandSender sender, String label, String[] args) {
                if (args.length >= 1 && args[0].equalsIgnoreCase("threadcost")) {
                    PRTSThreadCost.dumpThreadCpuTime(sender);
                    return true;
                }
                if (args.length >= 1 && args[0].equalsIgnoreCase("menubench")) {
                    MenuBenchmark.run(sender);
                    return true;
                }
                sender.sendMessage("§e[PRTS] 用法: /prtsfeatures threadcost|menubench");
                return true;
            }
        };
        ((CraftServer) Bukkit.getServer()).getCommandMap().register("prts", cmd);
        LOGGER.info("[PRTS-Features] registered command /prtsfeatures threadcost|menubench");
    }
}

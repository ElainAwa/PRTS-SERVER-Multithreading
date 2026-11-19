/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.owner;

import io.izzel.arclight.common.prts.kernel.domain.entity.EntityDueCapture;
import io.izzel.arclight.common.prts.kernel.domain.entity.EntityDueWindow;
import io.izzel.arclight.common.prts.support.EntityEligibility;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.schedule.Activity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The owner phase: rest rows are selected first, then (when declared) walking/standing AI rows;
 * both run from the host pre-tick wrapper. The walking slice is non-sleeping, non-PANIC/hurt AI
 * Villagers and executes the original server-thread tick without REST intent contracts. */
public final class ServerThreadOwnerPhase {

    public static final int MAX_ROWS = 8;
    public static final String TRACE_PROPERTY = "arclight.prts.ownerPhaseTrace";
    private static final Map<String, LevelPhase> PENDING = new LinkedHashMap<>();
    private static long totalSelected;
    private static long totalExecuted;
    private static long totalFallback;
    private static long totalLeft;
    private static volatile int lastSelected;

    private ServerThreadOwnerPhase() {
    }

    private enum Slice { REST, WALK }

    private static final class LevelPhase {
        final Map<Integer, Villager> rows = new LinkedHashMap<>();
        final Map<Integer, Slice> slice = new LinkedHashMap<>();
        int walkSelected;
        int walkExecuted;
        int selected;
        int executed;
        int fallback;
        int terminal;
        int truncated;
        long skipsAtStart;
        long tick;
    }

    /** Marks due rows of this level; only this level's outstanding set is cleared. */
    public static void enter(ServerLevel level, EntityDueCapture.Window window) {
        if (!EntityRowOwnershipWindow.enabled() || level == null || window == null) {
            return;
        }
        String dimension = level.dimension().location().toString();
        LevelPhase phase = new LevelPhase();
        phase.tick = level.getServer().getTickCount();
        phase.skipsAtStart = RestOwnerWiring.skips();
        List<Villager> selected = select(level, window, dimension, phase);
        phase.selected = selected.size();
        lastSelected = selected.size();
        for (Villager villager : selected) {
            phase.rows.put(villager.getId(), villager);
        }
        if (trace() && (!selected.isEmpty() || levelIdRows(window, dimension) > 0)) {
            System.out.println("[PRTS] owner-phase selected=" + selected.size()
                + " pending=" + phase.rows.size() + " reasons="
                + histogram(window, dimension));
        }
        if (trace() && phase.walkSelected > 0) {
            System.out.println("[PRTS] owner-walk selected=" + phase.walkSelected
                + " total=" + selected.size());
        }
        PENDING.put(dimension, phase);
    }

    /** Runs one pending owner row from the host pre-tick wrapper; false means host may tick. */
    public static boolean runPending(net.minecraft.world.entity.Entity entity) {
        if (entity == null) {
            return false;
        }
        String dimension = entity.level().dimension().location().toString();
        LevelPhase phase = PENDING.get(dimension);
        if (phase == null) {
            return false;
        }
        Villager villager = phase.rows.remove(entity.getId());
        if (villager != entity) {
            if (villager != null) {
                phase.rows.put(entity.getId(), villager);
            }
            return false;
        }
        Slice slice = phase.slice.get(entity.getId());
        if (slice == null) {
            phase.rows.put(entity.getId(), villager);
            return false;
        }
        try {
            boolean executed = slice == Slice.WALK
                ? RealEntityOwnerBody.runWalkOnServer((ServerLevel) entity.level(), villager)
                : RealEntityOwnerBody.runRestedOnServer((ServerLevel) entity.level(), villager);
            if (executed) {
                phase.executed++;
                if (slice == Slice.WALK) {
                    phase.walkExecuted++;
                }
            } else {
                phase.fallback++;
            }
            return executed;
        } catch (Throwable failure) {
            phase.terminal++;
            throw failure;
        }
    }

    /** Reconciles and reports one level's owner phase after the host entity pass. */
    public static void endTick(ServerLevel level) {
        if (level == null) {
            return;
        }
        LevelPhase phase = PENDING.remove(level.dimension().location().toString());
        if (phase == null) {
            return;
        }
        long hostSkips = RestOwnerWiring.skips();
        long delta = hostSkips - phase.skipsAtStart;
        int left = phase.rows.size();
        totalSelected += phase.selected;
        totalExecuted += phase.executed;
        totalFallback += phase.fallback;
        totalLeft += left;
        String line = "tick=" + phase.tick + " selected=" + phase.selected + " executed="
            + phase.executed + " fallback=" + phase.fallback + " terminal=" + phase.terminal
            + " leftPending=" + left + " hostSkipsDelta=" + delta
            + " hostSkipsTotal=" + hostSkips;
        if (!trace()) {
            return;
        }
        System.out.println("[PRTS] phaseEnd " + line);
        if (phase.walkSelected > 0) {
            System.out.println("[PRTS] owner-walk-end dim="
                + level.dimension().location() + " selected=" + phase.walkSelected
                + " executed=" + phase.walkExecuted + " total=" + phase.selected);
        }
        try {
            Path path = Path.of("logs/owner-phase-end.tsv");
            Files.createDirectories(path.getParent());
            boolean header = !Files.exists(path);
            String row = line.replace(' ', '\t');
            String text = (header ? "tick\tselected\texecuted\tfallback\tterminal"
                + "\tleftPending\thostSkipsDelta\thostSkipsTotal\n" : "") + row
                .replace("tick=", "").replace("selected=", "").replace("executed=", "")
                .replace("fallback=", "").replace("terminal=", "").replace("leftPending=", "")
                .replace("hostSkipsDelta=", "").replace("hostSkipsTotal=", "") + "\n";
            Files.writeString(path, text, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // A diagnostic write failure must not change the tick.
        }
    }

    /** Pure selector after admission; kept separate so tests can construct due rows. */
    static List<EntityDueWindow.Row> selectRows(EntityDueCapture.Window window, String levelId) {
        List<EntityDueWindow.Row> rows = new ArrayList<>();
        if (window == null || levelId == null) {
            return rows;
        }
        for (EntityDueWindow.World world : window.worlds()) {
            if (!levelId.equals(world.worldId())) {
                continue;
            }
            for (EntityDueWindow.Row row : world.rows()) {
                if (OwnerZoneAdmission.admits(row) && row.classTag() != null
                    && row.classTag().endsWith("Villager")) {
                    rows.add(row);
                }
            }
        }
        return rows;
    }

    private static List<Villager> select(ServerLevel level, EntityDueCapture.Window window,
                                         String levelId, LevelPhase phase) {
        List<Villager> selected = new ArrayList<>();
        for (EntityDueWindow.Row row : selectRows(window, levelId)) {
            net.minecraft.world.entity.Entity entity = level.getEntity(row.entityId());
            if (!(entity instanceof Villager villager) || !villager.isAlive()
                || villager.isRemoved() || villager.isNoAi()
                || villager.getPose() != Pose.SLEEPING
                || !villager.getBrain().isActive(Activity.REST)) {
                continue;
            }
            if (selected.size() < MAX_ROWS) {
                selected.add(villager);
                phase.slice.put(villager.getId(), Slice.REST);
            } else {
                phase.truncated++;
            }
        }
        if (OwnerZoneAdmission.walkSlice()) {
            for (EntityDueWindow.Row row : selectRowsWalk(window, levelId)) {
                net.minecraft.world.entity.Entity entity = level.getEntity(row.entityId());
                if (!(entity instanceof Villager villager) || !villager.isAlive()
                    || villager.isRemoved() || villager.isNoAi() || villager.isPassenger()) {
                    continue;
                }
                if (villager.getPose() == Pose.SLEEPING
                    || villager.getBrain().isActive(Activity.REST)
                    || villager.getBrain().isActive(Activity.PANIC)
                    || villager.hurtTime != 0) {
                    continue;
                }
                if (selected.size() < MAX_ROWS) {
                    selected.add(villager);
                    phase.slice.put(villager.getId(), Slice.WALK);
                    phase.walkSelected++;
                } else {
                    phase.truncated++;
                }
            }
        }
        if (phase.truncated > 0) {
            System.out.println("[PRTS] owner-phase truncated=" + phase.truncated);
        }
        return selected;
    }

    /** Pure walking-row selector; live pose/passenger checks happen in select. */
    static List<EntityDueWindow.Row> selectRowsWalk(EntityDueCapture.Window window,
                                                    String levelId) {
        List<EntityDueWindow.Row> rows = new ArrayList<>();
        if (window == null || levelId == null) {
            return rows;
        }
        for (EntityDueWindow.World world : window.worlds()) {
            if (!levelId.equals(world.worldId())) {
                continue;
            }
            for (EntityDueWindow.Row row : world.rows()) {
                if (OwnerZoneAdmission.admitsWalk(row) && row.classTag() != null
                    && row.classTag().endsWith("Villager")) {
                    rows.add(row);
                }
            }
        }
        return rows;
    }

    private static int levelIdRows(EntityDueCapture.Window window, String levelId) {
        int count = 0;
        for (EntityDueWindow.World world : window.worlds()) {
            if (levelId.equals(world.worldId())) {
                count += world.rows().size();
            }
        }
        return count;
    }

    private static Map<EntityEligibility.Reason, Integer> histogram(
        EntityDueCapture.Window window, String levelId) {
        Map<EntityEligibility.Reason, Integer> counts = new EnumMap<>(EntityEligibility.Reason.class);
        if (window == null) {
            return counts;
        }
        for (EntityDueWindow.World world : window.worlds()) {
            if (!levelId.equals(world.worldId())) {
                continue;
            }
            for (EntityDueWindow.Row row : world.rows()) {
                counts.merge(row.reason(), 1, Integer::sum);
            }
        }
        return counts;
    }

    /** One gated summary at server stop; no per-tick I/O when the diagnostic is off. */
    public static void stopSummary() {
        if (totalSelected > 0L || totalExecuted > 0L || totalLeft > 0L) {
            System.out.println("[PRTS] phaseSummary selected=" + totalSelected + " executed="
                + totalExecuted + " fallback=" + totalFallback + " leftPending=" + totalLeft);
        }
    }

    public static boolean trace() {
        return Boolean.getBoolean(TRACE_PROPERTY);
    }

    static int lastSelected() {
        return lastSelected;
    }
}

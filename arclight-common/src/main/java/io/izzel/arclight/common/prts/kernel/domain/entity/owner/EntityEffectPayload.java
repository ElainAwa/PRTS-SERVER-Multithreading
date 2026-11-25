/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.owner;

import net.minecraft.core.GlobalPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.Villager;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/** The single effect line of one owner entity tick: the identity, the terminal and the field names
 * that really changed. The line is bounded so the reservation made before the body always covers
 * the terminal written after it. */
final class EntityEffectPayload {

    static final int CHANGED_LIMIT = 240;
    private static final String UUID_SLOT = "00000000-0000-0000-0000-000000000000";
    private static final String PREFIX = "entity-state=";
    private static final String DUMP_PROPERTY = "arclight.prts.diag.effectDump";
    private static final int DUMP_LIMIT = parseDumpLimit(System.getProperty(DUMP_PROPERTY, ""));
    private static final AtomicInteger DUMPED = new AtomicInteger();
    private static int dumpTestLimit = -1;

    private EntityEffectPayload() {
    }

    static String placeholder() {
        return PREFIX + UUID_SLOT + ";result=PUBLISHED;changed=" + "x".repeat(CHANGED_LIMIT);
    }

    static String row(UUID uuid, String result, String changed) {
        String fields = changed == null || changed.isBlank() ? "unverified" : changed;
        if (fields.length() > CHANGED_LIMIT) {
            fields = fields.substring(0, CHANGED_LIMIT);
        }
        return PREFIX + uuid + ";result=" + result + ";changed=" + fields;
    }

    private static int parseDumpLimit(String raw) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (RuntimeException ignored) {
            return 0;
        }
    }

    static void setDumpLimitForTest(int limit) {
        dumpTestLimit = limit;
    }

    static void resetDumpForTest() {
        dumpTestLimit = -1;
        DUMPED.set(0);
    }

    static int dumpedForTest() {
        return DUMPED.get();
    }

    /** Diagnostic only: at most the configured number of effect lines reach stdout. The limit is
     * read once at class initialization; absent/invalid/zero keeps the shipped path silent. */
    static void dump(long effectRef, long tick, String content) {
        int limit = dumpTestLimit >= 0 ? dumpTestLimit : DUMP_LIMIT;
        if (limit <= 0) {
            return;
        }
        int slot;
        do {
            slot = DUMPED.get();
            if (slot >= limit) {
                return;
            }
        } while (!DUMPED.compareAndSet(slot, slot + 1));
        System.out.println("[PRTS] effect.dump|tick=" + tick + "|ref=" + effectRef
            + "|content=" + (content == null ? "" : content));
    }

    /** The state fields every admitted mob type shares; a type slice extends this instead of
     * copying the entity/pose/activity reads into its own capture. */
    record MobState(double x, double y, double z, double motionX, double motionY, double motionZ,
                    double health, String pose, String activity) {
    }

    static MobState captureMob(Mob mob) {
        var brain = mob.getBrain();
        return new MobState(mob.getX(), mob.getY(), mob.getZ(),
            mob.getDeltaMovement().x, mob.getDeltaMovement().y, mob.getDeltaMovement().z,
            mob.getHealth(), mob.getPose().name(),
            brain == null ? "none"
                : brain.getActiveNonCoreActivity().map(activity -> activity.getName()).orElse("none"));
    }

    static Snapshot capture(Villager villager) {
        MobState common = captureMob(villager);
        var inventory = villager.getInventory();
        StringBuilder items = new StringBuilder();
        int itemCount = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            var stack = inventory.getItem(slot);
            if (!stack.isEmpty()) {
                itemCount += stack.getCount();
            }
            items.append(stack.isEmpty() ? "-" : stack.getItem() + "x" + stack.getCount()).append(';');
        }
        GlobalPos job = villager.getBrain().getMemory(MemoryModuleType.JOB_SITE).orElse(null);
        GlobalPos home = villager.getBrain().getMemory(MemoryModuleType.HOME).orElse(null);
        return new Snapshot(common.x(), common.y(), common.z(), common.motionX(), common.motionY(),
            common.motionZ(), common.health(), itemCount, items.toString(), common.pose(),
            common.activity(), villager.getVillagerData().getProfession().toString(),
            villager.getVillagerXp(), globalPos(job), globalPos(home));
    }

    static String changedValues(Snapshot before, Snapshot after) {
        List<String> changed = new ArrayList<>();
        if (Double.compare(before.x, after.x) != 0 || Double.compare(before.y, after.y) != 0
            || Double.compare(before.z, after.z) != 0) {
            changed.add("pos=" + delta(before.x, after.x) + "," + delta(before.y, after.y)
                + "," + delta(before.z, after.z));
        }
        if (Double.compare(before.motionX, after.motionX) != 0
            || Double.compare(before.motionY, after.motionY) != 0
            || Double.compare(before.motionZ, after.motionZ) != 0) {
            changed.add("motion=" + delta(before.motionX, after.motionX) + ","
                + delta(before.motionY, after.motionY) + ","
                + delta(before.motionZ, after.motionZ));
        }
        if (Double.compare(before.health, after.health) != 0) {
            changed.add("health=" + number(before.health) + "->" + number(after.health));
        }
        if (before.inventoryCount != after.inventoryCount
            || !before.inventory.equals(after.inventory)) {
            changed.add("inventory=" + before.inventoryCount + "->" + after.inventoryCount);
        }
        if (!before.pose.equals(after.pose)) {
            changed.add("pose=" + before.pose + "->" + after.pose);
        }
        if (!before.activity.equals(after.activity)) {
            changed.add("activity=" + before.activity + "->" + after.activity);
        }
        if (!before.profession.equals(after.profession)) {
            changed.add("profession=" + before.profession + "->" + after.profession);
        }
        if (before.xp != after.xp) {
            changed.add("xp=" + before.xp + "->" + after.xp);
        }
        if (!before.jobSite.equals(after.jobSite)) {
            changed.add("brain_job_site=" + before.jobSite + "->" + after.jobSite);
        }
        if (!before.home.equals(after.home)) {
            changed.add("brain_home=" + before.home + "->" + after.home);
        }
        return changed.isEmpty() ? "none" : String.join(",", changed);
    }

    private static String delta(double before, double after) {
        return String.format(Locale.ROOT, "%+.3f", after - before);
    }

    private static String number(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private static String globalPos(GlobalPos pos) {
        if (pos == null) {
            return "none";
        }
        return pos.dimension().location() + "@" + pos.pos().getX() + ","
            + pos.pos().getY() + "," + pos.pos().getZ();
    }

    record Snapshot(double x, double y, double z, double motionX, double motionY, double motionZ,
                    double health, int inventoryCount, String inventory, String pose, String activity,
                    String profession, int xp, String jobSite, String home) {
    }
}
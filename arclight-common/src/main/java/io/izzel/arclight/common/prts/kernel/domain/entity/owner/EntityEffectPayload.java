/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.owner;

import net.minecraft.core.GlobalPos;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.Villager;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** The single effect line of one owner entity tick: the identity, the terminal and the field names
 * that really changed. The line is bounded so the reservation made before the body always covers
 * the terminal written after it. */
final class EntityEffectPayload {

    static final int CHANGED_LIMIT = 240;
    private static final String UUID_SLOT = "00000000-0000-0000-0000-000000000000";
    private static final String PREFIX = "entity-state=";

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

    static Snapshot capture(Villager villager) {
        var inventory = villager.getInventory();
        StringBuilder items = new StringBuilder();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            var stack = inventory.getItem(slot);
            items.append(stack.isEmpty() ? "-" : stack.getItem() + "x" + stack.getCount()).append(';');
        }
        GlobalPos job = villager.getBrain().getMemory(MemoryModuleType.JOB_SITE).orElse(null);
        GlobalPos home = villager.getBrain().getMemory(MemoryModuleType.HOME).orElse(null);
        return new Snapshot(villager.getX(), villager.getY(), villager.getZ(),
            villager.getDeltaMovement().x, villager.getDeltaMovement().y,
            villager.getDeltaMovement().z, villager.getHealth(), inventory.getContainerSize(),
            items.toString(), villager.getPose().name(),
            villager.getBrain().getActiveNonCoreActivity().map(activity -> activity.getName()).orElse("none"),
            villager.getVillagerData().getProfession().toString(),
            villager.getVillagerXp(), String.valueOf(job), String.valueOf(home));
    }

    static String changedFields(Snapshot before, Snapshot after) {
        List<String> changed = new ArrayList<>();
        if (Double.compare(before.x, after.x) != 0 || Double.compare(before.y, after.y) != 0
            || Double.compare(before.z, after.z) != 0) {
            changed.add("pos");
        }
        if (Double.compare(before.motionX, after.motionX) != 0
            || Double.compare(before.motionY, after.motionY) != 0
            || Double.compare(before.motionZ, after.motionZ) != 0) {
            changed.add("motion");
        }
        if (Double.compare(before.health, after.health) != 0) {
            changed.add("health");
        }
        if (before.inventorySize != after.inventorySize
            || !before.inventory.equals(after.inventory)) {
            changed.add("inventory");
        }
        if (!before.pose.equals(after.pose)) {
            changed.add("pose");
        }
        if (!before.activity.equals(after.activity)) {
            changed.add("activity");
        }
        if (!before.profession.equals(after.profession)) {
            changed.add("profession");
        }
        if (before.xp != after.xp) {
            changed.add("xp");
        }
        if (!before.jobSite.equals(after.jobSite)) {
            changed.add("brain_job_site");
        }
        if (!before.home.equals(after.home)) {
            changed.add("brain_home");
        }
        return changed.isEmpty() ? "none" : String.join(",", changed);
    }

    record Snapshot(double x, double y, double z, double motionX, double motionY, double motionZ,
                    double health, int inventorySize, String inventory, String pose, String activity,
                    String profession, int xp, String jobSite, String home) {
    }
}

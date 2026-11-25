/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel;

import io.izzel.arclight.common.prts.kernel.auth.HolderKind;
import io.izzel.arclight.common.prts.kernel.auth.WriteLevel;
import io.izzel.arclight.common.prts.kernel.commit.FrozenDeclarations;
import io.izzel.arclight.common.prts.kernel.domain.entity.EntityDueCapture;
import io.izzel.arclight.common.prts.kernel.domain.entity.EntityObjectGenerations;
import io.izzel.arclight.common.prts.kernel.jobs.JobDeclaration;
import io.izzel.arclight.common.prts.kernel.shares.ShareClass;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;

import java.util.ArrayList;
import java.util.List;

/** Hands the real due rows of one level into the same freeze that builds the tick plan: the row's
 * own entity-state object becomes the declared node, its chunk is only the spatial context the
 * declaration carries, and both the declaration and its candidate come from the one captured
 * window. Nothing is taken from a previous tick and nothing is declared after the window froze. */
public final class EntityDeclarationIntake {

    private record Armed(String levelId, List<Mob> selected) {
    }

    private static Armed armed;

    private EntityDeclarationIntake() {
    }

    /** Arms the next freeze with this level's selected rows; called after the due window froze and
     * before any owner body of the tick ran. */
    public static synchronized void arm(ServerLevel level, EntityDueCapture.Window window,
                                        List<Mob> selected) {
        if (level == null || window == null || selected == null) {
            armed = null;
            return;
        }
        armed = new Armed(level.dimension().location().toString(), List.copyOf(selected));
    }

    /** Builds the declarations and candidates of the armed window for the freeze that is running. */
    public static synchronized AiPlanFreezeHook.Capture capture(List<String> worlds,
                                                                long planSequence) {
        Armed current = armed;
        armed = null;
        if (current == null || current.selected().isEmpty()) {
            return AiPlanFreezeHook.Capture.empty();
        }
        String worldId = current.levelId();
        KernelModule module = KernelModule.instance();
        String siteId = module.ownerContext().primarySite().siteId();
        long worldEpoch = module.guard().worldEpochs().epochOf(worldId);
        List<JobDeclaration> declarations = new ArrayList<>(current.selected().size());
        List<FrozenDeclarations.Candidate> candidates = new ArrayList<>(current.selected().size());
        for (Mob villager : current.selected()) {
            long uuidHigh = villager.getUUID().getMostSignificantBits();
            long uuidLow = villager.getUUID().getLeastSignificantBits();
            long generation = EntityObjectGenerations.shared()
                .generationOf(worldId, uuidHigh, uuidLow);
            String domain = FrozenDeclarations.ENTITY_STATE_PREFIX + uuidHigh + ":" + uuidLow
                + ":" + generation;
            int chunkX = ((int) Math.floor(villager.getX())) >> 4;
            int chunkZ = ((int) Math.floor(villager.getZ())) >> 4;
            long packedChunk = net.minecraft.world.level.ChunkPos.asLong(chunkX, chunkZ);
            declarations.add(new JobDeclaration(domain, 0L, worldId, "entity", 0, List.of(), 0,
                "world:" + worldId, "world:" + worldId,
                List.of(new JobDeclaration.DomainRef(worldId, "entity", 0)),
                List.of(new JobDeclaration.DomainRef(worldId, "entity", 0)),
                ShareClass.ENTITY, JobDeclaration.SiteClass.PARALLEL, 0, 1,
                List.of(new JobDeclaration.OwnerDemand(worldId, WriteLevel.OBJECT, domain,
                    HolderKind.REGISTERED, siteId, 2)),
                villager.tickCount));
            candidates.add(new FrozenDeclarations.Candidate(worldId, "entity", domain,
                new FrozenDeclarations.DeclaredChunk(packedChunk), domain, 0L, worldEpoch,
                FrozenDeclarations.TargetKind.ENTITY));
        }
        return new AiPlanFreezeHook.Capture(declarations, candidates);
    }
}

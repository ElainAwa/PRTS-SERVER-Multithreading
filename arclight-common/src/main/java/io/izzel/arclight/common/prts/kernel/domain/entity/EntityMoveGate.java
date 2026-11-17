/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity;

import io.izzel.arclight.common.prts.kernel.auth.HolderKind;
import io.izzel.arclight.common.prts.kernel.auth.OwnerRegistry;
import io.izzel.arclight.common.prts.kernel.diff.StateHasher;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.Objects;

/**
 * The pre-write admission of one row move under the process owner context: the section membership
 * and the section directory facts come from the injected facts provider (the live one reads the
 * real section of the loaded chunk, never a chunk-presence approximation), the version slots are
 * granted and read again at the write point, and the action runs only when every right was
 * admitted. A missing row or a refused admission never runs the action.
 */
final class EntityMoveGate {

    /** The live position of one row, read on the thread that owns the tick. */
    public record Position(double x, double y, double z) {
    }

    /** The row lookup of one gate; a lookup that cannot answer returns null. */
    @FunctionalInterface
    interface RowLookup {

        Position read(String worldId, int entityId);
    }

    /** The real section facts: whether the target section exists and whether the source empties. */
    interface SectionFacts {

        boolean exists(ServerLevel level, long sectionKey);

        boolean emptiesAfter(ServerLevel level, long sectionKey);
    }

    /** The live facts: the loaded chunk's section decides existence; emptiness needs the index. */
    static final SectionFacts LIVE_FACTS = new SectionFacts() {

        @Override
        public boolean exists(ServerLevel level, long sectionKey) {
            if (level == null) {
                return false;
            }
            LevelChunk chunk = level.getChunkSource().getChunkNow(SectionPos.x(sectionKey),
                SectionPos.z(sectionKey));
            if (chunk == null) {
                return false;
            }
            LevelChunkSection section = chunk.getSections()[SectionPos.y(sectionKey)];
            return section != null && !section.hasOnlyAir();
        }

        @Override
        public boolean emptiesAfter(ServerLevel level, long sectionKey) {
            // Whether a source section empties needs the membership index, not a section scan; the
            // conservative answer keeps a directory delete unrequested until that index answers.
            return false;
        }
    };

    private final OwnerContext context;
    private final SectionFacts facts;
    private final RowLookup rows;

    EntityMoveGate(OwnerContext context, SectionFacts facts, RowLookup rows) {
        this.context = Objects.requireNonNull(context, "context");
        this.facts = Objects.requireNonNull(facts, "facts");
        this.rows = Objects.requireNonNull(rows, "rows");
    }

    /** Admits one move under the shared context and runs the write only when admitted. */
    boolean admit(EntityWriteBackIdentity expected, StateHasher.Slice row, Runnable write) {
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(row, "row");
        Objects.requireNonNull(write, "write");
        ServerLevel level = LiveEntityAccess.level(row.worldId());
        Position position = rows.read(row.worldId(), (int) row.entitySeq());
        if (position == null) {
            return false;
        }
        long sourceSection = SectionPos.asLong(BlockPos.containing(position.x(), position.y(),
            position.z()));
        long targetSection = SectionPos.asLong(BlockPos.containing(row.x(), row.y(), row.z()));
        boolean targetExists = sourceSection == targetSection || facts.exists(level, targetSection);
        boolean sourceEmptiesAfter = facts.emptiesAfter(level, sourceSection);
        EntityMoveResources.Move move = new EntityMoveResources.Move(expected.identity(),
            expected.objectGeneration(), sourceSection, targetSection, targetExists,
            sourceEmptiesAfter);
        for (OwnerRegistry.OwnershipDomain key : EntityMoveResources.keys(move)) {
            context.versions().grant(key.worldId(), key.level(), key.domainId());
        }
        EntityMoveAuthority authority = new EntityMoveAuthority(context.registry(),
            context.versions(), new EntityMoveAuthority.Binding("owner-site:" + siteId(),
                HolderKind.KERNEL, 0L, 0L, 0L, 0L, 0L));
        return authority.move(move, write) == EntityMoveAuthority.Verdict.ADMITTED;
    }

    private String siteId() {
        return context.primarySite().siteId();
    }
}

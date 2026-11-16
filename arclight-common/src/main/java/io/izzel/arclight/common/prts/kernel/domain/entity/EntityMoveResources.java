/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity;

import io.izzel.arclight.common.prts.kernel.auth.HolderKind;
import io.izzel.arclight.common.prts.kernel.auth.OwnerRegistry;
import io.izzel.arclight.common.prts.kernel.auth.OwnerToken;
import io.izzel.arclight.common.prts.kernel.auth.WriteLevel;
import io.izzel.arclight.common.prts.kernel.auth.WriteVersionSlots;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * The resource keys of one entity move, mapped onto the ownership domains and version slots the
 * existing write-right mechanism already names. A move is one of three acts: the row's own state
 * write, the membership writes of the section it leaves and the section it enters, and the
 * directory writes that create a missing section or drop one that became empty. The mapping adds no
 * resource kind, no rejection code and no read key of its own.
 */
final class EntityMoveResources {

    /** The acts one move performs, in the order the contract names them. */
    enum WriteKind {
        ENTITY_STATE,
        SOURCE_MEMBERSHIP,
        TARGET_MEMBERSHIP,
        DIRECTORY_CREATE,
        DIRECTORY_DELETE
    }

    /** The identity part of the entity-state domain: the object is the UUID pair and its lifecycle. */
    static final String ENTITY_STATE_PREFIX = "entity-state:";
    /** The identity part of one section membership domain: the section key is a stable long. */
    static final String SECTION_PREFIX = "entity-section:";
    /** The directory domain is one per world: the directory structure is a single shared container. */
    static final String DIRECTORY_DOMAIN = "entity-section-directory";

    /** One move as the write-back sees it: who moves, from which section to which, and what the
     * section directory holds before the move. The two booleans are facts read before the write. */
    record Move(EntityRowIdentity identity, long objectGeneration, long sourceSectionKey,
                long targetSectionKey, boolean targetSectionExists, boolean sourceEmptiesAfter) {

        Move {
            Objects.requireNonNull(identity, "identity");
            if (objectGeneration < 0L) {
                throw new IllegalArgumentException("a lifecycle generation cannot be negative");
            }
        }

        boolean sameSection() {
            return sourceSectionKey == targetSectionKey;
        }
    }

    private EntityMoveResources() {
    }

    /** The acts this move performs, in the contract order; an empty-source removal and a missing
     * target can both be true, and then both directory acts are performed by one directory key. */
    static List<WriteKind> kinds(Move move) {
        List<WriteKind> kinds = new ArrayList<>(5);
        kinds.add(WriteKind.ENTITY_STATE);
        if (!move.sameSection()) {
            kinds.add(WriteKind.SOURCE_MEMBERSHIP);
            kinds.add(WriteKind.TARGET_MEMBERSHIP);
            if (!move.targetSectionExists()) {
                kinds.add(WriteKind.DIRECTORY_CREATE);
            }
            if (move.sourceEmptiesAfter()) {
                kinds.add(WriteKind.DIRECTORY_DELETE);
            }
        }
        return List.copyOf(kinds);
    }

    /** The ownership domains this move asks for, deduplicated and in the stable key order. */
    static List<OwnerRegistry.OwnershipDomain> keys(Move move) {
        List<OwnerRegistry.OwnershipDomain> keys = new ArrayList<>(5);
        keys.add(entityState(move));
        if (!move.sameSection()) {
            keys.add(section(move.identity().worldId(), move.sourceSectionKey()));
            keys.add(section(move.identity().worldId(), move.targetSectionKey()));
            if (!move.targetSectionExists() || move.sourceEmptiesAfter()) {
                keys.add(directory(move.identity().worldId()));
            }
        }
        keys.sort(domainOrder());
        return List.copyOf(keys);
    }

    /** The entity-state domain: the world, the UUID pair and the object lifecycle generation. */
    static OwnerRegistry.OwnershipDomain entityState(Move move) {
        EntityRowIdentity identity = move.identity();
        return new OwnerRegistry.OwnershipDomain(identity.worldId(), WriteLevel.OBJECT,
            ENTITY_STATE_PREFIX + identity.uuidHigh() + ":" + identity.uuidLow() + ":"
                + move.objectGeneration());
    }

    /** The membership domain of one section of one world. */
    static OwnerRegistry.OwnershipDomain section(String worldId, long sectionKey) {
        return new OwnerRegistry.OwnershipDomain(worldId, WriteLevel.SECTION,
            SECTION_PREFIX + sectionKey);
    }

    /** The directory domain of one world. */
    static OwnerRegistry.OwnershipDomain directory(String worldId) {
        return new OwnerRegistry.OwnershipDomain(worldId, WriteLevel.DIMENSION, DIRECTORY_DOMAIN);
    }

    /**
     * The tokens one move asks for, in the stable domain order; the expected version of each token
     * is the version the slot carries when the tokens are built, and zero when it carries none.
     */
    static List<OwnerToken> tokens(Move move, WriteVersionSlots versions, String holderSiteId,
                                   HolderKind holderKind, long epoch, long planSequence,
                                   long worldEpoch, long holdStartTick, long expireTick) {
        List<OwnerToken> tokens = new ArrayList<>(5);
        for (OwnerRegistry.OwnershipDomain domain : keys(move)) {
            long version = versions == null ? WriteVersionSlots.NOT_CARRIED
                : versions.carried(domain.worldId(), domain.level(), domain.domainId());
            tokens.add(new OwnerToken(domain.worldId(), domain.level(), domain.domainId(), epoch,
                version, holdStartTick, expireTick, holderKind, holderSiteId, planSequence,
                worldEpoch));
        }
        return List.copyOf(tokens);
    }

    /** Whether every token names a version the slots carry. */
    static boolean versionsCarried(List<OwnerToken> tokens) {
        for (OwnerToken token : tokens) {
            if (token.expectedVersion() == WriteVersionSlots.NOT_CARRIED) {
                return false;
            }
        }
        return true;
    }

    /** The stable order every caller must present resources in: world, level, domain. */
    static Comparator<OwnerRegistry.OwnershipDomain> domainOrder() {
        return Comparator.comparing(OwnerRegistry.OwnershipDomain::worldId)
            .thenComparing(domain -> domain.level().ordinal())
            .thenComparing(OwnerRegistry.OwnershipDomain::domainId);
    }

    static Comparator<OwnerToken> tokenOrder() {
        return Comparator.comparing(OwnerToken::worldId)
            .thenComparing(token -> token.level().ordinal())
            .thenComparing(OwnerToken::domainId);
    }
}

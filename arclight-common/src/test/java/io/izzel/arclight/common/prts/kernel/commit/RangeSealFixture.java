/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.commit;

import java.util.LinkedHashMap;
import java.util.Map;

/** Builds the real range seals a test drives. It is a fixture, not a second constructor: every field
 * a seal carries is set here explicitly so a test can edit one column and leave the rest canonical. */
public final class RangeSealFixture {

    public static final String WORLD = "minecraft:overworld";
    public static final String DOMAIN = "chunk-pipeline";
    public static final String MAILBOX = "worldgen";
    public static final long PLAN = 9L;
    public static final long FREEZE_ORDINAL = 3L;
    public static final long EPOCH = 5L;
    public static final long INSTANCE = 41L;
    public static final long TOKEN = 77L;
    public static final String STATUS = "full";
    public static final String LIFECYCLE = "host/1/0";
    public static final long CELL = 64L;
    public static final long CELL2 = 65L;

    public FrozenDeclarations.FreezeKey freeze = new FrozenDeclarations.FreezeKey(LIFECYCLE, 1L);
    public String world = WORLD;
    public String domain = DOMAIN;
    public long planSequence = PLAN;
    public long freezeOrdinal = FREEZE_ORDINAL;
    public int position = 4;
    public int role = 1;
    public String nodeKey = "chunk-pipeline/" + WORLD + "/" + MAILBOX + "#" + FREEZE_ORDINAL;
    public long[] cells = {CELL};
    public long cell = CELL;
    public String status = STATUS;
    public long instanceId = INSTANCE;
    public long worldEpoch = EPOCH;
    public long taskOrdinal = 1L;
    public long mintTick = 100L;
    public long demandToken = TOKEN;
    public String statusMapDigestOverride;
    public String spaceDigestOverride;

    public FrozenDeclarations.Seat seat() {
        return new FrozenDeclarations.Seat(world, domain, planSequence, position);
    }

    public FrozenDeclarations.Stamp stamp() {
        return new FrozenDeclarations.Stamp(taskOrdinal, world, MAILBOX, cell, mintTick,
            freezeOrdinal, planSequence, demandToken);
    }

    public Map<Long, FrozenDeclarations.CellStatus> statuses() {
        Map<Long, FrozenDeclarations.CellStatus> statuses = new LinkedHashMap<>();
        statuses.put(cell, new FrozenDeclarations.CellStatus(status, stamp()));
        return statuses;
    }

    public FrozenDeclarations.RangeSeal build() {
        long[] copy = cells.clone();
        Map<Long, FrozenDeclarations.CellStatus> statuses = statuses();
        String space = spaceDigestOverride != null ? spaceDigestOverride
            : RangeDigests.spaceDigest(copy);
        String mapDigest = statusMapDigestOverride != null ? statusMapDigestOverride
            : RangeDigests.statusMapDigest(statuses);
        return new FrozenDeclarations.RangeSeal(seat(), nodeKey, role, world, MAILBOX,
            freezeOrdinal, planSequence, freeze, copy, space, statuses, mapDigest, instanceId,
            worldEpoch, stamp());
    }

    public RangeSealFixture at(long newPlanSequence, int newPosition) {
        planSequence = newPlanSequence;
        position = newPosition;
        return this;
    }

    public RangeSealFixture freeze(FrozenDeclarations.FreezeKey key) {
        freeze = key;
        return this;
    }

    public RangeSealFixture cells(long... list) {
        cells = list;
        return this;
    }

    public RangeSealFixture cell(long one) {
        cell = one;
        return this;
    }

    public RangeSealFixture status(String one) {
        status = one;
        return this;
    }

    public RangeSealFixture instance(long one) {
        instanceId = one;
        return this;
    }

    public RangeSealFixture epoch(long one) {
        worldEpoch = one;
        return this;
    }

    public RangeSealFixture role(int one) {
        role = one;
        return this;
    }

    public RangeSealFixture node(String one) {
        nodeKey = one;
        return this;
    }

    public RangeSealFixture spaceDigest(String one) {
        spaceDigestOverride = one;
        return this;
    }

    public RangeSealFixture statusMapDigest(String one) {
        statusMapDigestOverride = one;
        return this;
    }
}

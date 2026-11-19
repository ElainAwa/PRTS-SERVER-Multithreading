/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.owner;

import io.izzel.arclight.common.prts.kernel.SharedRuntime;
import io.izzel.arclight.common.prts.kernel.jobs.WorkAccount;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

/** One deferred body as one offered work unit of the shared account: the identity is written before
 * the first submission, the unit is seated at its tick and position, and the first terminal answer
 * closes it. A refused identity or seat is terminal before any body runs. */
public final class OwnerUnitLedger {

    private final WorkAccount account;
    private final String unitId;

    private OwnerUnitLedger(WorkAccount account, String unitId) {
        this.account = account;
        this.unitId = unitId;
    }

    public static OwnerUnitLedger open(ServerLevel level, Entity entity, long tick, int position) {
        WorkAccount account = SharedRuntime.shared().account();
        String worldId = level.dimension().location().toString();
        String key = "entity-state:" + entity.getUUID().getMostSignificantBits() + ":"
            + entity.getUUID().getLeastSignificantBits();
        String unitId = WorkAccount.unitId(key, 0L);
        if (!account.offer(new WorkAccount.Unit(unitId, key, worldId, key))) {
            throw new IllegalStateException("owner batch unit was refused");
        }
        if (!account.seat(unitId, 0L, tick, position) || !account.markReady(unitId)) {
            account.close(unitId, WorkAccount.Outcome.REJECTED_BEFORE_EFFECT, "seat-refused");
            throw new IllegalStateException("owner batch unit was not seated");
        }
        return new OwnerUnitLedger(account, unitId);
    }

    public String unitId() {
        return unitId;
    }

    /** Moves the unit to running before the body; a refused transition is terminal. */
    public void started() {
        if (!account.markRunning(unitId)) {
            throw new IllegalStateException("owner batch unit was not started");
        }
    }

    public void close(WorkAccount.Outcome outcome, String code) {
        if (!account.close(unitId, outcome, code)) {
            throw new IllegalStateException("owner batch unit was closed twice");
        }
    }

    public boolean closed() {
        return account.closure(unitId) != null;
    }
}

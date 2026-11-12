/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.sites;

import io.izzel.arclight.common.prts.kernel.auth.HolderKind;
import io.izzel.arclight.common.prts.kernel.auth.OwnerRegistry;
import io.izzel.arclight.common.prts.kernel.auth.OwnerToken;
import io.izzel.arclight.common.prts.kernel.auth.WriteAuthority;
import io.izzel.arclight.common.prts.kernel.auth.WriteLedger;
import io.izzel.arclight.common.prts.kernel.auth.WriteLevel;
import io.izzel.arclight.common.prts.kernel.auth.WriteVersionSlots;
import io.izzel.arclight.common.prts.kernel.codes.WriteDisposition;
import io.izzel.arclight.common.prts.kernel.intent.IntentQueue;
import io.izzel.arclight.common.prts.support.PrtsWorldWriteTaps;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldWriteGuardSameRightsTest {

    private static final String WORLD = "w";

    @Test
    void anUncoveredMainWriteIsJudgedWhenVersionRightsAreActive() {
        Rig rig = new Rig();
        rig.versions.refresh(true);
        Object level = new Object();

        assertEquals(PrtsWorldWriteTaps.BlockWriteTap.JUDGE, rig.guard.classifyBlockWrite(level),
            "rights on, the host thread may not take the short path");
        assertFalse(rig.guard.admitBlockWrite(level, WORLD, () -> true),
            "a main write without a token must not proceed");
        assertEquals(WriteDisposition.DENY,
            rig.guard.lastDecision().disposition());
    }

    @Test
    void aMatchingTokenLetsTheMainWriteProceed() {
        Rig rig = new Rig();
        rig.versions.refresh(true);
        long version = rig.versions.grant(WORLD, WriteLevel.REGION,
            WritePath.BLOCK_WRITE.key());
        OwnerToken token = new OwnerToken(WORLD, WriteLevel.REGION, WritePath.BLOCK_WRITE.key(),
            1L, version, 5L, 100L, HolderKind.REGISTERED, "main");
        assertTrue(rig.owners.acquire(token));
        Object level = new Object();

        assertEquals(PrtsWorldWriteTaps.BlockWriteTap.JUDGE, rig.guard.classifyBlockWrite(level));
        assertTrue(rig.guard.admitBlockWrite(level, WORLD, () -> true),
            "a main write under its own token must proceed");

        assertTrue(rig.owners.release(token.domain(), "main"));
        assertEquals(PrtsWorldWriteTaps.BlockWriteTap.JUDGE, rig.guard.classifyBlockWrite(level));
        assertFalse(rig.guard.admitBlockWrite(level, WORLD, () -> true),
            "a main write after its token was released must not proceed");
    }

    @Test
    void anIntentOnAnUnregisteredWriteDoesNotProceedWhenRightsAreActive() throws Exception {
        Rig rig = new Rig();
        rig.versions.refresh(true);
        rig.guard.refresh(true, false, false, 5L);
        Object level = new Object();
        boolean[] admitted = new boolean[1];
        Thread worker = new Thread(() ->
            admitted[0] = rig.guard.admitBlockWrite(level, WORLD, () -> true), "test-worker");
        worker.start();
        worker.join(2000L);
        assertFalse(admitted[0], "an intent may not let the original write proceed");
    }

    @Test
    void rightsCanBeActiveWithoutTheWritePathGuardSwitch() {
        Rig rig = new Rig();
        rig.versions.refresh(true);
        rig.guard.refresh(false, false, false, 5L);
        Object level = new Object();

        assertEquals(PrtsWorldWriteTaps.BlockWriteTap.JUDGE, rig.guard.classifyBlockWrite(level),
            "rights on must judge even when the write-path guard switch is off");
        assertFalse(rig.guard.admitBlockWrite(level, WORLD, () -> true),
            "a main write without a token must not proceed just because the guard switch is off");
    }

    @Test
    void controlledRightsAloneAlsoJudgeTheHostThread() {
        Rig rig = new Rig();
        rig.versions.refresh(false);
        rig.controlled.refresh(true);
        rig.guard.refresh(false, false, false, 5L);
        Object level = new Object();

        assertEquals(PrtsWorldWriteTaps.BlockWriteTap.JUDGE, rig.guard.classifyBlockWrite(level),
            "controlled rights on must judge even with the other two switches off");
        assertFalse(rig.guard.admitBlockWrite(level, WORLD, () -> true),
            "a main write without a claim must not proceed under controlled rights");
    }

    @Test
    void rightsOffKeepsTheShortPathOfTheHostThread() {
        Rig rig = new Rig();
        rig.versions.refresh(false);
        Object level = new Object();

        assertEquals(PrtsWorldWriteTaps.BlockWriteTap.PASS, rig.guard.classifyBlockWrite(level),
            "rights off, the host thread keeps its observation path");
    }

    private static final class Rig {

        final OwnerRegistry owners = new OwnerRegistry();
        final WriteVersionSlots versions = new WriteVersionSlots();
        final IntentQueue intents = new IntentQueue(() -> 8, () -> 0);
        final WriteLedger ledger = new WriteLedger();
        final WriteAuthority authority =
            new WriteAuthority(owners, intents, ledger, () -> false, () -> 0);
        final WorldWriteGuard guard = new WorldWriteGuard(new WritePathCounters(), authority,
            intents, new IntentPayloadDirectory(), ledger);
        final WriteControlledSlots controlled =
            new WriteControlledSlots(owners, versions, guard.worldEpochs(),
                WriteControlledSlots.Source.NONE);

        Rig() {
            guard.versionSlots(versions);
            guard.controlledSlots(controlled);
            guard.bindServerThread(Thread.currentThread(), "main");
            guard.noteLiveWorlds(List.of(WORLD));
            guard.refresh(true, true, false, 5L);
        }
    }
}
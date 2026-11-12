/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.sites;

import io.izzel.arclight.common.prts.kernel.auth.HolderKind;
import io.izzel.arclight.common.prts.kernel.auth.OwnerRegistry;
import io.izzel.arclight.common.prts.kernel.auth.WriteLevel;
import io.izzel.arclight.common.prts.kernel.auth.WriteVersionSlots;
import io.izzel.arclight.common.prts.kernel.auth.OwnerRegistry.OwnershipDomain;
import io.izzel.arclight.common.prts.kernel.auth.OwnerToken;
import io.izzel.arclight.common.prts.kernel.auth.WriteAuthority;
import io.izzel.arclight.common.prts.kernel.auth.WriteLedger;
import io.izzel.arclight.common.prts.kernel.intent.IntentQueue;
import io.izzel.arclight.common.prts.support.PrtsWorldWriteTaps;
import net.minecraft.core.SectionPos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WriteControlledSlotsOverlapTest {

    private static final String WORLD = "w";

    @Test
    void aChildRangeOfAnotherHolderIsRefusedAtRegistration() {
        WriteControlledSlots table = table();
        WriteControlledSlots.Slot parent = slot("main", 1L, WriteControlledSlots.WriteSpace.of(
            WriteControlledSlots.CellKind.CHUNK, 0L));
        WriteControlledSlots.Slot child = slot("site:b", 1L, WriteControlledSlots.WriteSpace.of(
            WriteControlledSlots.CellKind.SECTION, SectionPos.asLong(0, 0, 0)));

        assertEquals(WriteControlledSlots.Refusal.NONE, table.register(parent));
        assertNotEquals(WriteControlledSlots.Refusal.NONE, table.register(child),
            "a child range of another holder overlaps the parent and must be refused");
        assertEquals(1, table.slots(), "the parent range must remain seated");
    }

    @Test
    void theReverseOrderIsRefusedTheSameWay() {
        WriteControlledSlots table = table();
        WriteControlledSlots.Slot parent = slot("main", 1L, WriteControlledSlots.WriteSpace.of(
            WriteControlledSlots.CellKind.CHUNK, 0L));
        WriteControlledSlots.Slot child = slot("site:b", 1L, WriteControlledSlots.WriteSpace.of(
            WriteControlledSlots.CellKind.SECTION, SectionPos.asLong(0, 0, 0)));

        assertEquals(WriteControlledSlots.Refusal.NONE, table.register(child));
        assertNotEquals(WriteControlledSlots.Refusal.NONE, table.register(parent),
            "a parent range of another holder must be refused too");
        assertEquals(1, table.slots());
    }

    @Test
    void disjointRangesOfDifferentHoldersAreAccepted() {
        WriteControlledSlots table = table();
        WriteControlledSlots.Slot parent = slot("main", 1L, WriteControlledSlots.WriteSpace.of(
            WriteControlledSlots.CellKind.CHUNK, 0L));
        WriteControlledSlots.Slot child = slot("site:b", 1L, WriteControlledSlots.WriteSpace.of(
            WriteControlledSlots.CellKind.SECTION, SectionPos.asLong(3, 0, 3)));

        assertEquals(WriteControlledSlots.Refusal.NONE, table.register(parent));
        assertEquals(WriteControlledSlots.Refusal.NONE, table.register(child));
        assertEquals(2, table.slots());
    }

    private static WriteControlledSlots table() {
        return new WriteControlledSlots(new OwnerRegistry(), new WriteVersionSlots(),
            new WorldEpochs(), WriteControlledSlots.Source.NONE);
    }

    private static WriteControlledSlots.Slot slot(String site, long generation,
                                                  WriteControlledSlots.WriteSpace space) {
        WriteControlledSlots.SlotKey key = new WriteControlledSlots.SlotKey(WORLD,
            WritePath.BLOCK_WRITE.key(), WriteLevel.REGION,
            WriteControlledSlots.SlotKey.segmentOf(space), 1L);
        return new WriteControlledSlots.Slot(key, new Object(), "node", 0, 7L, generation, 1L,
            100L, HolderKind.REGISTERED, site, Set.of(WritePath.BLOCK_WRITE.key()), space);
    }

    @Test
    void aTransferWhileACapturedWriteIsOpenIsRefused() {
        Rig rig = new Rig();
        rig.versions.refresh(true);
        long version = rig.versions.grant(WORLD, WriteLevel.REGION, WritePath.BLOCK_WRITE.key());
        rig.versions.noteGranted(1L, Map.of(
            new OwnershipDomain(WORLD, WriteLevel.REGION, WritePath.BLOCK_WRITE.key()), version));
        OwnerToken token = new OwnerToken(WORLD, WriteLevel.REGION, WritePath.BLOCK_WRITE.key(),
            1L, version, 5L, 100L, HolderKind.REGISTERED, "main");
        assertTrue(rig.owners.acquire(token));
        rig.controlled.refresh(true);

        WriteControlledSlots.WriteSpace space =
            WriteControlledSlots.WriteSpace.of(WriteControlledSlots.CellKind.CHUNK, 0L);
        WriteControlledSlots.Slot first = rig.slot("main", 1L, space, version);
        assertEquals(WriteControlledSlots.Refusal.NONE, rig.controlled.register(first));
        assertEquals(PrtsWorldWriteTaps.BlockWriteTap.JUDGE,
            rig.guard.classifyBlockWrite(rig.level, 1, 1, 1));

        WriteControlledSlots.Slot second = rig.slot("site:b", 2L, space, version);
        assertNotEquals(WriteControlledSlots.Refusal.NONE, rig.controlled.register(second),
            "the table may not transfer a range a captured write still holds");
        assertTrue(rig.guard.admitBlockWrite(rig.level, WORLD, () -> true),
            "the captured write still lands under the old generation");
        assertEquals(WriteControlledSlots.Refusal.NONE, rig.controlled.register(second),
            "a quiescent transfer is accepted");
    }

    @Test
    void aRefusedClaimDoesNotFallBackToTheOriginalWrite() {
        Rig rig = new Rig();
        rig.versions.refresh(true);
        long version = rig.versions.grant(WORLD, WriteLevel.REGION, WritePath.BLOCK_WRITE.key());
        rig.versions.noteGranted(1L, Map.of(
            new OwnershipDomain(WORLD, WriteLevel.REGION, WritePath.BLOCK_WRITE.key()), version));
        OwnerToken stale = new OwnerToken(WORLD, WriteLevel.REGION, WritePath.BLOCK_WRITE.key(),
            1L, version, 5L, 100L, HolderKind.REGISTERED, "main");
        assertTrue(rig.owners.acquire(stale));
        assertTrue(rig.owners.release(stale.domain(), "main"));
        OwnerToken current = new OwnerToken(WORLD, WriteLevel.REGION,
            WritePath.BLOCK_WRITE.key(), 2L, version, 5L, 100L, HolderKind.REGISTERED, "main");
        assertTrue(rig.owners.acquire(current));
        rig.controlled.refresh(true);
        rig.guard.refresh(true, false, false, 5L);

        WriteControlledSlots.WriteSpace space =
            WriteControlledSlots.WriteSpace.of(WriteControlledSlots.CellKind.CHUNK, 0L);
        WriteControlledSlots.Slot covered = rig.slot("main", 1L, space, version);
        assertEquals(WriteControlledSlots.Refusal.NONE, rig.controlled.register(covered, stale));
        assertEquals(PrtsWorldWriteTaps.BlockWriteTap.JUDGE,
            rig.guard.classifyBlockWrite(rig.level, 1, 1, 1));
        assertFalse(rig.guard.admitBlockWrite(rig.level, WORLD, () -> true),
            "a refused claim may not fall back to the unlimited write path");
    }

    private static final class Rig {

        final Object level = new Object();
        final OwnerRegistry owners = new OwnerRegistry();
        final WriteVersionSlots versions = new WriteVersionSlots();
        final IntentQueue intents = new IntentQueue(() -> 8, () -> 0);
        final WriteLedger ledger = new WriteLedger();
        final WriteAuthority authority =
            new WriteAuthority(owners, intents, ledger, () -> false, () -> 0);
        final WorldWriteGuard guard = new WorldWriteGuard(new WritePathCounters(), authority,
            intents, new IntentPayloadDirectory(), ledger);
        final WriteControlledSlots controlled =
            new WriteControlledSlots(owners, versions, guard.worldEpochs(), source());

        Rig() {
            guard.versionSlots(versions);
            guard.controlledSlots(controlled);
            guard.bindServerThread(Thread.currentThread(), "main");
            guard.noteLiveWorlds(List.of(WORLD));
            guard.refresh(true, true, false, 5L);
        }

        WriteControlledSlots.Slot slot(String site, long generation,
                                       WriteControlledSlots.WriteSpace space, long version) {
            WriteControlledSlots.SlotKey key = new WriteControlledSlots.SlotKey(WORLD,
                WritePath.BLOCK_WRITE.key(), WriteLevel.REGION,
                WriteControlledSlots.SlotKey.segmentOf(space), 1L);
            return new WriteControlledSlots.Slot(key, level, "node", 0, version, generation,
                guard.worldEpochs().epochOf(WORLD), 100L, HolderKind.REGISTERED, site,
                Set.of(WritePath.BLOCK_WRITE.key()), space);
        }

        private static WriteControlledSlots.Source source() {
            return new WriteControlledSlots.Source() {

                @Override
                public String worldOf(Object levelRef) {
                    return WORLD;
                }

                @Override
                public Step stepOf(String worldId, String domainId, String planNodeKey) {
                    return new Step(1L, 0);
                }
            };
        }
    }
}
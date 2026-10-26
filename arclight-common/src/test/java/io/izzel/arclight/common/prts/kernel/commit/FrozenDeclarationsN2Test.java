/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.commit;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The declaration seal rule: an entry whose declaration carries no target status is skipped per seat, and
 * every other seal check stays. The pipeline's own declarations carry no state today, so the table
 * they produce is NOT_WIRED (no seal) and this change never makes them reachable. */
class FrozenDeclarationsN2Test {

    private static final String WORLD = "world-a";
    private static final String DOMAIN = "chunk-pipeline";
    private static final String EMPTY_NODE = "chunk-pipeline/world-a/mailbox-empty";
    private static final String VALID_NODE = "chunk-pipeline/world-a/mailbox-valid";
    private static final long CHUNK = 64L;
    private static final long OTHER_CHUNK = 65L;
    private static final long INSTANCE = 1L;
    private static final long EPOCH = 7L;
    private static final long PLAN = 9L;
    private static final String STATUS = "full";

    private static FrozenDeclarations.FreezeKey freeze() {
        return new FrozenDeclarations.FreezeKey("lifecycle-a", 1L);
    }

    private static FrozenDeclarations.Candidate candidate(String node, long chunk, String status) {
        return new FrozenDeclarations.Candidate(WORLD, DOMAIN, node,
            new FrozenDeclarations.DeclaredChunk(chunk), status, INSTANCE, EPOCH);
    }

    private static FrozenDeclarations.Assignment place(String node, int position) {
        return new FrozenDeclarations.Assignment(WORLD, DOMAIN, node, position);
    }

    private static FrozenDeclarations.Seat seat(int position) {
        return new FrozenDeclarations.Seat(WORLD, DOMAIN, PLAN, position);
    }

    @Test
    void anEmptyStatusDeclarationAloneSealsNothing() {
        FrozenDeclarations.Sealed sealed = FrozenDeclarations.seal(freeze(), PLAN,
            List.of(candidate(EMPTY_NODE, CHUNK, "")), List.of(place(EMPTY_NODE, 0)));

        assertTrue(sealed.sealed(), sealed.refusal());
        FrozenDeclarations table = sealed.declarations();
        assertEquals(FrozenDeclarations.NOT_WIRED, table.state(),
            "a table with only empty-status declarations is not sealed");
        assertEquals(0, table.size());
        assertNull(table.entryAt(seat(0)), "no entry is written for a seat with no declared state");
    }

    @Test
    void aMixedTableKeepsTheValidEntryAndSkipsTheEmptyOne() {
        FrozenDeclarations.Sealed sealed = FrozenDeclarations.seal(freeze(), PLAN,
            List.of(candidate(EMPTY_NODE, CHUNK, ""), candidate(VALID_NODE, OTHER_CHUNK, STATUS)),
            List.of(place(EMPTY_NODE, 0), place(VALID_NODE, 1)));

        assertTrue(sealed.sealed(), sealed.refusal());
        FrozenDeclarations table = sealed.declarations();
        assertEquals(FrozenDeclarations.SEALED, table.state(),
            "the valid declaration still seals the table");
        assertEquals(1, table.size());
        assertNull(table.entryAt(seat(0)), "the empty-status seat stays absent");
        FrozenDeclarations.Entry entry = table.entryAt(seat(1));
        assertNotNull(entry, "the valid declaration keeps its entry");
        assertEquals(STATUS, entry.targetStatus());
        assertEquals(OTHER_CHUNK, entry.packedChunkPos());
        assertTrue(table.digest() != 0L);
    }

    @Test
    void anEmptyStatusSeatCannotReachATypedLineAndReplayIsStillStrict() {
        FrozenDeclarations.Sealed sealed = FrozenDeclarations.seal(freeze(), PLAN,
            List.of(candidate(EMPTY_NODE, CHUNK, "")), List.of(place(EMPTY_NODE, 0)));
        FrozenDeclarations table = sealed.declarations();
        assertNull(table.entryAt(seat(0)));
        assertTrue(table.entries().isEmpty(), "no entry means no status a typed line could carry");

        FrozenDeclarations.Entry empty = new FrozenDeclarations.Entry(seat(0), EMPTY_NODE, 1, CHUNK,
            "", INSTANCE, EPOCH);
        FrozenDeclarations.Sealed replayed = FrozenDeclarations.of(freeze(), PLAN, List.of(empty));
        assertFalse(replayed.sealed(), "the replay entry point still refuses an empty status");
        assertEquals("declaration-field-missing", replayed.refusal());
    }

    @Test
    void theOldResolutionAndReplayOfAValidTableAreUnchanged() {
        FrozenDeclarations.Sealed sealed = FrozenDeclarations.seal(freeze(), PLAN,
            List.of(candidate(VALID_NODE, OTHER_CHUNK, STATUS)),
            List.of(place(VALID_NODE, 0)));
        assertTrue(sealed.sealed(), sealed.refusal());
        FrozenDeclarations table = sealed.declarations();
        assertEquals(FrozenDeclarations.SEALED, table.state());
        assertEquals(1, table.size());

        FrozenDeclarations.Sealed replayed = FrozenDeclarations.of(freeze(), PLAN, table.entries());
        assertTrue(replayed.sealed(), replayed.refusal());
        assertEquals(table.digest(), replayed.declarations().digest(),
            "the replay of a valid table still reproduces the same fold");
        assertEquals(STATUS, replayed.declarations().entryAt(seat(0)).targetStatus());
    }
}

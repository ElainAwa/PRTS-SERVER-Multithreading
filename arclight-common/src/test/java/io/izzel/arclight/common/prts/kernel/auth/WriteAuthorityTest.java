/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.auth;

import io.izzel.arclight.common.prts.kernel.codes.RejectCode;
import io.izzel.arclight.common.prts.kernel.codes.RejectTrigger;
import io.izzel.arclight.common.prts.kernel.intent.CommitOrder;
import io.izzel.arclight.common.prts.kernel.intent.IntentQueue;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The decision matrix of the write decision point. */
class WriteAuthorityTest {

    private static final long TICK = 100L;

    @Test
    void anUnregisteredReadPassesAndItsWriteBecomesAnIntent() {
        Scratch scratch = new Scratch(false, 8);
        WriteVerdict read = scratch.authority.authorize(attempt(1L, "unregistered:t:0", "world",
            "world", WriteLevel.REGION, "region-1", HolderKind.UNREGISTERED, true, 7L).read()
            .build());
        WriteVerdict write = scratch.authority.authorize(attempt(2L, "unregistered:t:0", "world",
            "world", WriteLevel.REGION, "region-1", HolderKind.UNREGISTERED, true, 7L).build());

        assertEquals(WriteDisposition.GRANT, read.disposition());
        assertEquals(WriteDisposition.INTENT, write.disposition());
        assertEquals(1, scratch.intents.depth());
        assertEquals(0L, scratch.ledger.unregisteredGrants());
        assertEquals(1L, scratch.ledger.unregisteredAttempts());
        assertEquals(1, scratch.ledger.pairs().size());
    }

    @Test
    void enforcementRefusesAnUnregisteredWriteInsteadOfQueueingIt() {
        Scratch scratch = new Scratch(true, 8);
        WriteVerdict verdict = scratch.authority.authorize(attempt(1L, "unregistered:t:0", "world",
            "world", WriteLevel.REGION, "region-1", HolderKind.UNREGISTERED, true, 7L).build());

        assertEquals(WriteDisposition.DENY, verdict.disposition());
        assertEquals(RejectCode.WRITE_DENIED_NOT_OWNER, verdict.code());
        assertEquals(0, scratch.intents.depth());
        assertEquals(0L, scratch.ledger.unregisteredGrants());
        assertEquals(1L, scratch.ledger.codeCount(RejectCode.WRITE_DENIED_NOT_OWNER));
    }

    @Test
    void aRegisteredHolderIsJudgedOnOwnerVersionAndDomainSet() {
        Scratch scratch = new Scratch(false, 8);
        scratch.owners.acquire(new OwnerToken("world", WriteLevel.REGION, "region-1", 1L, 7L, TICK,
            TICK + 50L, HolderKind.REGISTERED, "site:a"));

        WriteVerdict granted = scratch.authority.authorize(attempt(1L, "site:a", "world", "world",
            WriteLevel.REGION, "region-1", HolderKind.REGISTERED, true, 7L).build());
        WriteVerdict wrongSite = scratch.authority.authorize(attempt(2L, "site:b", "world", "world",
            WriteLevel.REGION, "region-1", HolderKind.REGISTERED, true, 7L).build());
        WriteVerdict wrongVersion = scratch.authority.authorize(attempt(3L, "site:a", "world",
            "world", WriteLevel.REGION, "region-1", HolderKind.REGISTERED, true, 6L).build());
        WriteVerdict wrongDomain = scratch.authority.authorize(attempt(4L, "site:a", "world",
            "world", WriteLevel.REGION, "region-2", HolderKind.REGISTERED, true, 7L).build());
        WriteVerdict undeclared = scratch.authority.authorize(attempt(5L, "site:a", "world",
            "world", WriteLevel.REGION, "region-1", HolderKind.REGISTERED, false, 7L).build());

        assertEquals(WriteDisposition.GRANT, granted.disposition());
        assertEquals(WriteDisposition.DENY, wrongSite.disposition());
        assertEquals(RejectCode.WRITE_DENIED_NOT_OWNER, wrongSite.code());
        assertEquals(WriteDisposition.DENY, wrongVersion.disposition());
        assertEquals(RejectCode.VERSION_MISMATCH, wrongVersion.code());
        assertEquals(WriteDisposition.DENY, wrongDomain.disposition());
        assertEquals(RejectCode.WRITE_DENIED_NOT_OWNER, wrongDomain.code());
        assertEquals(WriteDisposition.DENY, undeclared.disposition());
        assertEquals(RejectCode.NATIVE_UNDECLARED, undeclared.code());
    }

    @Test
    void anExpiredTokenIsReclaimedAndRefused() {
        Scratch scratch = new Scratch(false, 8);
        scratch.owners.acquire(new OwnerToken("world", WriteLevel.REGION, "region-1", 1L, 7L, TICK,
            TICK + 5L, HolderKind.REGISTERED, "site:a"));

        WriteVerdict verdict = scratch.authority.authorize(attempt(1L, "site:a", "world", "world",
            WriteLevel.REGION, "region-1", HolderKind.REGISTERED, true, 7L).tick(TICK + 6L)
            .build());

        assertEquals(WriteDisposition.DENY, verdict.disposition());
        assertEquals(RejectCode.VERSION_MISMATCH, verdict.code());
        assertEquals(1L, scratch.owners.expiredReclaimedCount());
    }

    @Test
    void aCrossWorldWriteGoesThroughTheIntentQueue() {
        Scratch scratch = new Scratch(false, 8);
        WriteVerdict verdict = scratch.authority.authorize(attempt(1L, "site:a", "world", "other",
            WriteLevel.REGION, "region-1", HolderKind.REGISTERED, true, 7L).build());

        assertEquals(WriteDisposition.INTENT, verdict.disposition());
        assertEquals(RejectCode.CROSS_WORLD_WRITE_DENIED, verdict.code());
        assertTrue(verdict.intentRef() > 0L);
        assertEquals(1, scratch.intents.depth());
    }

    @Test
    void aFullIntentQueueRefusesInsteadOfGrowing() {
        Scratch scratch = new Scratch(false, 2);
        for (int index = 1; index <= 3; index++) {
            scratch.authority.authorize(attempt(index, "unregistered:t:0", "world", "world",
                WriteLevel.REGION, "region-" + index, HolderKind.UNREGISTERED, true, 7L,
                index).build());
        }

        assertEquals(2, scratch.intents.depth());
        assertEquals(1L, scratch.intents.rejectedFullCount());
        assertEquals(1L, scratch.ledger.codeCount(RejectCode.QUEUE_CAP_EXCEEDED));
        assertEquals(2L, scratch.ledger.totalIntent());
        assertEquals(1L, scratch.ledger.totalDenied());
        assertEquals(3L, scratch.ledger.totalAttempts());
    }

    @Test
    void theLedgerClosesForEveryWorldAndSitePair() {
        Scratch scratch = new Scratch(false, 8);
        scratch.owners.acquire(new OwnerToken("world", WriteLevel.REGION, "region-1", 1L, 7L, TICK,
            TICK + 50L, HolderKind.REGISTERED, "site:a"));
        scratch.authority.authorize(attempt(1L, "site:a", "world", "world", WriteLevel.REGION,
            "region-1", HolderKind.REGISTERED, true, 7L).build());
        scratch.authority.authorize(attempt(2L, "site:b", "world", "world", WriteLevel.REGION,
            "region-1", HolderKind.REGISTERED, true, 7L).build());
        scratch.authority.authorize(attempt(3L, "unregistered:t:0", "world", "world",
            WriteLevel.REGION, "region-1", HolderKind.UNREGISTERED, true, 7L).build());

        assertTrue(scratch.ledger.verifyClosure());
        assertTrue(scratch.ledger.accountingOk());
        for (WriteLedger.Pair pair : scratch.ledger.pairs().values()) {
            assertEquals(pair.attempts(), pair.granted() + pair.intent() + pair.denied());
        }
        assertEquals(scratch.ledger.totalAttempts(),
            scratch.ledger.totalGranted() + scratch.ledger.totalIntent()
                + scratch.ledger.totalDenied());
    }

    @Test
    void aSecondHolderForTheSameDomainIsCountedAndRefused() {
        Scratch scratch = new Scratch(false, 8);
        OwnerToken first = new OwnerToken("world", WriteLevel.REGION, "region-1", 1L, 7L, TICK,
            TICK + 50L, HolderKind.REGISTERED, "site:a");
        OwnerToken second = new OwnerToken("world", WriteLevel.REGION, "region-1", 2L, 7L, TICK,
            TICK + 50L, HolderKind.REGISTERED, "site:b");

        assertTrue(scratch.owners.acquire(first));
        assertFalse(scratch.owners.acquire(second));
        assertEquals(1L, scratch.owners.doubleHolderCount());
        assertEquals("site:a", scratch.owners.lookup("world", WriteLevel.REGION, "region-1")
            .orElseThrow().holderSiteId());
    }

    @Test
    void theIntentSegmentKeepsTheFrozenOrder() {
        Scratch scratch = new Scratch(false, 8);
        scratch.authority.authorize(attempt(1L, "unregistered:t:0", "world", "world",
            WriteLevel.REGION, "region-1", HolderKind.UNREGISTERED, true, 7L, 4L).build());
        scratch.authority.authorize(attempt(2L, "unregistered:t:0", "world", "world",
            WriteLevel.REGION, "region-2", HolderKind.UNREGISTERED, true, 7L, 5L).build());

        CommitOrder outOfOrder = scratch.intents.commit(5L);
        CommitOrder first = scratch.intents.commit(4L);
        CommitOrder second = scratch.intents.commit(5L);

        assertEquals(RejectCode.COMMIT_ORDER_VIOLATION, outOfOrder.code());
        assertTrue(first.committed());
        assertTrue(second.committed());
        assertEquals(1L, scratch.intents.orderViolationCount());
        assertEquals(2L, scratch.intents.committedCount());
        assertEquals(0, scratch.intents.depth());
    }

    @Test
    void theTriggerTableMapsTwentyNineRowsOntoTheExistingCodes() {
        Set<RejectCode> writeCodes = new HashSet<>();
        Set<RejectCode> allCodes = new HashSet<>();
        int rows = 0;
        for (RejectTrigger trigger : RejectTrigger.values()) {
            rows++;
            if (trigger.code() == null) {
                assertEquals(RejectTrigger.RESERVE_BORROWED, trigger);
                continue;
            }
            allCodes.add(trigger.code());
            if (trigger.scope() == RejectTrigger.Scope.WRITE) {
                writeCodes.add(trigger.code());
            }
        }

        assertEquals(29, rows);
        assertEquals(9, writeCodes.size());
        assertEquals(20, RejectCode.values().length);
        for (RejectCode code : allCodes) {
            assertNotNull(code);
        }
        assertNull(RejectTrigger.RESERVE_BORROWED.code());
        assertEquals(RejectCode.COMMIT_ORDER_VIOLATION,
            RejectTrigger.INTENT_ORDER_VIOLATION.code());
        assertEquals(WriteDisposition.INTENT, RejectTrigger.UNREGISTERED_WRITE.disposition());
    }

    private static WriteAttempt.Builder attempt(long id, String site, String world,
                                                String declaredWorld, WriteLevel level,
                                                String domain, HolderKind kind, boolean admitted,
                                                long version) {
        return attempt(id, site, world, declaredWorld, level, domain, kind, admitted, version, 0L);
    }

    private static WriteAttempt.Builder attempt(long id, String site, String world,
                                                String declaredWorld, WriteLevel level,
                                                String domain, HolderKind kind, boolean admitted,
                                                long version, long order) {
        return WriteAttempt.builder(id, world, site)
            .holder(kind, site, "thread-" + site)
            .target(level, domain)
            .declaredWorld(declaredWorld)
            .domains(Set.of(domain), Set.of(domain))
            .version(version, TICK, order)
            .admitted(admitted);
    }

    /** Scratch objects so no test can touch the runtime of the process. */
    private static final class Scratch {

        private final OwnerRegistry owners = new OwnerRegistry();
        private final IntentQueue intents;
        private final WriteLedger ledger = new WriteLedger();
        private final WriteAuthority authority;

        private Scratch(boolean enforce, int capacity) {
            this.intents = new IntentQueue(() -> capacity);
            this.authority = new WriteAuthority(owners, intents, ledger, () -> enforce, () -> 2);
        }
    }
}

/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel;

import io.izzel.arclight.common.prts.kernel.commit.CommitController;
import io.izzel.arclight.common.prts.kernel.commit.CommitLog;
import io.izzel.arclight.common.prts.kernel.commit.CommitNamespaces;
import io.izzel.arclight.common.prts.kernel.commit.CommitReservation;
import io.izzel.arclight.common.prts.kernel.commit.CommitTicket;
import io.izzel.arclight.common.prts.kernel.commit.EffectStore;
import io.izzel.arclight.common.prts.kernel.commit.FrozenDeclarations;
import io.izzel.arclight.common.prts.kernel.config.ExecutableProductFace;
import io.izzel.arclight.common.prts.kernel.observe.KernelReadings;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import io.izzel.arclight.common.prts.kernel.commit.WitnessSource;

/** One chain is taken whole or not at all. The five checks of an install are references — the log
 * the holder mints in, the store that log carries, the one plan authority of the module, the owner
 * thread the host named and the room the store declared — and every failure leg leaves the module
 * on its own log, with no controller and with NOT_WIRED still readable. */
class CommitChainBindingTest {

    private static final String WORLD = "world-a";
    private static final String DOMAIN = "entity";
    private static final String NODE = "a/0";
    private static final long EFFECT = 9L;

    private static final class ForeignPlans implements CommitLog.OrderSource {

        @Override
        public CommitLog.Resolved resolve(String worldId, String domainId, String nodeKey) {
            return WORLD.equals(worldId) && DOMAIN.equals(domainId) && NODE.equals(nodeKey)
                ? new CommitLog.Resolved(7L, 0, false) : null;
        }
    }

    /** A whole chain of the shape a caller builds before it asks the module to adopt it. */
    private static final class Chain {

        final KernelModule module = KernelModule.instance();
        final ForeignPlans plans = new ForeignPlans();
        final EffectStore store = new EffectStore(4096, 8);
        final CommitLog log;
        final CommitNamespaces.ResetEpochs epochs = new CommitNamespaces.ResetEpochs();
        final CommitReservation reservations;
        final CommitController controller;

        Chain(CommitLog.OrderSource source) {
            this(source, null);
        }

        Chain(CommitLog.OrderSource source, CommitController.Publisher publisher) {
            log = new CommitLog(() -> 4, source, store,
                new WitnessSource(plans, WORLD, DOMAIN, 3L, "published", 5L, 7L, NODE).bind(11L));
            reservations = new CommitReservation(log, store, plans, "owner-a", epochs);
            controller = new CommitController(reservations, publisher);
        }

        CommitTicket.ReserveRequest request() {
            CommitNamespaces.Namespace producer =
                new CommitNamespaces.Namespace("producer", 5150L, 0L, 77L);
            CommitNamespaces.Namespace namespace = reservations.controller();
            CommitLog.Identity identity = new CommitLog.Identity(CommitLog.SCHEMA_TYPED, 11L, 5L, 3L,
                "published", 7L, EFFECT, producer, namespace);
            EffectStore.Effect variant =
                new EffectStore.Effect(EFFECT, "publish", "PUBLISHED", List.of("a=1", "b=2"));
            return new CommitTicket.ReserveRequest(WORLD, DOMAIN, NODE, CommitLog.Batch.Kind.APPLY,
                7L, 0, 7L, 4242L, 2, identity, namespace, variant, variant, variant);
        }
    }

    @BeforeEach
    void switchTheKernelOnAndNameTheOwnerThread() throws IOException {
        System.setProperty("arclight.prts.kernel", "true");
        // The declared face is written for the length of one test, and one tick is what names the
        // thread a chain is installed on: the install is owner-only and names no thread of its own.
        ExecutableProductFace.declare();
        KernelModule.instance().serverTick(List.of(WORLD));
    }

    @AfterEach
    void leaveTheModuleAsItWas() throws IOException {
        System.clearProperty("arclight.prts.kernel");
        KernelModule module = KernelModule.instance();
        module.removeWaitSiteTap();
        module.removeWritePathTap();
        module.resetReadings();
        module.rollbackCommitChain();
        ExecutableProductFace.restore();
    }

    private static String reading(String key) {
        for (String row : KernelReadings.export(KernelModule.instance())) {
            if (row.startsWith(key + "=")) {
                return row.substring(key.length() + 1);
            }
        }
        return "<missing>";
    }

    /** Every account one refused request or one refused install could move, as one reading. What the
     * refusal is allowed to move is read apart from it. */
    private static String ledger(Chain c) {
        return "controller[submits=" + c.controller.submits() + " supplierCalls="
            + c.controller.supplierCalls() + " publishFailures=" + c.controller.publishFailures()
            + " acceptedPublished=" + c.controller.acceptedPublished() + " refusals="
            + c.controller.refusals() + " resets=" + c.controller.resets() + "]"
            + " reservations[reserveSuccess=" + c.reservations.reserveSuccess() + " finalized="
            + c.reservations.finalized() + " released=" + c.reservations.releasedCount()
            + " outstanding=" + c.reservations.outstanding() + " publishing="
            + c.reservations.publishing() + " pending=" + c.reservations.pendingCount() + " ledger="
            + c.reservations.terminalLedger().size() + " failed=" + c.reservations.failedTotal()
            + " conservation=" + c.reservations.conservationHolds() + "]"
            + " log[sequence=" + c.log.sequence() + " dropped=" + c.log.dropped() + " ticketStored="
            + c.log.ticketStored() + " ringDepth=" + c.log.ringDepth() + " taken="
            + c.log.reservationsTaken() + " consumed=" + c.log.reservationsConsumed()
            + " released=" + c.log.reservationsReleased() + " heldPlans=" + c.log.heldPlans() + "]"
            + " store[capacitySlots=" + c.store.capacitySlots() + " capacityFinalized="
            + c.store.capacityFinalized() + " records=" + c.store.records() + " pins="
            + c.store.pins() + "]"
            + " epoch=" + c.epochs.current();
    }

    /** A controlled fixture and nothing more: this tick's plan carries no artefact, so what it shows
     * is the generation boundary of the holder and not a product declaration. It is not a CAP-41
     * input, no real supplier takes part, and no instance is run. */
    @Test
    void aPlanWithoutArtefactsSealsNoTableAndEveryLifecycleBoundaryMovesTheGeneration() {
        KernelModule module = KernelModule.instance();
        Chain c = new Chain(module.commitOrderSource());

        // The holder is one for the whole process, so the first boundary of this case ends whatever
        // freeze a case before it left standing: what is read below is this case's own state.
        long generation = module.declarationGeneration();
        module.resetReadings();
        assertEquals(generation + 1L, module.declarationGeneration(),
            "reading a reset takes the holder one generation on");
        assertEquals(FrozenDeclarations.NOT_WIRED, module.declarations().state(),
            "the plan of this tick declared no artefact, so no entry was sealed");
        assertEquals(0, module.declarations().size(), "and an empty plan is not read as a table");
        int retained = module.declarationSnapshotsRetained();
        long retired = module.declarationSnapshotsRetired();
        long withheld = module.declarationSnapshotsWithheld();

        assertTrue(module.installCommitChain(c.log, c.controller).bound());
        assertEquals(generation + 2L, module.declarationGeneration(), "so does an install");
        assertTrue(module.rollbackCommitChain());
        assertEquals(generation + 3L, module.declarationGeneration(), "and so does a rollback");
        assertEquals(FrozenDeclarations.NOT_WIRED, module.declarations().state(),
            "no boundary makes a table out of a plan that carried no artefact");
        assertEquals(generation + 3L, module.declarationGeneration(),
            "and none of them hands a number back");
        // Neither of the two boundaries replaced a freeze that carried an entry, so the retention had
        // no table to keep: the counters are read across the boundaries, never taken for zero.
        assertEquals(retained, module.declarationSnapshotsRetained(), "no snapshot was kept");
        assertEquals(retired, module.declarationSnapshotsRetired(), "none was retired");
        assertEquals(withheld, module.declarationSnapshotsWithheld(), "and nothing was owed");
        assertEquals(String.valueOf(withheld), reading("product.commit.declaration_snapshots_withheld"),
            "the reading is the counter this case did not move");
    }

    @Test
    void aChainIsTakenWholeAndTheFiveChecksAreReadBackInsteadOfAssumed() {
        Chain c = new Chain(KernelModule.instance().commitOrderSource());
        KernelModule module = c.module;
        CommitLog before = module.commits();

        KernelModule.CommitBinding binding = module.installCommitChain(c.log, c.controller);

        assertTrue(binding.bound(), "every check passed, so the whole chain is taken");
        assertEquals("", binding.refusal());
        assertEquals(c.log.logLifetime(), binding.logLifetime());
        assertEquals(c.store.storeId(), binding.storeId());
        assertSame(c.log, module.commits(), "the module adopts the log of the chain");
        assertSame(c.reservations.log(), module.commits(),
            "and the holder mints its tickets in that very log: one chain, not two books");
        assertSame(c.store, c.log.effects(), "the store of the chain is the store its log carries");
        assertSame(module.commitOrderSource(), c.log.orderSource(),
            "and the plan authority of the log is the one instance of the module");
        assertSame(Thread.currentThread(), c.reservations.ownerThread(),
            "the owner is the thread the host named, not a thread a shape suggested");
        assertTrue(module.commitChainIsTheOneLog());
        assertTrue(module.commitOrderSourceBound());
        assertSame(c.controller, module.commitController());

        assertEquals("BOUND", reading("product.commit.controller_wiring"));
        assertEquals("NONE", reading("product.commit.controller_publisher"));
        assertEquals("0", reading("product.commit.controller_executable"),
            "bound and able to run are two columns");
        assertEquals("1", reading("product.commit.binding_chain_log"));
        assertEquals("1", reading("product.commit.binding_order_source"));
        assertEquals("1", reading("product.commit.owner_thread_bound"));
        assertEquals("host:server-thread", reading("product.commit.owner_thread"),
            "the owner is read by the site the host bound, never by a thread name");
        assertEquals("-", reading("product.commit.binding_refusal"));
        assertEquals(String.valueOf(c.log.logLifetime()),
            reading("product.commit.binding_log_lifetime"));
        assertEquals(String.valueOf(c.store.storeId()), reading("product.commit.binding_store_id"));
        assertFalse(before == module.commits(), "the module moved off the log it built for itself");
    }

    @Test
    void everyFailedCheckIsRefusedWholeAndLeavesTheModuleOnItsOwnLog() {
        KernelModule module = KernelModule.instance();
        CommitLog own = module.commits();

        Chain good = new Chain(module.commitOrderSource());
        Chain other = new Chain(module.commitOrderSource());
        Chain foreignSource = new Chain(new ForeignPlans());
        Chain foreignStore = new Chain(module.commitOrderSource());
        // The room of this store is promised small and then outgrown, which is what halts it.
        EffectStore haltedStore = new EffectStore(4096, 4);
        CommitLog haltedLog = new CommitLog(() -> 4, module.commitOrderSource(), haltedStore);
        CommitReservation haltedReservations = new CommitReservation(haltedLog, haltedStore,
            new ForeignPlans(), "owner-a", new CommitNamespaces.ResetEpochs());
        haltedStore.finalizeCapacity(haltedStore.reserveCapacity(3L, 1, 64).slot(),
            new EffectStore.Effect(3L, "publish", "FAILED", List.of("a=1", "b=2")));
        assertTrue(haltedStore.halted());

        EffectStore secondStore = new EffectStore(4096, 8);
        CommitLog mismatchedStoreLog = new CommitLog(() -> 4, module.commitOrderSource(), secondStore);
        CommitReservation mismatchedStore = new CommitReservation(mismatchedStoreLog, good.store,
            new ForeignPlans(), "owner-a", new CommitNamespaces.ResetEpochs());

        assertEquals("chain-log-mismatch", refuse(module, other.log, good.controller));
        assertEquals("chain-store-mismatch", refuse(module, mismatchedStoreLog, new CommitController(
            mismatchedStore, null)));
        assertEquals("order-source-mismatch", refuse(module, foreignSource.log,
            foreignSource.controller));
        assertEquals("store-halted", refuse(module, haltedLog, new CommitController(
            haltedReservations, null)));
        assertEquals("chain-log-missing", refuse(module, null, good.controller));
        assertEquals("controller-missing", refuse(module, good.log, null));

        Chain ownerless = new Chain(module.commitOrderSource());
        AtomicReference<String> offThread = new AtomicReference<>();
        Thread asker = new Thread(() -> offThread.set(module.installCommitChain(ownerless.log,
            ownerless.controller).refusal()));
        asker.start();
        join(asker);
        assertEquals("off-owner-thread", offThread.get(), "the gate has no exception for a caller");

        assertNull(module.commitController(), "no controller was taken by any refused leg");
        assertSame(own, module.commits(), "and the module is still on the log it built for itself");
        assertFalse(module.commitChainIsTheOneLog());
        assertEquals("NOT_WIRED", reading("product.commit.controller_wiring"));
        assertEquals("0", reading("product.commit.binding_chain_log"));
        assertEquals("not-wired", ownerless.controller.submit(ownerless.request()).diagnostic(),
            "and a chain that was not adopted still has no action to run");
        Chain declined = new Chain(module.commitOrderSource());
        assertEquals("declared-schema-mismatch", refuseOnAnUndeclaredFace(declined, false));
        assertEquals("declared-face-off", refuseOnAnUndeclaredFace(declined, true));
    }

    private static String refuse(KernelModule module, CommitLog candidate, CommitController controller) {
        KernelModule.CommitBinding binding = module.installCommitChain(candidate, controller);
        assertFalse(binding.bound(), "a candidate that fails one check is refused");
        assertEquals(0L, binding.logLifetime(), "and it names no identity it did not take");
        assertEquals(0L, binding.storeId());
        assertEquals(binding.refusal(), reading("product.commit.binding_refusal"));
        return binding.refusal();
    }

    /** The declared face is the last check: with it off, even a chain that is right in every other
     * respect is refused, and the refusal names the declaration rather than a piece of the chain. */
    private static String refuseOnAnUndeclaredFace(Chain c, boolean switchedOnWithoutRoom) {
        KernelModule module = KernelModule.instance();
        module.rollbackCommitChain();
        try {
            if (switchedOnWithoutRoom) {
                ExecutableProductFace.declareWithoutRoom();
            } else {
                ExecutableProductFace.restore();
            }
            KernelModule.CommitBinding binding = module.installCommitChain(c.log, c.controller);
            assertFalse(binding.bound(), "a declaration that is not executable is not obeyed");
            return binding.refusal();
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static void join(Thread thread) {
        try {
            thread.join(30_000L);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        assertFalse(thread.isAlive(), "the asking thread finished");
    }

    @Test
    void aSecondChainIsNotTakenWhileOneIsBound() {
        Chain first = new Chain(KernelModule.instance().commitOrderSource());
        Chain second = new Chain(KernelModule.instance().commitOrderSource());
        KernelModule module = first.module;

        assertTrue(module.installCommitChain(first.log, first.controller).bound());
        KernelModule.CommitBinding again = module.installCommitChain(second.log, second.controller);

        assertFalse(again.bound(), "a module holds one chain, and it is not swapped under it");
        assertEquals("already-bound", again.refusal());
        assertSame(first.log, module.commits(), "the chain that was taken stays the one it took");
        assertSame(first.controller, module.commitController());
    }

    private static FrozenDeclarations.Candidate declared(String worldId, String domainId,
                                                         String nodeKey,
                                                         FrozenDeclarations.DeclaredChunk chunk) {
        return new FrozenDeclarations.Candidate(worldId, domainId, nodeKey, chunk, "published", 1L, 7L);
    }

    /** Two nodes whose three names concatenate to one text stay two nodes: the names are compared as
     * themselves, so one domain is never read out of another's namespace and the seal is not refused
     * as a duplicate of a node it never carried. */
    @Test
    void twoNodesOfOneSplicedTextStayTwoNodes() {
        FrozenDeclarations.FreezeKey freeze = new FrozenDeclarations.FreezeKey("lifecycle-a", 3L);
        FrozenDeclarations.DeclaredChunk chunk =
            new FrozenDeclarations.DeclaredChunk(ChunkPos.asLong(3, -7));
        List<FrozenDeclarations.Candidate> candidates = List.of(
            declared("a/b", "c", "k", chunk),
            declared("a", "b/c", "k", chunk),
            declared("w", "d1", "k", chunk),
            declared("w", "d2", "k", chunk));
        List<FrozenDeclarations.Assignment> places = List.of(
            new FrozenDeclarations.Assignment("a/b", "c", "k", 0),
            new FrozenDeclarations.Assignment("a", "b/c", "k", 1),
            new FrozenDeclarations.Assignment("w", "d1", "k", 2),
            new FrozenDeclarations.Assignment("w", "d2", "k", 3));

        FrozenDeclarations.Sealed sealed = FrozenDeclarations.seal(freeze, 7L, candidates, places);

        assertTrue(sealed.sealed(), "the seal holds: " + sealed.refusal());
        assertEquals(4, sealed.declarations().size(), "four nodes and no collision between them");
        Set<String> nodes = new TreeSet<>();
        for (FrozenDeclarations.Entry entry : sealed.declarations().entries()) {
            nodes.add(entry.seat().worldId() + "|" + entry.seat().domainId() + "|" + entry.nodeKey());
        }
        assertEquals(4, nodes.size(), "each of the four is its own node: " + nodes);
    }
}

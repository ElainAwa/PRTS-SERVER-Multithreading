/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.wiring;

import io.izzel.arclight.common.prts.config.PrtsConfigManager;
import io.izzel.arclight.common.prts.kernel.KernelModule;
import io.izzel.arclight.common.prts.kernel.commit.CommitLog;
import io.izzel.arclight.common.prts.kernel.commit.CommitRing;
import io.izzel.arclight.common.prts.kernel.config.ExecutableProductFace;
import io.izzel.arclight.common.prts.kernel.config.KernelSettings;
import io.izzel.arclight.common.prts.kernel.diff.StateHasher;
import io.izzel.arclight.common.prts.kernel.dispatch.DispatchReadings;
import io.izzel.arclight.common.prts.kernel.dispatch.DispatchWriteBack;
import io.izzel.arclight.common.prts.kernel.domain.entity.EntityCandidateView;
import io.izzel.arclight.common.prts.kernel.domain.entity.WorkPlan;
import io.izzel.arclight.common.prts.kernel.intent.IntentQueue;
import io.izzel.arclight.common.prts.kernel.observe.KernelReadings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The production commit-sink wiring of the entity domain: the constructor binds a sink into the
 * domain's write back, the sink resolves the current commit log on every report, and it is gated by
 * the commit-log switch exactly like the kernel's own landed producers. The readout integration
 * drives the real review through a server tick with a one-slot ring, all in process.
 */
class EntityDomainCommitSinkTest {

    private static final String WORLD = "minecraft:overworld";
    private static final Path CONFIG_DIR = PrtsConfigManager.directory();
    private static final Field ENTITY_WRITE_BACK = field(EntityDomain.class, "writeBack");
    private static final Field WRITE_BACK_SINK = field(DispatchWriteBack.class, "commitSink");
    private static final Field MODULE_COMMITS = field(KernelModule.class, "commits");
    private static final String RING_READING = "safety.kind.version-conflict.site.commit_ring_entity";
    private static final String RING_DOMAIN_READING = RING_READING + ".domain";

    private KernelModule module;
    private CommitLog originalCommits;

    @BeforeEach
    void quietTheObservationPlane() throws Exception {
        System.setProperty("arclight.prts.kernel", "true");
        module = KernelModule.instance();
        module.rollbackCommitChain();
        configure(false, false);
        module.resetReadings();
        originalCommits = (CommitLog) MODULE_COMMITS.get(module);
        KernelWiring.install();
    }

    @AfterEach
    void restoreTheDeclaredDefaults() throws Exception {
        MODULE_COMMITS.set(module, originalCommits);
        module.rollbackCommitChain();
        module.removeWaitSiteTap();
        module.removeWritePathTap();
        module.removeLoadProbe();
        module.safety().reset();
        module.resetReadings();
        System.clearProperty("arclight.prts.kernel");
        ExecutableProductFace.restore();
    }

    @Test
    void theProductionWiringBindsACommitSink() throws Exception {
        EntityDomain constructed = new EntityDomain(module);
        assertNotNull(productionSink(constructed), "the domain constructor binds the sink");
        EntityDomain installed = KernelWiring.install();
        assertNotNull(productionSink(installed), "the wiring entry binds the sink");
        assertSame(installed, module.domains().stream()
            .filter(domain -> domain.id().equals("entity")).findFirst().orElse(null),
            "the wiring entry installs the domain it bound");
    }

    @Test
    void theCommitLogGateReadsTheCurrentSwitchOnEveryReport() throws Exception {
        configure(false, false);
        assertTrue(KernelSettings.dispatchTakeover(), "the takeover tier is the one this test drives");
        assertFalse(KernelSettings.commitLog());
        DispatchWriteBack writeBack = writeBack(productionSink(KernelWiring.install()),
            KernelSettings::dispatchTakeover);
        CommitLog log = module.commits();
        long unplanned = log.unplanned();
        long held = log.held();
        assertEquals(DispatchWriteBack.Settlement.ACCEPTED, writeBack.settle(batch(11L), rows(11L)));
        assertEquals(unplanned, log.unplanned(), "the switched-off commit log was fed a report");
        assertEquals(held, log.held(), "the switched-off commit log stored a line");

        configure(true, false);
        assertTrue(KernelSettings.commitLog());
        unplanned = log.unplanned();
        held = log.held();
        assertEquals(DispatchWriteBack.Settlement.ACCEPTED, writeBack.settle(batch(12L), rows(12L)));
        assertEquals(unplanned + 1L, log.unplanned(), "the switched-on commit log missed the report");
        assertEquals(held + 1L, log.held(), "the switched-on commit log did not store the line");

        configure(false, false);
        assertFalse(KernelSettings.commitLog());
        unplanned = log.unplanned();
        held = log.held();
        assertEquals(DispatchWriteBack.Settlement.ACCEPTED, writeBack.settle(batch(13L), rows(13L)));
        assertEquals(unplanned, log.unplanned(), "the gate kept a captured switch instead of the live one");
        assertEquals(held, log.held());
    }

    @Test
    void theSinkFollowsTheCurrentCommitLog() throws Exception {
        configure(true, false);
        CommitLog original = module.commits();
        DispatchWriteBack writeBack = writeBack(productionSink(KernelWiring.install()),
            KernelSettings::dispatchTakeover);

        CommitLog first = new CommitLog(() -> 1, module.commitOrderSource());
        setCommits(first);
        long firstUnplanned = first.unplanned();
        long firstHeld = first.held();
        long originalUnplanned = original.unplanned();
        assertEquals(DispatchWriteBack.Settlement.ACCEPTED, writeBack.settle(batch(21L), rows(21L)));
        assertEquals(firstUnplanned + 1L, first.unplanned(), "the replaced log missed the report");
        assertEquals(firstHeld + 1L, first.held());
        assertEquals(originalUnplanned, original.unplanned(), "the old log was fed after the replacement");

        CommitLog second = new CommitLog(() -> 1, module.commitOrderSource());
        setCommits(second);
        long secondUnplanned = second.unplanned();
        assertEquals(DispatchWriteBack.Settlement.ACCEPTED, writeBack.settle(batch(22L), rows(22L)));
        assertEquals(secondUnplanned + 1L, second.unplanned());

        setCommits(original);
        long restoredUnplanned = original.unplanned();
        long restoredHeld = original.held();
        assertEquals(DispatchWriteBack.Settlement.ACCEPTED, writeBack.settle(batch(23L), rows(23L)));
        assertEquals(restoredUnplanned + 1L, original.unplanned(), "the restored log missed the report");
        assertEquals(restoredHeld + 1L, original.held());
        assertEquals(secondUnplanned + 1L, second.unplanned(), "the replaced log was fed after the restore");
    }

    @Test
    void computeOnlyAndRefusedSettlementsDoNotReachTheSink() throws Exception {
        configure(true, false);
        CountingSink counting = new CountingSink(productionSink(KernelWiring.install()));
        DispatchWriteBack computeOnly = writeBack(counting, () -> false);
        assertEquals(DispatchWriteBack.Settlement.COMPUTE_ONLY,
            computeOnly.settle(batch(31L), rows(31L)));
        assertEquals(0, counting.calls(), "the compute-only tier reported a commit");

        DispatchWriteBack takeover = writeBack(counting, () -> true, 1);
        assertEquals(DispatchWriteBack.Settlement.REFUSED_PAYLOAD,
            takeover.settle(batch(32L), List.of()));
        assertEquals(0, counting.calls(), "an empty batch was reported as landed");
        assertEquals(DispatchWriteBack.Settlement.ACCEPTED, takeover.settle(batch(33L), rows(33L)));
        assertEquals(1, counting.calls(), "the landed batch did not reach the sink exactly once");
        assertEquals(DispatchWriteBack.Settlement.REFUSED_QUEUE_CAP,
            takeover.settle(batch(34L), rows(34L)));
        assertEquals(1, counting.calls(), "a refused batch was reported as landed");
    }

    @Test
    void aLandedEntityBatchRefusalIsAttributedByTheRealReview() throws Exception {
        configure(true, true);
        assertTrue(KernelSettings.commitLog());
        assertTrue(KernelSettings.safetyNet());
        module.resetReadings();
        CommitLog oneSlot = new CommitLog(() -> 1, module.commitOrderSource());
        setCommits(oneSlot);
        EntityDomain installed = KernelWiring.install();
        DispatchWriteBack writeBack = writeBack(productionSink(installed),
            KernelSettings::dispatchTakeover);
        assertEquals(DispatchWriteBack.Settlement.ACCEPTED, writeBack.settle(batch(41L), rows(41L)));
        assertEquals(DispatchWriteBack.Settlement.ACCEPTED, writeBack.settle(batch(42L), rows(42L)));
        assertEquals(1L, oneSlot.rings().stream().mapToLong(CommitRing::refusedFull).sum(),
            "the one-slot ring did not refuse the second landed batch");

        module.serverTick(List.of(WORLD));

        List<String> export = KernelReadings.export(module);
        assertEquals("1", line(export, RING_READING), "the refused line is not counted at the ring site");
        assertEquals("entity", line(export, RING_DOMAIN_READING),
            "the ring row does not carry the domain the report was filed under");
        assertTrue(Long.parseLong(line(export, "safety.kind.version-conflict.domain_rows")) >= 1L,
            "no site row was attributed to a real domain");
        assertEquals("real-domain", line(export, "safety.kind.version-conflict.attribution"));
        assertEquals("3", line(export, "safety.kind.version-conflict.total"),
            "the review did not count the two unplanned reports and the ring refusal");
        assertEquals("1", line(export, "safety.kind.version-conflict.site_equals_total"));
        assertEquals("1", line(export, "safety.kind.version-conflict.world_equals_total"));
        assertTrue(module.safety().domains().contains("entity"),
            "the review did not declare the installed entity domain");
    }

    private static void configure(boolean commitLog, boolean safetyNet) throws IOException {
        PrtsConfigManager.Entry entry = PrtsConfigManager.entries().get(PrtsConfigManager.KERNEL);
        String content = PrtsConfigManager.defaults(entry);
        content = feature(content, "self-timers", false);
        content = feature(content, "share-table", false);
        content = feature(content, "wait-registry", false);
        content = feature(content, "write-path-guard", false);
        content = feature(content, "dispatch-takeover", true);
        content = feature(content, "commit-log", commitLog);
        content = feature(content, "safety-net", safetyNet);
        Files.createDirectories(CONFIG_DIR);
        Files.writeString(CONFIG_DIR.resolve("kernel.yml"), content, StandardCharsets.UTF_8);
        PrtsConfigManager.ensureAndLoad();
    }

    private static String feature(String content, String name, boolean value) {
        Pattern pattern = Pattern.compile(
            "(?m)^([ \\t]*" + Pattern.quote(name) + ":\\s*)(true|false)(\\s*)$");
        if (!pattern.matcher(content).find()) {
            throw new IllegalStateException("the kernel defaults carry no line for " + name);
        }
        return pattern.matcher(content).replaceFirst("$1" + value);
    }

    private void setCommits(CommitLog log) throws Exception {
        MODULE_COMMITS.set(module, log);
    }

    private static DispatchWriteBack writeBack(CommitLog.Sink sink, BooleanSupplier takeover) {
        return writeBack(sink, takeover, 64);
    }

    private static DispatchWriteBack writeBack(CommitLog.Sink sink, BooleanSupplier takeover,
                                               int capacity) {
        IntentQueue intents = new IntentQueue(() -> capacity, () -> 1);
        Map<String, Object> payloads = new LinkedHashMap<>();
        AtomicInteger handles = new AtomicInteger();
        DispatchWriteBack writeBack = new DispatchWriteBack(intents,
            (prefix, write) -> {
                String handle = prefix + ":" + handles.incrementAndGet();
                payloads.put(handle, write);
                return handle;
            }, payloads::remove, world -> 0L, new DispatchReadings(), takeover);
        writeBack.bindCommitSink(sink);
        return writeBack;
    }

    private static DispatchWriteBack productionWriteBack(EntityDomain domain) throws Exception {
        return (DispatchWriteBack) ENTITY_WRITE_BACK.get(domain);
    }

    private static CommitLog.Sink productionSink(EntityDomain domain) throws Exception {
        return (CommitLog.Sink) WRITE_BACK_SINK.get(productionWriteBack(domain));
    }

    private static WorkPlan.WorkBatch batch(long batchId) {
        EntityCandidateView.Builder builder = EntityCandidateView.builder(WORLD, 0L);
        builder.add(0L, 0, 0, 0.0, 64.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0L);
        WorkPlan.WorkTask task = new WorkPlan.WorkTask(batchId, WORLD, "r0.0", batchId,
            0, 1, 1L, 0L, 0, "r0.0");
        return new WorkPlan.WorkBatch(batchId, task, 0L, builder.build());
    }

    private static List<StateHasher.Slice> rows(long batchId) {
        return List.of(new StateHasher.Slice(WORLD, "r0.0", batchId, 0L, 1.0, 64.0, 1.0,
            0.0, 0.0, 0.0, 0.0, 0.0, 0L, 0L, 0L));
    }

    private static String line(List<String> export, String key) {
        List<String> hits = new ArrayList<>();
        for (String row : export) {
            if (row.startsWith(key + "=")) {
                hits.add(row);
            }
        }
        assertEquals(1, hits.size(), key + " is one line of the readout");
        return hits.get(0).substring(key.length() + 1);
    }

    private static Field field(Class<?> type, String name) {
        try {
            Field field = type.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static final class CountingSink implements CommitLog.Sink {

        private final CommitLog.Sink delegate;
        private final AtomicInteger calls = new AtomicInteger();

        CountingSink(CommitLog.Sink delegate) {
            this.delegate = delegate;
        }

        @Override
        public void reach(CommitLog.Batch batch) {
            calls.incrementAndGet();
            delegate.reach(batch);
        }

        int calls() {
            return calls.get();
        }
    }
}

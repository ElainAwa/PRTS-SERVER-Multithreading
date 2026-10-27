/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.observe;

import io.izzel.arclight.common.prts.config.PrtsConfigManager;
import io.izzel.arclight.common.prts.kernel.KernelModule;
import io.izzel.arclight.common.prts.kernel.commit.CommitController;
import io.izzel.arclight.common.prts.kernel.commit.CommitLog;
import io.izzel.arclight.common.prts.kernel.commit.CommitNamespaces;
import io.izzel.arclight.common.prts.kernel.commit.CommitReservation;
import io.izzel.arclight.common.prts.kernel.commit.CommitRing;
import io.izzel.arclight.common.prts.kernel.commit.EffectStore;
import io.izzel.arclight.common.prts.kernel.config.ExecutableProductFace;
import io.izzel.arclight.common.prts.kernel.safety.SafetyNet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What the readout attributes a safety row to once the review carries a domain: the last declared
 * domain filed at the site, kept across quiet reviews, and the family of the site key otherwise. */
class SafetyDomainAttributionReadingsTest {

    private static final String WORLD = "world-a";
    // A domain the module drives declares itself every tick; this name is no installed domain, so
    // the run has to declare it before a report may be read as evidence about it.
    private static final String DOMAIN = "chunk-pipeline";
    private static final String RING_SITE = "commit:ring|" + DOMAIN;
    // The site key keeps the hyphen the domain name carries; only the separators of the key are
    // replaced in the reading name.
    private static final String RING_READING =
        "safety.kind.version-conflict.site.commit_ring_chunk-pipeline";
    private static final String ORDER_READING = "safety.kind.version-conflict.site.commit_order";

    @BeforeEach
    void declareTheReviewableFace() throws IOException {
        System.setProperty("arclight.prts.kernel", "true");
        KernelModule module = KernelModule.instance();
        module.rollbackCommitChain();
        declareFace();
        // The first tick binds the owner thread of this run, so the chain can be adopted after it.
        module.serverTick(List.of(WORLD));
        adoptOneLineRing();
        // The net starts this run empty, and the tick that follows files every counter the run
        // already carried: the review the test asserts on reports the difference to that one only.
        module.safety().reset();
        module.serverTick(List.of(WORLD));
    }

    @AfterEach
    void restore() throws IOException {
        KernelModule module = KernelModule.instance();
        module.rollbackCommitChain();
        module.markSafePoint();
        module.safety().reset();
        System.clearProperty("arclight.prts.kernel");
        ExecutableProductFace.restore();
    }

    /** The declared face is executable and the net reviews every tick; the declaration goes into the
     * file an operator owns, so no built-in default of this build is touched. */
    private static void declareFace() throws IOException {
        PrtsConfigManager.Entry entry = PrtsConfigManager.entries().get(PrtsConfigManager.KERNEL);
        String content = PrtsConfigManager.defaults(entry)
            .replace("  product-commit: false", "  product-commit: true")
            .replace("  product-schema-version: -1", "  product-schema-version: 1")
            .replace("  effect-capacity-bytes: 0", "  effect-capacity-bytes: 4096")
            .replace("  effect-capacity-records: 0", "  effect-capacity-records: 16")
            .replace("  safety-net: false", "  safety-net: true");
        Path directory = PrtsConfigManager.directory();
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("kernel.yml"), content, StandardCharsets.UTF_8);
        PrtsConfigManager.ensureAndLoad();
    }

    /** Adopts a chain whose one ring holds a single line, so a second reach of the pair is refused. */
    private static void adoptOneLineRing() {
        KernelModule module = KernelModule.instance();
        EffectStore store = new EffectStore(4096, 16);
        CommitLog log = new CommitLog(() -> 1, module.commitOrderSource(), store);
        CommitReservation reservations = new CommitReservation(log, store,
            module.commitOrderSource(), "owner-a", new CommitNamespaces.ResetEpochs());
        assertTrue(module.installCommitChain(log, new CommitController(reservations, null)).bound(),
            "the whole chain is taken or none of it is");
    }

    /** Stores the one line the ring holds and gets the next reach refused as full. */
    private static void fillTheRing() {
        CommitLog log = KernelModule.instance().commits();
        assertTrue(log.reach(batch("a/0")).logged(), "the first line is stored");
        assertFalse(log.reach(batch("a/1")).logged(), "the ring holds one line and refuses the next");
        assertEquals(1L, refusedFull(), "the refusal is counted on the ring it happened on");
    }

    private static long refusedFull() {
        long refused = 0L;
        for (CommitRing ring : KernelModule.instance().commits().rings()) {
            refused += ring.refusedFull();
        }
        return refused;
    }

    private static CommitLog.Batch batch(String nodeKey) {
        return new CommitLog.Batch(WORLD, DOMAIN, nodeKey, CommitLog.Batch.Kind.APPLY, 1L, 1L, 1);
    }

    private static List<String> export() {
        return KernelReadings.export(KernelModule.instance());
    }

    private static String line(List<String> export, String key) {
        List<String> hits = new ArrayList<>();
        for (String row : export) {
            if (row.startsWith(key + "=")) {
                hits.add(row);
            }
        }
        assertEquals(1, hits.size(), key + " is one line of the readout");
        return hits.get(0);
    }

    private static List<String> keys(List<String> export, String prefix) {
        List<String> keys = new ArrayList<>();
        for (String row : export) {
            int cut = row.indexOf('=');
            if (cut > 0 && row.startsWith(prefix)) {
                keys.add(row.substring(0, cut));
            }
        }
        return keys;
    }

    @Test
    void aDeclaredRingDomainReadsAsARealDomain() {
        KernelModule module = KernelModule.instance();
        module.safety().declareDomains(List.of(DOMAIN));
        fillTheRing();
        module.serverTick(List.of(WORLD));

        List<String> export = export();
        assertEquals(RING_READING + "=1", line(export, RING_READING), "the refused line is counted");
        assertEquals(RING_READING + ".domain=" + DOMAIN, line(export, RING_READING + ".domain"),
            "the row carries the domain its report was filed under");
        assertEquals(ORDER_READING + "=2", line(export, ORDER_READING),
            "the two reaches that resolved no plan are the aggregate counter");
        assertEquals(ORDER_READING + ".domain=commit", line(export, ORDER_READING + ".domain"),
            "a counter that names no domain keeps the family of its site key");
        assertEquals("safety.kind.version-conflict.domain_rows=1",
            line(export, "safety.kind.version-conflict.domain_rows"),
            "one of the two rows is attributed to a real domain");
        assertEquals("safety.kind.version-conflict.attribution=real-domain",
            line(export, "safety.kind.version-conflict.attribution"));
        assertEquals("safety.kind.version-conflict.site_equals_total=1",
            line(export, "safety.kind.version-conflict.site_equals_total"));
        assertEquals("safety.kind.version-conflict.world_equals_total=1",
            line(export, "safety.kind.version-conflict.world_equals_total"));
        assertEquals(2, module.safetyReports().size(), "the review filed the two violations");
        boolean filed = false;
        for (SafetyNet.ViolationReport report : module.safetyReports()) {
            if (RING_SITE.equals(report.siteId())) {
                filed = true;
                assertEquals(DOMAIN, report.domainKey(), "the report names the declared domain");
            }
        }
        assertTrue(filed, "the refusal is reported at the ring's own site");
    }

    @Test
    void aRingDomainTheRunDidNotDeclareStaysNoDomainAttribution() {
        KernelModule module = KernelModule.instance();
        assertFalse(module.safety().domains().contains(DOMAIN),
            "this run declares no such domain");
        fillTheRing();
        module.serverTick(List.of(WORLD));

        List<String> export = export();
        assertEquals(RING_READING + "=1", line(export, RING_READING), "the violation is counted");
        assertEquals(RING_READING + ".domain=commit", line(export, RING_READING + ".domain"),
            "the row keeps the family of its site key");
        assertEquals("safety.kind.version-conflict.domain_rows=0",
            line(export, "safety.kind.version-conflict.domain_rows"));
        assertEquals("safety.kind.version-conflict.attribution=no-domain-attribution",
            line(export, "safety.kind.version-conflict.attribution"));
        boolean filed = false;
        for (SafetyNet.ViolationReport report : module.safetyReports()) {
            if (RING_SITE.equals(report.siteId())) {
                filed = true;
                assertEquals(SafetyNet.KERNEL_ORIGIN, report.domainKey(),
                    "a domain that was not declared is a kernel origin, which is no domain");
            }
        }
        assertTrue(filed, "the refusal is reported at the ring's own site");
    }

    @Test
    void declaringTheDomainFlipsTheReadingAndAddsNoKey() {
        KernelModule module = KernelModule.instance();
        fillTheRing();
        module.serverTick(List.of(WORLD));

        List<String> undeclared = export();
        assertEquals("safety.kind.version-conflict.attribution=no-domain-attribution",
            line(undeclared, "safety.kind.version-conflict.attribution"));

        module.safety().declareDomains(List.of(DOMAIN));
        assertFalse(module.commits().reach(batch("a/2")).logged(),
            "the ring is still full, so the next reach is refused again");
        module.serverTick(List.of(WORLD));

        List<String> declared = export();
        assertEquals("safety.kind.version-conflict.domain_rows=1",
            line(declared, "safety.kind.version-conflict.domain_rows"));
        assertEquals("safety.kind.version-conflict.attribution=real-domain",
            line(declared, "safety.kind.version-conflict.attribution"));
        assertEquals(RING_READING + ".domain=" + DOMAIN, line(declared, RING_READING + ".domain"));
        assertEquals(keys(undeclared, "safety."), keys(declared, "safety."),
            "the same net publishes the same safety keys whether or not a domain was declared");
    }

    @Test
    void theLastDeclaredDomainStaysOnTheCumulativeRow() {
        KernelModule module = KernelModule.instance();
        module.safety().declareDomains(List.of(DOMAIN));
        fillTheRing();
        module.serverTick(List.of(WORLD));
        assertEquals("safety.kind.version-conflict.attribution=real-domain",
            line(export(), "safety.kind.version-conflict.attribution"));

        module.serverTick(List.of(WORLD));

        assertTrue(module.safetyReports().isEmpty(),
            "a review that filed nothing leaves no report of the tick before behind");
        assertEquals(RING_READING + "=1", line(export(), RING_READING),
            "the row is still counted");
        assertEquals(RING_READING + ".domain=" + DOMAIN, line(export(), RING_READING + ".domain"),
            "the cumulative row keeps the declared domain that was filed on it");
        assertEquals("safety.kind.version-conflict.domain_rows=1",
            line(export(), "safety.kind.version-conflict.domain_rows"));
        assertEquals("safety.kind.version-conflict.attribution=real-domain",
            line(export(), "safety.kind.version-conflict.attribution"));
    }
}

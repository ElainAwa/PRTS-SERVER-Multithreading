/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel;

import io.izzel.arclight.common.prts.kernel.codes.RejectCode;
import io.izzel.arclight.common.prts.kernel.commit.FrozenDeclarations;
import io.izzel.arclight.common.prts.support.PrtsSeams;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The hard boundaries of the typed consumer batch: no seam, reject code, default value, feature
 * switch, entry column or reading key was added, and a build that was given no window writes no
 * typed line. The static side reads the sources of the module this test belongs to. */
class TypedConsumerBoundaryTest {

    private static final Path PRTS = findPrts();

    private static Path findPrts() {
        for (String candidate : List.of("src/main/java/io/izzel/arclight/common/prts",
            "arclight-common/src/main/java/io/izzel/arclight/common/prts")) {
            Path path = Paths.get(candidate);
            if (Files.isDirectory(path)) {
                return path;
            }
        }
        return Paths.get("src/main/java/io/izzel/arclight/common/prts");
    }

    private static String source(String relative) throws IOException {
        return Files.readString(PRTS.resolve(relative));
    }

    @Test
    void theSeamAndRejectCodeCountsAreUnchanged() {
        List<PrtsSeams.Seam> seams = PrtsSeams.kernelSeams();
        assertEquals(23, seams.size(), "the kernel keeps its 23 seams");
        long waitSites = seams.stream().filter(seam -> seam.seamId().startsWith("wait_site_"))
            .count();
        assertEquals(12L, waitSites, "the wait sites are the second family of seams");
        assertEquals(11L, seams.size() - waitSites, "the direct seams are unchanged");
        assertEquals(20, RejectCode.values().length, "no reject code was added");
    }

    @Test
    void theEntryColumnsAreUnchanged() {
        assertEquals(7, FrozenDeclarations.Entry.class.getRecordComponents().length,
            "the entry keeps its seven columns");
    }

    @Test
    void theDefaultsAndTheThreeSwitchDefaultsAreUnchanged() throws IOException {
        assumeTrue(Files.isDirectory(PRTS), "the source tree is not beside the test run");
        String config = source("config/PrtsConfigManager.java");
        Pattern row = Pattern.compile("features\\.put\\(\"([^\"]+)\", (true|false)\\)");
        Matcher matcher = row.matcher(config);
        int defaults = 0;
        while (matcher.find()) {
            defaults++;
        }
        assertEquals(40, defaults, "the declared feature defaults are unchanged");
        for (String name : List.of("tick-plan", "pipeline-jobs", "product-commit")) {
            Matcher one = Pattern.compile("features\\.put\\(\"" + Pattern.quote(name)
                + "\", (true|false)\\)").matcher(config);
            assertTrue(one.find(), () -> "the " + name + " switch is declared");
            assertEquals("false", one.group(1), () -> "the " + name + " switch keeps its default");
        }
    }

    @Test
    void noReadingKeyIsAddedForTheTypedConsumer() throws IOException {
        assumeTrue(Files.isDirectory(PRTS), "the source tree is not beside the test run");
        String readings = source("kernel/observe/KernelReadings.java");
        for (String token : List.of("typed.", "effect.ref", "row.identity", "settle.seat")) {
            assertFalse(readings.contains(token), () -> "a reading key was added: " + token);
        }
    }

    @Test
    void aBuildWithoutAWindowWritesNoTypedLine() {
        KernelModule module = KernelModule.instance();
        module.removeWaitSiteTap();
        module.removeWritePathTap();
        module.rollbackCommitChain();
        module.markSafePoint();
        module.resetReadings();
        module.markSafePoint();
        try {
            assertNotNull(module.claims());
            assertEquals(0, module.claims().active(), "no plan declared a claim");
            assertEquals(0L, module.claims().landedWriteCount());
            assertEquals(0L, module.claims().reportedBatchCount());
            assertEquals(0, module.claims().openWindowFrames());
            assertEquals(0, module.rangeSeals().size());
            assertEquals(0, module.rangeSeals().pinnedCount());
            assertEquals(0L, module.rangeSeals().published());
            assertEquals(0L, module.commits().typedStored(), "no typed line was written");
            assertEquals(0L, module.commits().typedRefused());
            assertEquals(0, module.claims().reportLanded(report -> {
                throw new AssertionError("a build without a window reported a batch");
            }), "no span reports a batch it never opened");
            KernelModule.TypedConsumerReadiness readiness = module.typedConsumerReadiness();
            assertTrue(readiness.attributionArmed());
            assertTrue(readiness.obligationsRegistered());
            assertFalse(readiness.ready(), "the default build holds no adopted chain");
        } finally {
            module.resetReadings();
            module.markSafePoint();
        }
    }
}

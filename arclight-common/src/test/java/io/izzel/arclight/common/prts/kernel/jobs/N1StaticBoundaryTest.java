/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.jobs;

import io.izzel.arclight.common.prts.kernel.codes.RejectCode;
import io.izzel.arclight.common.prts.support.PrtsSeams;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The static boundary of the N1 real-status change: the wiring publishes only inside the freeze
 * settlement, no seam / reject code / default / reading key was added, and the retention read path
 * is separate from the run-start read path. */
class N1StaticBoundaryTest {

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
        assertEquals(20, RejectCode.values().length, "no reject code was added");
    }

    @Test
    void theWiringPublishesOnlyInsideTheFreezeSettlement() throws IOException {
        assumeTrue(Files.isDirectory(PRTS), "the source tree is not beside the test run");
        String module = source("kernel/KernelModule.java");
        int retained = module.indexOf("retainedStatuses()");
        int publish = module.indexOf("publishDeclaredDeclared");
        int seal = module.indexOf("sealDeclarations(result.plan())");
        assertTrue(retained >= 0, "the wiring reads the retained statuses");
        assertTrue(publish > retained, "the publish follows the retained read");
        assertTrue(seal > publish, "the publish happens before the new plan's declarations replace");
        assertEquals(1, count(module, "publishDeclaredDeclared"),
            "the wiring calls the declared publish exactly once");
        assertFalse(module.contains("publishDeclaredDeclared(declarations, List.of()"),
            "the wiring never publishes from an empty retained list literal");
    }

    @Test
    void theRetentionReadPathIsSeparateFromTheRunStartReadPath() throws IOException {
        assumeTrue(Files.isDirectory(PRTS), "the source tree is not beside the test run");
        String demand = source("kernel/jobs/DemandShadowCandidates.java");
        assertTrue(demand.contains("if (!(state instanceof PipelineBatchLedger.Running running))"),
            "statusFor still answers only while the task runs");
        assertTrue(demand.contains("public synchronized List<TaskStatus> retainedStatuses()"),
            "the retained read is a separate method");
        assertTrue(demand.contains("releaseBatch") && demand.contains("releaseBefore")
            && demand.contains("resetStatuses"), "the retention has explicit release points");
        assertFalse(demand.contains("targetStatus = \"full\""),
            "no constant status fallback is written");
        assertFalse(demand.contains("N2"), "no N2 fallback is referenced");
    }

    private static int count(String text, String token) {
        int found = 0;
        int at = text.indexOf(token);
        while (at >= 0) {
            found++;
            at = text.indexOf(token, at + token.length());
        }
        return found;
    }
}

/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.observe;

import io.izzel.arclight.common.prts.kernel.KernelModule;
import io.izzel.arclight.common.prts.kernel.codes.RejectCode;
import io.izzel.arclight.common.prts.kernel.jobs.JobDeclaration;
import io.izzel.arclight.common.prts.kernel.jobs.JobGraph;
import io.izzel.arclight.common.prts.kernel.jobs.JobGraphBuilder;
import io.izzel.arclight.common.prts.kernel.jobs.RecomputeUnbounded;
import io.izzel.arclight.common.prts.kernel.plan.TickPlanPlanner;
import io.izzel.arclight.common.prts.kernel.plan.TickPlanStore;
import io.izzel.arclight.common.prts.kernel.shares.ShareClass;
import io.izzel.arclight.common.prts.kernel.shares.SharePlanner;
import io.izzel.arclight.common.prts.kernel.shares.ShareTable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The formal graph-recompute counter: one key, published only after a graph build covers the
 * current run generation, zero while only budgeted bootstrap/fallback builds ran, positive after an
 * unbudgeted build actually started. The same test writes the readout back out as the raw export the
 * offline parser consumes, so the product row and the parser chain are exercised over one source. */
class P3Acc11ReadoutTest {

    private static final String WORLD = "world-a";
    private static final String KEY = "recompute.unbounded";

    private final KernelModule module = KernelModule.instance();

    private static JobDeclaration declaration(String key, int priority, List<String> predecessors) {
        JobDeclaration.DomainRef ref = new JobDeclaration.DomainRef(WORLD, "entity", 0);
        return new JobDeclaration(key, key.hashCode(), WORLD, "entity", 0, predecessors, priority,
            key, "region", List.of(ref), List.of(ref), ShareClass.ENTITY,
            JobDeclaration.SiteClass.PARALLEL, 0, 1);
    }

    private static List<JobDeclaration> chain() {
        List<JobDeclaration> declarations = new ArrayList<>();
        declarations.add(declaration("a/0", 0, List.of()));
        declarations.add(declaration("a/1", 1, List.of("a/0")));
        declarations.add(declaration("a/2", 2, List.of("a/1")));
        return declarations;
    }

    private static List<JobDeclaration> dense(int count) {
        List<JobDeclaration> declarations = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            List<String> predecessors = new ArrayList<>();
            for (int earlier = 0; earlier < index; earlier++) {
                predecessors.add("a/" + earlier);
            }
            declarations.add(declaration("a/" + index, 0, predecessors));
        }
        return declarations;
    }

    private static ShareTable table() {
        return new SharePlanner().plan(List.of(WORLD), 10L, Map.of());
    }

    private static TickPlanStore.Control control() {
        return TickPlanStore.Control.of(9L, 1L, 1L, 1.0, 0L, 0L, 4.0, "normal");
    }

    private static TickPlanPlanner.Input input(long sequence, List<JobDeclaration> declarations,
                                               JobGraph previous) {
        return new TickPlanPlanner.Input(10L, sequence, 1L, List.of(WORLD), List.of("entity"),
            control(), declarations, table(), 64, TickPlanStore.Feedback.none(), previous);
    }

    private static List<String> export() {
        return KernelReadings.export(KernelModule.instance());
    }

    private static List<String> rows(String key) {
        List<String> found = new ArrayList<>();
        for (String line : export()) {
            if (line.startsWith(key + "=")) {
                found.add(line);
            }
        }
        return found;
    }

    @BeforeEach
    void startAGeneration() {
        module.resetReadings();
        RecomputeUnbounded.reset();
    }

    @Test
    void theKeyIsAbsentUntilAGraphBuildCoversTheSource() {
        assertTrue(rows(KEY).isEmpty(),
            "a run that planned nothing publishes no recompute row");
    }

    @Test
    void aBudgetedBootstrapPublishesExactlyOneZero() {
        TickPlanPlanner.Result planned = TickPlanPlanner.plan(input(2L, chain(), null));

        assertTrue(planned.ok(), () -> "refused: " + planned.code());
        assertEquals(JobGraphBuilder.RecomputeMode.BOOTSTRAP, planned.recompute().mode());
        assertEquals(1, rows(KEY).size());
        assertEquals(KEY + "=0", rows(KEY).get(0));
    }

    @Test
    void reusedAndFallbackBuildsStayZero() {
        TickPlanPlanner.Result first = TickPlanPlanner.plan(input(2L, chain(), null));
        TickPlanPlanner.Result reused = TickPlanPlanner.plan(
            input(3L, chain(), first.plan().graph()));
        List<JobDeclaration> changed = new ArrayList<>(chain());
        changed.set(1, declaration("a/1", 5, List.of("a/0")));
        TickPlanPlanner.Result fallback = TickPlanPlanner.plan(
            input(4L, changed, reused.plan().graph()));

        assertEquals(JobGraphBuilder.RecomputeMode.REUSED, reused.recompute().mode());
        assertEquals(JobGraphBuilder.RecomputeMode.FULL_FALLBACK, fallback.recompute().mode());
        assertEquals(1, rows(KEY).size());
        assertEquals(KEY + "=0", rows(KEY).get(0),
            "reuse and a budgeted fallback are not unbudgeted rebuilds");
    }

    @Test
    void aRefusalThatNeverStartsABuildPublishesNoKey() {
        TickPlanPlanner.Input tooMany = new TickPlanPlanner.Input(10L, 2L, 1L, List.of(WORLD),
            List.of("entity"), control(), chain(), table(), 2, TickPlanStore.Feedback.none());

        TickPlanPlanner.Result capped = TickPlanPlanner.plan(tooMany);

        assertFalse(capped.ok());
        assertEquals(RejectCode.QUEUE_CAP_EXCEEDED, capped.code());
        assertTrue(rows(KEY).isEmpty(),
            "no full build was reached, so the source cannot publish a zero");
    }

    @Test
    void aBudgetedFullBuildThatRunsOutOfBudgetStillPublishesZero() {
        TickPlanPlanner.Result refused = TickPlanPlanner.plan(input(2L, dense(64), null));

        assertFalse(refused.ok());
        assertEquals(RejectCode.SCALE_BUDGET_EXCEEDED, refused.code());
        assertEquals(1, rows(KEY).size());
        assertEquals(KEY + "=0", rows(KEY).get(0),
            "a budgeted rebuild that failed is covered but not an unbudgeted rebuild");
    }

    @Test
    void anUnbudgetedBuildThatAlreadyStartedTurnsTheKeyPositive() {
        JobGraphBuilder.Freeze refused = JobGraphBuilder.freeze(
            List.of(declaration("a/0", 0, List.of("a/1")), declaration("a/1", 0, List.of("a/0"))),
            10L, 2L, 1L, 64);

        assertFalse(refused.ok());
        assertEquals(1, rows(KEY).size());
        assertEquals(KEY + "=1", rows(KEY).get(0),
            "the unbudgeted branch started before the refusal, so the key is red");
    }

    @Test
    void repeatedUnbudgetedBuildsAccumulateInOneRow() {
        JobGraphBuilder.freeze(chain(), 10L, 2L, 1L, 64);
        JobGraphBuilder.freeze(chain(), 11L, 3L, 1L, 64);

        assertEquals(1, rows(KEY).size());
        assertEquals(KEY + "=2", rows(KEY).get(0));
    }

    @Test
    void aResetDropsTheOldGenerationAndABuildPublishesAFreshZero() {
        TickPlanPlanner.plan(input(2L, chain(), null));
        assertEquals(KEY + "=0", rows(KEY).get(0));

        module.resetReadings();

        assertTrue(rows(KEY).isEmpty(), "the new generation has planned nothing yet");
        TickPlanPlanner.Result planned = TickPlanPlanner.plan(input(2L, chain(), null));
        assertTrue(planned.ok());
        assertEquals(KEY + "=0", rows(KEY).get(0));
    }

    @Test
    void theDiagnosticFreezePublishesNoKey() {
        JobGraphBuilder.Freeze freeze = JobGraphBuilder.freezeDiagnostic(chain(), 10L, 1L, 1L, 64);

        assertTrue(freeze.ok());
        assertTrue(rows(KEY).isEmpty(),
            "a self report is not a planning execution and covers nothing");
    }

    @Test
    void theRealPlanningPathStaysZeroUntilAMutationBypassesIt() {
        TickPlanPlanner.plan(input(2L, chain(), null));
        assertEquals(KEY + "=0", rows(KEY).get(0));

        JobGraphBuilder.freeze(chain(), 10L, 9L, 1L, 64);

        assertEquals(KEY + "=1", rows(KEY).get(0),
            "only a bypass of the bounded planning entry makes the key nonzero");
    }

    @Test
    void theReadoutRawExportIsWrittenForTheOfflineParserChain() throws IOException {
        Path dir = Path.of("build", "p3-acc11");
        Files.createDirectories(dir);

        TickPlanPlanner.plan(input(2L, chain(), null));
        List<String> zero = rawExport();
        assertTrue(zero.stream().anyMatch(line -> line.contains(KEY + "=0")));
        Files.write(dir.resolve("raw-zero.txt"), zero);
        String runId = runIdOf(zero);
        Files.writeString(dir.resolve("run-id.txt"), runId + "\n");

        module.resetReadings();
        RecomputeUnbounded.reset();
        List<String> notRun = rawExport();
        assertTrue(notRun.stream().noneMatch(line -> line.contains(KEY + "=")));
        Files.write(dir.resolve("raw-not-run.txt"), notRun);

        module.resetReadings();
        RecomputeUnbounded.reset();
        JobGraphBuilder.freeze(chain(), 10L, 9L, 1L, 64);
        List<String> positive = rawExport();
        assertTrue(positive.stream().anyMatch(line -> line.contains(KEY + "=1")));
        Files.write(dir.resolve("raw-positive.txt"), positive);
    }

    private static List<String> rawExport() {
        List<String> raw = new ArrayList<>();
        for (String line : KernelReadings.forensicReadings(KernelModule.instance())) {
            raw.add("[PRTS] kernel " + line);
        }
        return raw;
    }

    private static String runIdOf(List<String> raw) {
        String marker = "product.commit.run_identity=";
        for (String line : raw) {
            int at = line.indexOf(marker);
            if (at < 0) {
                continue;
            }
            String value = line.substring(at + marker.length());
            int runId = value.indexOf("runId=");
            if (runId >= 0) {
                String rest = value.substring(runId + "runId=".length());
                int space = rest.indexOf(' ');
                return space < 0 ? rest : rest.substring(0, space);
            }
        }
        return "unnamed";
    }
}

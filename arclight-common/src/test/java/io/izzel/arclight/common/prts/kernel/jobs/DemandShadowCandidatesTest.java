/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.jobs;

import io.izzel.arclight.common.prts.config.PrtsConfigManager;
import io.izzel.arclight.common.prts.kernel.KernelModule;
import io.izzel.arclight.common.prts.kernel.commit.CommitLog;
import io.izzel.arclight.common.prts.kernel.commit.FrozenDeclarations;
import io.izzel.arclight.common.prts.kernel.config.KernelSettings;
import io.izzel.arclight.common.prts.kernel.observe.KernelReadings;
import io.izzel.arclight.common.prts.kernel.plan.TickPlan;
import io.izzel.arclight.common.prts.kernel.shares.ShareClass;
import io.izzel.arclight.common.prts.support.PrtsChunkDemand;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * What one freeze reads out of the chunk demands of its own window: the columns of a candidate are
 * the parameters of the call that asked for the chunk and the freeze that read it, a chunk is judged
 * as the object it is, an operation seen to be done before the freeze is refused, and a window that
 * is complete is still not executable: the freeze that read it declares what the capture carries and
 * seals it, and no run of it is witnessed.
 *
 * <p>The tap is installed here as a fixture: in a build the demand observer owns the seam, and no
 * production entry installs this reader, so nothing in this class is a reading of a running server.
 */
class DemandShadowCandidatesTest {

    private static final String WORLD = "minecraft:overworld";
    private static final String OTHER_WORLD = "minecraft:the_nether";
    private static final String STATUS = "full";
    private static final long EPOCH = 11L;
    private static final FrozenDeclarations.FreezeKey KEY =
        new FrozenDeclarations.FreezeKey("lifecycle-a", 4L);

    private final DemandShadowCandidates candidates = new DemandShadowCandidates();

    @BeforeEach
    void installTheReader() {
        PrtsChunkDemand.install(candidates);
    }

    @AfterEach
    void removeTheReader() {
        PrtsChunkDemand.install(null);
    }

    private static DemandShadowCandidates.LiveWorldEpoch live(long epoch) {
        return worldId -> epoch;
    }

    private static DemandShadowCandidates.Freeze freeze(long planSequence) {
        return new DemandShadowCandidates.Freeze(planSequence, 2L, KEY);
    }

    private List<DemandShadowCandidates.Shadow> read(long planSequence) {
        return candidates.readFor(freeze(planSequence), live(EPOCH));
    }

    @Test
    void theParametersOfOneCallBecomeTheColumnsOfOneCandidateOfThatFreeze() {
        PrtsChunkDemand.demandDescriptor(WORLD, 3, -7, STATUS, true);

        List<DemandShadowCandidates.Shadow> shadows = read(5L);

        assertEquals(1, shadows.size());
        DemandShadowCandidates.Shadow shadow = shadows.get(0);
        assertEquals(WORLD, shadow.worldId(), "the world is the one the call named");
        assertEquals(DemandShadowCandidates.DOMAIN, shadow.domainId());
        assertEquals(ChunkPos.asLong(3, -7), shadow.chunk().packedPos(),
            "the chunk is packed from the coordinates of this very call");
        assertEquals(STATUS, shadow.targetStatus());
        assertTrue(shadow.requireChunk(), "a parameter of the call is carried as it was given");
        assertEquals(EPOCH, shadow.worldEpoch(), "the generation was read once at the freeze");
        assertEquals(5L, shadow.planSequence());
        assertEquals(2L, shadow.planGeneration());
        assertEquals(KEY, shadow.freeze(), "the candidate names the freeze that read it");
        assertFalse(shadow.late());
        assertTrue(shadow.declared(), "every column a declaration carries is there");
        assertEquals(DemandShadowCandidates.NOT_EXECUTABLE, shadow.state(),
            "complete and still not to be sealed: the freeze has no execution witness");
        assertEquals("", shadow.refusal());
        assertEquals(1L, candidates.lastRead());
        assertEquals(1L, candidates.unexecutable());
        assertEquals(0L, candidates.notWired());
    }

    @Test
    void theKeyIsAPureFunctionOfTheCapture() {
        PrtsChunkDemand.demandDescriptor(WORLD, 3, -7, STATUS, true);
        PrtsChunkDemand.demandDescriptor(WORLD, 3, -7, STATUS, true);
        PrtsChunkDemand.demandDescriptor(WORLD, -1, 0, STATUS, true);

        List<DemandShadowCandidates.Shadow> shadows = read(5L);

        assertEquals(3, shadows.size());
        assertEquals(shadows.get(0).nodeKey(), shadows.get(1).nodeKey(),
            "the same parameters give the same key, whatever the window held before them");
        assertNotEquals(shadows.get(0).nodeKey(), shadows.get(2).nodeKey());
        assertEquals(DemandShadowCandidates.DOMAIN + "/" + WORLD + "/" + ChunkPos.asLong(3, -7) + "/"
            + STATUS, shadows.get(0).nodeKey());
    }

    @Test
    void theParametersOfOneCallAreNotSharedWithTheNext() {
        PrtsChunkDemand.demandDescriptor(WORLD, 1, 2, "features", false);
        PrtsChunkDemand.demandDescriptor(OTHER_WORLD, 0, 0, STATUS, true);

        List<DemandShadowCandidates.Shadow> shadows = read(5L);

        assertEquals(2, shadows.size());
        assertEquals(WORLD, shadows.get(0).worldId());
        assertEquals(ChunkPos.asLong(1, 2), shadows.get(0).chunk().packedPos());
        assertEquals("features", shadows.get(0).targetStatus());
        assertFalse(shadows.get(0).requireChunk());
        assertEquals(OTHER_WORLD, shadows.get(1).worldId());
        assertEquals(0L, shadows.get(1).chunk().packedPos());
    }

    @Test
    void theOriginIsACandidateAndItsPositionIsNotAStatementAboutPresence() {
        PrtsChunkDemand.demandDescriptor(WORLD, 0, 0, STATUS, true);

        List<DemandShadowCandidates.Shadow> shadows = read(5L);

        DemandShadowCandidates.Shadow origin = shadows.get(0);
        assertNotNull(origin.chunk(), "the origin is a chunk the caller named");
        assertEquals(CommitLog.NO_CHUNK_POSITION, origin.chunk().packedPos(),
            "and it packs to the value a missing chunk would pack to");
        assertTrue(origin.declared(), "presence is the object, so the origin is declared");
        assertEquals(DemandShadowCandidates.NOT_EXECUTABLE, origin.state());
    }

    @Test
    void aNegativeChunkPositionIsStillACandidate() {
        assertEquals(-4294967296L, ChunkPos.asLong(0, -1),
            "a packed position is signed: a negative z packs to a negative number");
        assertEquals(4294967295L, ChunkPos.asLong(-1, 0),
            "and a negative x packs to a positive one, so the sign says nothing about presence");
        PrtsChunkDemand.demandDescriptor(WORLD, 0, -1, STATUS, true);
        PrtsChunkDemand.demandDescriptor(WORLD, -1, 0, STATUS, true);

        List<DemandShadowCandidates.Shadow> shadows = read(5L);

        assertEquals(-4294967296L, shadows.get(0).chunk().packedPos());
        assertEquals(4294967295L, shadows.get(1).chunk().packedPos());
        assertTrue(shadows.get(0).declared(), "a negative position is a legal chunk, not a missing one");
        assertTrue(shadows.get(1).declared());
        assertEquals(DemandShadowCandidates.NOT_EXECUTABLE, shadows.get(0).state());
        assertEquals(DemandShadowCandidates.NOT_EXECUTABLE, shadows.get(1).state());
    }

    @Test
    void aCallThatNamedNoCoordinatesIsNoCandidate() {
        PrtsChunkDemand.futureOpened(WORLD, STATUS);

        DemandShadowCandidates.Shadow shadow = read(5L).get(0);

        assertNull(shadow.chunk(), "an ask that named no coordinates carries no chunk");
        assertFalse(shadow.declared());
        assertEquals(DemandShadowCandidates.NOT_WIRED, shadow.state());
        assertEquals(1L, candidates.unshaped());
    }

    @Test
    void aCallThatNamedNoTargetStateIsNoCandidate() {
        PrtsChunkDemand.demandDescriptor(WORLD, 3, -7, "", true);

        DemandShadowCandidates.Shadow shadow = read(5L).get(0);

        assertNotNull(shadow.chunk(), "the chunk is there and the state is not");
        assertEquals("", shadow.targetStatus());
        assertFalse(shadow.declared());
        assertEquals(DemandShadowCandidates.NOT_WIRED, shadow.state());
        assertEquals(1L, candidates.unshaped());
    }

    @Test
    void aCallThatNamedNoWorldIsNoCandidate() {
        PrtsChunkDemand.demandDescriptor(null, 3, -7, STATUS, true);

        DemandShadowCandidates.Shadow shadow = read(5L).get(0);

        assertEquals("", shadow.worldId());
        assertFalse(shadow.declared());
        assertEquals(DemandShadowCandidates.NOT_WIRED, shadow.state());
        assertEquals(1L, candidates.unshaped());
    }

    @Test
    void aWorldWithoutALiveGenerationIsNoCandidateAndTheTwoWaysAreNamedApart() {
        PrtsChunkDemand.demandDescriptor(WORLD, 3, -7, STATUS, true);
        PrtsChunkDemand.demandDescriptor(OTHER_WORLD, 3, -7, STATUS, true);

        List<DemandShadowCandidates.Shadow> shadows = candidates.readFor(freeze(5L), worldId ->
            WORLD.equals(worldId) ? 0L : -1L);

        DemandShadowCandidates.Shadow untracked = shadows.get(0);
        DemandShadowCandidates.Shadow lost = shadows.get(1);
        assertEquals(CommitLog.NO_WORLD_EPOCH, untracked.worldEpoch());
        assertEquals("world-untracked", untracked.refusal());
        assertEquals(-1L, lost.worldEpoch());
        assertEquals("world-not-live", lost.refusal());
        assertNotEquals(untracked.refusal(), lost.refusal(), "the two refusals are not one name");
        assertFalse(untracked.declared());
        assertFalse(lost.declared());
        assertEquals(DemandShadowCandidates.NOT_WIRED, untracked.state());
        assertEquals(DemandShadowCandidates.NOT_WIRED, lost.state());
        assertEquals(2L, candidates.unlive());
    }

    @Test
    void aGenerationThatMovedAfterTheFreezeIsNotCurrentAndTheColumnIsNotRewritten() {
        PrtsChunkDemand.demandDescriptor(WORLD, 3, -7, STATUS, true);
        DemandShadowCandidates.Shadow shadow = read(5L).get(0);

        assertTrue(shadow.currentFor(EPOCH), "the freeze read the generation of its own world");
        assertFalse(shadow.currentFor(EPOCH + 1L), "a world rebuilt since the freeze is not current");
        assertEquals(EPOCH, shadow.worldEpoch(),
            "the frozen column still carries the freeze's read, not the later one");
        assertEquals(DemandShadowCandidates.NOT_EXECUTABLE, shadow.state());
    }

    @Test
    void aCompletionSeenBeforeTheFreezeRefusesTheCapture() {
        PrtsChunkDemand.demandDescriptor(WORLD, 3, -7, STATUS, true);
        PrtsChunkDemand.futureCompleted(WORLD, STATUS, 1L, true);

        DemandShadowCandidates.Shadow shadow = read(5L).get(0);

        assertTrue(shadow.late(), "an operation that already ran was not outstanding at the freeze");
        assertEquals(DemandShadowCandidates.LATE_BEFORE_FREEZE, shadow.refusal());
        assertEquals(DemandShadowCandidates.NOT_WIRED, shadow.state());
        assertEquals(1L, candidates.lateRefusals());
        assertEquals(0L, candidates.unexecutable());
    }

    @Test
    void aCompletionOfAnotherWorldOrStateRefusesNothing() {
        PrtsChunkDemand.demandDescriptor(WORLD, 3, -7, STATUS, true);
        PrtsChunkDemand.futureCompleted(WORLD, "features", 1L, true);
        PrtsChunkDemand.futureCompleted(OTHER_WORLD, STATUS, 1L, true);

        DemandShadowCandidates.Shadow shadow = read(5L).get(0);

        assertFalse(shadow.late(), "a completion answers the capture that waited on it and no other");
        assertEquals(DemandShadowCandidates.NOT_EXECUTABLE, shadow.state());
    }

    @Test
    void aCompletionAfterTheFreezeBelongsToTheNextWindow() {
        PrtsChunkDemand.demandDescriptor(WORLD, 3, -7, STATUS, true);
        assertFalse(read(5L).get(0).late());
        PrtsChunkDemand.futureCompleted(WORLD, STATUS, 1L, true);
        PrtsChunkDemand.demandDescriptor(WORLD, 4, -7, STATUS, true);

        assertFalse(read(6L).get(0).late(),
            "a capture taken after a completion did not wait on it and is not read as done");
    }

    @Test
    void aCaptureNotSeenDoneIsStillNotExecutable() {
        assertTrue(DemandShadowCandidates.lateBeforeFreeze(true), "done before the freeze refuses");
        assertFalse(DemandShadowCandidates.lateBeforeFreeze(false),
            "and the other answer settles nothing: not done is not not started");
        PrtsChunkDemand.demandDescriptor(WORLD, 3, -7, STATUS, true);
        DemandShadowCandidates.Shadow shadow = read(5L).get(0);

        assertTrue(List.of(DemandShadowCandidates.NOT_WIRED, DemandShadowCandidates.NOT_EXECUTABLE)
            .contains(shadow.state()), "the capture reads: " + shadow.state());
        assertNotEquals(FrozenDeclarations.SEALED, shadow.state(),
            "and never a state a table is sealed with");
        for (RecordComponent component : DemandShadowCandidates.Shadow.class.getRecordComponents()) {
            assertFalse(component.getName().toLowerCase().contains("instance"),
                "a capture carries no instance number at all: " + component.getName());
        }
        assertThrows(NoSuchMethodException.class,
            () -> DemandShadowCandidates.class.getDeclaredMethod("mint", List.class),
            "there is no entry that mints a number here");
    }

    @Test
    void anAskThatIsNotAFutureIsNoCapture() {
        PrtsChunkDemand.demandStarted(WORLD, 3, -7, STATUS, true);
        PrtsChunkDemand.demandFinished(WORLD, STATUS, true, true);
        PrtsChunkDemand.futureTaken(WORLD, STATUS);

        assertTrue(read(5L).isEmpty(), "only the head of a future-returning ask is a capture");
        assertEquals(0L, candidates.captured());
    }

    @Test
    void aWindowIsReadOnceAndBelongsToTheFreezeThatReadIt() {
        PrtsChunkDemand.demandDescriptor(WORLD, 3, -7, STATUS, true);
        assertEquals(1, read(5L).size());
        assertTrue(read(6L).isEmpty(), "a window a freeze read is not handed to the next one");
        assertEquals(0, candidates.held());
        assertEquals(2L, candidates.windows());
    }

    @Test
    void aFullRingCostsTheOldestCaptureAndNeverTheCaller() {
        for (int index = 0; index < DemandShadowCandidates.CAP + 3; index++) {
            candidates.demandDescriptor(WORLD, index, 0, STATUS, true);
        }
        PrtsChunkDemand.demandDescriptor(OTHER_WORLD, 0, 0, STATUS, true);

        assertEquals(DemandShadowCandidates.CAP, candidates.held(), "the ring never grows past its cap");
        assertEquals(DemandShadowCandidates.CAP + 4L, candidates.captured());
        assertEquals(4L, candidates.dropped(), "what a full ring cost is counted, not hidden");
        assertTrue(PrtsChunkDemand.installed(), "the reader is still the installed one");
        List<DemandShadowCandidates.Shadow> shadows = read(5L);
        assertEquals(DemandShadowCandidates.CAP, shadows.size());
        assertEquals(ChunkPos.asLong(4, 0), shadows.get(0).chunk().packedPos(),
            "the captures a full ring cost are the oldest ones");
        assertEquals(ChunkPos.asLong(0, 0), shadows.get(DemandShadowCandidates.CAP - 1)
            .chunk().packedPos(), "and the last capture of the window is kept");
    }

    private static List<Class<?>> owners() throws ClassNotFoundException {
        return List.of(DemandShadowCandidates.class, DemandShadowCandidates.DemandDescriptor.class,
            DemandShadowCandidates.Shadow.class, DemandShadowCandidates.Freeze.class,
            Class.forName(DemandShadowCandidates.class.getName() + "$Slot"));
    }

    @Test
    void everyColumnOfACandidateIsAPlainValue() throws Exception {
        TreeSet<String> types = new TreeSet<>();
        for (Class<?> owner : owners()) {
            for (Field field : owner.getDeclaredFields()) {
                types.add(field.getType().getName());
            }
        }

        assertEquals(new TreeSet<>(List.of("[L" + DemandShadowCandidates.class.getName() + "$Slot;",
            "boolean", "int", "long", "java.lang.Object", "java.util.List", "java.util.Map",
            "java.lang.String",
            FrozenDeclarations.DeclaredChunk.class.getName(),
            FrozenDeclarations.FreezeKey.class.getName(),
            DemandShadowCandidates.DemandDescriptor.class.getName(),
            "[L" + DemandShadowCandidates.class.getName() + "$Slot;")), types,
            "every column of a candidate, an opaque task identity and the register map, and "
                + "nothing else: " + types);
        for (String name : types) {
            assertFalse(name.startsWith("net.minecraft."), name + " is a platform type");
            assertFalse(name.startsWith("org.bukkit."), name + " is a platform type");
            assertFalse(name.startsWith("java.util.concurrent."), name + " is a task type");
        }
    }

    @Test
    void noColumnIsAssignableToAWorldAFutureOrAChunk() throws Exception {
        List<Class<?>> forbidden = List.of(Level.class, ChunkAccess.class, Future.class,
            CompletableFuture.class, Thread.class, Class.class);
        for (Class<?> owner : owners()) {
            for (Field field : owner.getDeclaredFields()) {
                Class<?> type = field.getType().isArray() ? field.getType().getComponentType()
                    : field.getType();
                if (type.isPrimitive()) {
                    continue;
                }
                for (Class<?> held : forbidden) {
                    assertFalse(held.isAssignableFrom(type), owner.getSimpleName() + "."
                        + field.getName() + " is a " + held.getName());
                }
            }
        }
    }


    // ---- the freeze of a driven tick: the wiring this batch adds ----

    private static final Path CONFIG_DIR = PrtsConfigManager.directory();
    private static final String TICK_WORLD = "shadow-freeze";

    private static String reading(String key) {
        for (String row : KernelReadings.export(KernelModule.instance())) {
            if (row.startsWith(key + "=")) {
                return row.substring(key.length() + 1);
            }
        }
        return "<missing>";
    }

    /** A tick plan is off in the declared defaults, so the fixture switches the one feature on for
     * the life of the case and puts the directory back the way it found it. */
    private static void switchTheTickPlanOn() throws IOException {
        switchTheDeclaredValues("  tick-plan: false", "  tick-plan: true");
    }

    /** The same switch with one further declared value moved, for a case that needs the other
     * producer or a smaller intake than the defaults declare. */
    private static void switchTheDeclaredValues(String declared, String wanted) throws IOException {
        assumeTrue(!Files.exists(CONFIG_DIR),
            "a prts-config directory already exists in the test working directory");
        Files.createDirectories(CONFIG_DIR);
        PrtsConfigManager.Entry entry = PrtsConfigManager.entries().get(PrtsConfigManager.KERNEL);
        String text = PrtsConfigManager.defaults(entry)
            .replace("  tick-plan: false", "  tick-plan: true");
        Files.writeString(CONFIG_DIR.resolve("kernel.yml"), text.replace(declared, wanted),
            StandardCharsets.UTF_8);
        PrtsConfigManager.ensureAndLoad();
        System.setProperty("arclight.prts.kernel", "true");
    }

    private static void restoreTheDeclaredDefaults() throws IOException {
        PrtsChunkDemand.install(null);
        KernelModule.instance().removeLoadProbe();
        System.clearProperty("arclight.prts.kernel");
        deleteTree(CONFIG_DIR);
        PrtsConfigManager.ensureAndLoad();
        deleteTree(CONFIG_DIR);
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }


    // ---- the freeze of a driven tick: the hand-off this batch adds ----

    private static final String NODE_WORLD = TICK_WORLD;
    private static final String FOREIGN_DOMAIN = "fixture-domain";

    /** The control frame the plan needs, with the reader installed and its counters cleared. The
     * window of the freeze is filled after this call and read by the next tick, which is the order
     * the host walks. */
    private static KernelModule openTheDrivenFreeze() throws IOException {
        switchTheTickPlanOn();
        return takeTheControlFrame();
    }

    /** The same, with one further declared value of the fixture moved. */
    private static KernelModule openTheDrivenFreezeWith(String declared, String wanted)
        throws IOException {
        switchTheDeclaredValues(declared, wanted);
        return takeTheControlFrame();
    }

    private static KernelModule takeTheControlFrame() {
        KernelModule module = KernelModule.instance();
        DemandShadowCandidates reader = module.demandShadowCandidates();
        module.serverTick(List.of(NODE_WORLD));
        reader.reset();
        PrtsChunkDemand.install(reader);
        return module;
    }

    /** The steps of the newest plan that the named domain carries. */
    private static List<TickPlan.CommitStep> stepsOf(KernelModule module, String domainId) {
        List<TickPlan.CommitStep> steps = new ArrayList<>();
        for (TickPlan.CommitStep step : module.plans().latest().commitOrder()) {
            if (step.domainId().equals(domainId)) {
                steps.add(step);
            }
        }
        return steps;
    }

    private static DemandShadowCandidates.Shadow capturedNode(
        List<DemandShadowCandidates.Shadow> window, String nodeKey) {
        for (DemandShadowCandidates.Shadow shadow : window) {
            if (shadow.nodeKey().equals(nodeKey)) {
                return shadow;
            }
        }
        return null;
    }

    /** The direct association reading: the declaration the plan took and the candidate the table
     * sealed name the capture the window read, three names at a time as themselves and never one
     * spliced text. Answers how many comparisons it made - one per step and one per entry - so a
     * window with nothing to hand over is read as the control it is and not as a failure. */
    private static int compareTheTwoHandOffs(KernelModule module) {
        List<DemandShadowCandidates.Shadow> window = module.demandShadowCandidates().lastCandidates();
        int compared = 0;
        for (TickPlan.CommitStep step : stepsOf(module, DemandShadowCandidates.DOMAIN)) {
            DemandShadowCandidates.Shadow shadow = capturedNode(window, step.nodeKey());
            assertNotNull(shadow, "the plan carries the node of a capture the window read");
            assertEquals(shadow.worldId(), step.worldId());
            assertEquals(shadow.domainId(), step.domainId());
            assertEquals(shadow.nodeKey(), step.nodeKey());
            compared++;
        }
        for (FrozenDeclarations.Entry entry : module.declarations().entries()) {
            DemandShadowCandidates.Shadow shadow = capturedNode(window, entry.nodeKey());
            assertNotNull(shadow, "the table carries the node of a capture the window read");
            assertEquals(shadow.worldId(), entry.seat().worldId());
            assertEquals(shadow.domainId(), entry.seat().domainId());
            assertEquals(shadow.nodeKey(), entry.nodeKey());
            compared++;
        }
        return compared;
    }

    private static JobDeclaration foreignDeclaration() {
        return new JobDeclaration(FOREIGN_DOMAIN + "/" + NODE_WORLD + "/fixture", 1L, NODE_WORLD,
            FOREIGN_DOMAIN, 0, List.of(), 0, "fixture", "world:" + NODE_WORLD, List.of(), List.of(),
            ShareClass.OTHER, JobDeclaration.SiteClass.PARALLEL, 0, 1, List.of());
    }

    @Test
    void aDemandCaptureWithAnArtefactBecomesACandidateAtTheFreeze() throws IOException {
        try {
            KernelModule module = openTheDrivenFreeze();
            PrtsChunkDemand.demandDescriptor(NODE_WORLD, 3, -7, STATUS, true);

            module.serverTick(List.of(NODE_WORLD));

            DemandShadowCandidates reader = module.demandShadowCandidates();
            List<DemandShadowCandidates.Shadow> window = reader.lastCandidates();
            assertEquals(1, window.size());
            DemandShadowCandidates.Shadow shadow = window.get(0);
            FrozenDeclarations table = module.declarations();
            assertEquals(FrozenDeclarations.SEALED, table.state(),
                "the capture carries every column, so the freeze seals a table of it");
            assertEquals(1, table.size());
            FrozenDeclarations.Entry entry = table.entries().get(0);
            assertEquals(shadow.nodeKey(), entry.nodeKey());
            assertEquals(shadow.chunk().packedPos(), entry.packedChunkPos());
            assertEquals(shadow.targetStatus(), entry.targetStatus());
            assertTrue(entry.instanceId() > 0L, "the one mint numbered the seat of this capture");
            assertEquals(shadow.worldEpoch(), entry.worldEpoch());
            assertEquals(shadow.worldId(), entry.seat().worldId());
            assertEquals(shadow.domainId(), entry.seat().domainId());
            assertEquals(module.plans().latest().planSequence(), table.planSequence());
            assertEquals("", module.declarationRefusal());
            assertEquals("SEALED", reading("product.commit.declaration_state"));
            assertEquals("1", reading("product.commit.declaration_entries"));
        } finally {
            restoreTheDeclaredDefaults();
        }
    }

    @Test
    void theFreezeHandsTheMergedListToSealAndNotASecondTable() throws IOException {
        try {
            KernelModule module = openTheDrivenFreezeWith("  pipeline-jobs: false",
                "  pipeline-jobs: true");
            module.pipelineBatches().mint("fixture-mailbox", NODE_WORLD, "fixture-mailbox",
                ChunkPos.asLong(0, 0), 0L);
            PrtsChunkDemand.demandDescriptor(NODE_WORLD, 3, -7, STATUS, true);

            module.serverTick(List.of(NODE_WORLD));

            assertEquals(1, module.pipelineJobCandidates(),
                "the round producer still hands its own candidate to the same merge");
            assertEquals(1, stepsOf(module, PipelineRoundJobs.DOMAIN).size(),
                "and its declaration still reaches the plan");
            assertEquals(1, stepsOf(module, DemandShadowCandidates.DOMAIN).size());
            FrozenDeclarations table = module.declarations();
            assertEquals(FrozenDeclarations.SEALED, table.state(),
                "the one merged list reaches the one table");
            assertEquals(1, table.size(),
                "the shadow capture is the declaration of the two that carries an artefact");
            assertEquals(module.demandShadowCandidates().lastCandidates().get(0).nodeKey(),
                table.entries().get(0).nodeKey());
        } finally {
            restoreTheDeclaredDefaults();
        }
    }

    @Test
    void aCandidateWithoutAChunkIsNeverHandedToThePlan() throws IOException {
        try {
            KernelModule module = openTheDrivenFreeze();
            long submitted = module.jobIntake().submitted();
            PrtsChunkDemand.futureOpened(NODE_WORLD, STATUS);

            module.serverTick(List.of(NODE_WORLD));

            List<DemandShadowCandidates.Shadow> window =
                module.demandShadowCandidates().lastCandidates();
            assertEquals(1, window.size(), "the ask is read as a capture of the window");
            assertFalse(window.get(0).declared(),
                "an ask that named no coordinates carries no artefact and is never the origin");
            assertEquals(DemandShadowCandidates.NOT_WIRED, window.get(0).state());
            assertEquals(submitted, module.jobIntake().submitted(),
                "no declaration is handed for a capture that carries no artefact");
            assertEquals(0, stepsOf(module, DemandShadowCandidates.DOMAIN).size());
            assertEquals(FrozenDeclarations.NOT_WIRED, module.declarations().state());
            assertEquals(0, module.declarations().size());
        } finally {
            restoreTheDeclaredDefaults();
        }
    }

    @Test
    void aDeclaredNodeWithoutASeatIsNotNumbered() throws IOException {
        try {
            KernelModule module = openTheDrivenFreezeWith("  job-queue-cap: 256",
                "  job-queue-cap: 1");
            module.jobIntake().submit(foreignDeclaration());
            long refused = module.jobIntake().refused();
            PrtsChunkDemand.demandDescriptor(NODE_WORLD, 3, -7, STATUS, true);

            module.serverTick(List.of(NODE_WORLD));

            DemandShadowCandidates reader = module.demandShadowCandidates();
            assertEquals(DemandShadowCandidates.NOT_EXECUTABLE,
                reader.lastCandidates().get(0).state(),
                "the capture carries every column and the plan still gave it no seat");
            assertEquals(refused + 1L, module.jobIntake().refused(),
                "the one intake refused the declaration and counted it");
            assertEquals(0, stepsOf(module, DemandShadowCandidates.DOMAIN).size(),
                "a declaration that never reached the intake is no node of the plan");
            assertEquals(1, stepsOf(module, FOREIGN_DOMAIN).size(),
                "the node the plan did carry takes a rank");
            assertEquals(0, module.declarations().size(), "and no entry, so nothing was numbered");
            assertEquals(FrozenDeclarations.NOT_WIRED, module.declarations().state());
        } finally {
            restoreTheDeclaredDefaults();
        }
    }

    @Test
    void aDrivenFreezeReadsTheWindowOnceAndHandsEachCaptureOnce() throws IOException {
        try {
            KernelModule module = openTheDrivenFreeze();
            PrtsChunkDemand.demandDescriptor(NODE_WORLD, 3, -7, STATUS, true);
            PrtsChunkDemand.demandDescriptor(NODE_WORLD, 4, -7, STATUS, true);
            DemandShadowCandidates reader = module.demandShadowCandidates();
            long submitted = module.jobIntake().submitted();
            long windows = reader.windows();

            module.serverTick(List.of(NODE_WORLD));

            assertEquals(windows + 1L, reader.windows(), "one window read per freeze and never two");
            assertEquals(2L, reader.lastRead(), "both hand-offs derive from the one returned list");
            assertEquals(2L, module.jobIntake().submitted() - submitted,
                "one declaration per handed capture and never two for one");
            assertEquals(2, stepsOf(module, DemandShadowCandidates.DOMAIN).size());
            assertEquals(2, module.declarations().size(), "the one merge hands both to the one table");
            assertEquals("2", reading("product.commit.declaration_entries"));
            assertEquals(4, compareTheTwoHandOffs(module),
                "two steps of the plan and two entries of the table, each compared element by element");
        } finally {
            restoreTheDeclaredDefaults();
        }
    }

    @Test
    void aDrivenFreezeReadsBothStatesAtTheWindowLevel() throws IOException {
        try {
            KernelModule module = openTheDrivenFreeze();
            PrtsChunkDemand.demandDescriptor(NODE_WORLD, 3, -7, STATUS, true);
            PrtsChunkDemand.futureOpened(NODE_WORLD, STATUS);

            module.serverTick(List.of(NODE_WORLD));

            DemandShadowCandidates reader = module.demandShadowCandidates();
            TreeSet<String> states = new TreeSet<>();
            for (DemandShadowCandidates.Shadow shadow : reader.lastCandidates()) {
                states.add(shadow.state());
            }
            assertEquals(new TreeSet<>(List.of(DemandShadowCandidates.NOT_WIRED,
                DemandShadowCandidates.NOT_EXECUTABLE)), states,
                "a driven freeze reads exactly the two states the class answers");
            assertEquals(1L, reader.unexecutable(), "the capture carrying every column");
            assertEquals(1L, reader.notWired(), "and the one that carries no chunk");
            assertEquals(FrozenDeclarations.SEALED, module.declarations().state());
            assertEquals(1, module.declarations().size(), "only the complete capture is handed over");
            assertEquals(1, stepsOf(module, DemandShadowCandidates.DOMAIN).size());
        } finally {
            restoreTheDeclaredDefaults();
        }
    }

    @Test
    void aDrivenFreezeCountsTheAsksItDroppedOrRefused() throws IOException {
        try {
            KernelModule module = openTheDrivenFreeze();
            DemandShadowCandidates reader = module.demandShadowCandidates();
            // The bound first: 65 asks fill the ring once, the oldest is dropped and counted, and the
            // freeze that reads it keeps 64 of them.
            for (int index = 0; index <= DemandShadowCandidates.CAP; index++) {
                PrtsChunkDemand.demandDescriptor(NODE_WORLD, index, 0, STATUS, true);
            }

            module.serverTick(List.of(NODE_WORLD));

            assertEquals(DemandShadowCandidates.CAP + 1L, reader.captured(),
                "the drop is a bound and never the number of asks that happened");
            assertEquals(1L, reader.dropped(), "the capture the full ring cost is counted");
            assertEquals(DemandShadowCandidates.CAP, reader.lastRead(),
                "the window kept the bound and not one more");
            assertEquals(DemandShadowCandidates.CAP, module.declarations().size(),
                "and the freeze sealed exactly the captures the window kept");
            assertEquals("SEALED", reading("product.commit.declaration_state"));
            assertEquals(String.valueOf(DemandShadowCandidates.CAP),
                reading("product.commit.declaration_entries"));

            // Then the whole-window refusal: three asks of which one world-and-status pair a
            // completion marked before the freeze.
            reader.reset();
            PrtsChunkDemand.demandDescriptor(NODE_WORLD, 3, -7, STATUS, true);
            PrtsChunkDemand.demandDescriptor(NODE_WORLD, 4, -7, STATUS, true);
            PrtsChunkDemand.demandDescriptor(NODE_WORLD, 5, -7, "empty", true);
            PrtsChunkDemand.futureCompleted(NODE_WORLD, "empty", 0L, true);
            long submitted = module.jobIntake().submitted();

            module.serverTick(List.of(NODE_WORLD));

            assertEquals(3L, reader.captured(),
                "the three asks happened: a refusal is never read as no ask at all");
            assertEquals(1L, reader.windows(), "one freeze read the window once");
            assertEquals(1L, reader.lateRefusals(), "one pair of them was marked by a completion");
            assertEquals(2L, reader.unexecutable(), "the window holds complete captures");
            assertEquals(1L, reader.notWired(), "and the marked one is never read as an absence");
            assertEquals(submitted, module.jobIntake().submitted(),
                "a window refused as a whole hands no declaration over");
            assertEquals(0, stepsOf(module, DemandShadowCandidates.DOMAIN).size());
            assertEquals(FrozenDeclarations.NOT_WIRED, module.declarations().state());
            assertEquals(0, module.declarations().size(),
                "handing the captures the marking did not reach over is the failure this forbids");
            assertEquals("0", reading("product.commit.declaration_entries"));
        } finally {
            restoreTheDeclaredDefaults();
        }
    }

    @Test
    void theTwoHandOffsOfOneCaptureCarryTheSameThreeNames() throws IOException {
        try {
            KernelModule module = openTheDrivenFreeze();
            PrtsChunkDemand.demandDescriptor(NODE_WORLD, 3, -7, STATUS, true);

            module.serverTick(List.of(NODE_WORLD));

            assertEquals(2, compareTheTwoHandOffs(module),
                "the declaration and the candidate name the same node, element by element");
        } finally {
            restoreTheDeclaredDefaults();
        }
    }

    @Test
    void aWindowWithNothingToDeclareLeavesTheAssociationUntouched() throws IOException {
        try {
            KernelModule module = openTheDrivenFreeze();
            PrtsChunkDemand.futureOpened(NODE_WORLD, STATUS);

            module.serverTick(List.of(NODE_WORLD));

            assertEquals(0, compareTheTwoHandOffs(module),
                "a window that hands nothing over has no pair to compare and trips nothing");
            assertEquals(0, module.declarations().size(),
                "and it ends with no entry, which is the reading that is not the discriminator");
            assertEquals(FrozenDeclarations.NOT_WIRED, module.declarations().state());
        } finally {
            restoreTheDeclaredDefaults();
        }
    }

    /** The conversion of one window as the carrier itself answers it: one declaration and one
     * candidate per handed capture, the same three names on both sides, the number column left to the
     * freeze point, and a copy of the window refused where the one read returned it.
     *
     * <p>The two entry points are reached by name here on purpose: this case has to compile on the
     * tree that has neither of them, which is the tree the break-first reading and the rollback of
     * the production files are taken on.
     */
    @Test
    void theConversionOfOneWindowHandsOneDeclarationAndOneCandidatePerCapture() throws Exception {
        PrtsChunkDemand.demandDescriptor(WORLD, 0, 0, STATUS, true);
        PrtsChunkDemand.demandDescriptor(WORLD, 3, -7, STATUS, true);
        PrtsChunkDemand.futureOpened(WORLD, STATUS);

        List<DemandShadowCandidates.Shadow> window = read(5L);

        List<DemandShadowCandidates.Shadow> copy = new ArrayList<>(window);
        assertThrows(InvocationTargetException.class, () -> candidatesOf(copy),
            "an equal but distinct copy is not the window the one read returned");
        assertThrows(InvocationTargetException.class, () -> declarationsOf(copy),
            "and neither hand-off reads one");
        List<?> declared = declarationsOf(window);
        List<?> handed = candidatesOf(window);
        assertEquals(2, declared.size(), "one declaration per handed capture");
        assertEquals(2, handed.size(), "and one candidate per handed capture");
        for (int index = 0; index < handed.size(); index++) {
            JobDeclaration declaration = (JobDeclaration) declared.get(index);
            FrozenDeclarations.Candidate candidate = (FrozenDeclarations.Candidate) handed.get(index);
            assertEquals(candidate.worldId(), declaration.worldId());
            assertEquals(candidate.domainId(), declaration.domainId());
            assertEquals(candidate.nodeKey(), declaration.key(),
                "the key the plan matches the candidate on is the capture's own node key");
            assertTrue(candidate.artefactDeclared(), "only a capture of every column is handed over");
            assertEquals(0L, candidate.instanceId(),
                "the freeze point mints the number and this side never fills the column in");
        }
        FrozenDeclarations.Candidate origin = (FrozenDeclarations.Candidate) handed.get(0);
        assertEquals(ChunkPos.asLong(0, 0), origin.chunk().packedPos(),
            "an ask that named the origin is a capture like any other and is handed over");

        // A window a completion marked is refused as a whole, and never capture by capture.
        PrtsChunkDemand.demandDescriptor(WORLD, 5, 5, STATUS, true);
        PrtsChunkDemand.demandDescriptor(WORLD, 6, 5, "empty", true);
        PrtsChunkDemand.futureCompleted(WORLD, "empty", 0L, true);
        List<DemandShadowCandidates.Shadow> marked = read(6L);

        assertEquals(2, marked.size());
        assertTrue(marked.get(1).late(), "the completion marked the pair it answers");
        assertTrue(declarationsOf(marked).isEmpty(), "and the window is refused as a whole");
        assertTrue(candidatesOf(marked).isEmpty(),
            "so the capture the marking did not reach is not handed over either");
    }

    private List<?> declarationsOf(List<DemandShadowCandidates.Shadow> window) throws Exception {
        return (List<?>) DemandShadowCandidates.class.getMethod("declarationsOf", List.class)
            .invoke(candidates, window);
    }

    private List<?> candidatesOf(List<DemandShadowCandidates.Shadow> window) throws Exception {
        return (List<?>) DemandShadowCandidates.class.getMethod("candidatesOf", List.class)
            .invoke(candidates, window);
    }

    @Test
    void aDrivenFreezeSealsTheOneCaptureItsOwnWindowCarried() throws IOException {
        try {
            KernelModule module = openTheDrivenFreeze();
            DemandShadowCandidates reader = module.demandShadowCandidates();
            PrtsChunkDemand.demandDescriptor(NODE_WORLD, 3, -7, STATUS, true);
            long sealed = module.declarationsSealed();
            long roundCandidates = module.pipelineJobCandidates();

            module.serverTick(List.of(NODE_WORLD));

            assertEquals(1L, reader.windows(), "the freeze read the window of its own tick");
            List<DemandShadowCandidates.Shadow> read = reader.lastCandidates();
            assertEquals(1, read.size());
            DemandShadowCandidates.Shadow shadow = read.get(0);
            assertEquals(NODE_WORLD, shadow.worldId());
            assertEquals(ChunkPos.asLong(3, -7), shadow.chunk().packedPos());
            assertEquals(STATUS, shadow.targetStatus());
            assertTrue(shadow.worldEpoch() > 0L, "the world was tracked and its generation was read");
            assertTrue(shadow.planSequence() > 0L);
            assertTrue(shadow.freeze().generation() > 0L);
            assertEquals(DemandShadowCandidates.NOT_EXECUTABLE, shadow.state(),
                "the capture is complete and the freeze still has no witness for it");
            assertEquals(sealed + 1L, module.declarationsSealed(),
                "the freeze sealed a table of the plan's own declarations");
            assertEquals(FrozenDeclarations.SEALED, module.declarations().state(),
                "and the capture the window read is the one entry of it");
            assertEquals(1, module.declarations().size());
            assertEquals("", module.declarationRefusal(), "nothing was refused, nothing was taken");
            assertEquals(roundCandidates, module.pipelineJobCandidates(),
                "the shadow window is not the pipeline producer's candidate list");
            assertEquals("SEALED", reading("product.commit.declaration_state"));
            assertEquals("1", reading("product.commit.declaration_entries"));
        } finally {
            restoreTheDeclaredDefaults();
        }
    }
}

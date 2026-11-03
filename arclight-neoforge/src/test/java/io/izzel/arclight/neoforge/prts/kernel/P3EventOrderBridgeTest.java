/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.neoforge.prts.kernel;

import io.izzel.arclight.common.prts.kernel.KernelModule;
import io.izzel.arclight.common.prts.kernel.observe.EventOrderProbe;
import io.izzel.arclight.common.prts.kernel.observe.KernelReadings;
import net.neoforged.bus.EventBus;
import net.neoforged.bus.ListenerList;
import net.neoforged.bus.api.BusBuilder;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.EventListener;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.ICancellableEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The platform bridge against the real NeoForge bus: the frozen dispatch list, priority and
 * registration order, phases, inheritance, cancellation, register and unregister, nested posts,
 * exceptions and the switch sequence are read without changing any of them. */
class P3EventOrderBridgeTest {

    private static final List<String> LOG = new ArrayList<>();

    @BeforeEach
    void armTheBridge() {
        LOG.clear();
        System.setProperty(EventOrderProbe.PROPERTY, "true");
        PrtsEventOrderBridge.install();
        EventOrderProbe.reset();
    }

    @AfterEach
    void disarmTheBridge() {
        PrtsEventOrderBridge.remove();
        System.clearProperty(EventOrderProbe.PROPERTY);
    }

    public static class SampleEvent extends Event {
    }

    public static class BaseSampleEvent extends Event {
    }

    public static class SubSampleEvent extends BaseSampleEvent {
    }

    public static class CancelEvent extends Event implements ICancellableEvent {

        private boolean canceled;

        @Override
        public void setCanceled(boolean canceled) {
            this.canceled = canceled;
        }

        @Override
        public boolean isCanceled() {
            return canceled;
        }
    }

    public static class Annotated {

        @SubscribeEvent
        public void onSample(SampleEvent event) {
            LOG.add("annotated");
        }
    }

    private static EventBus bus() {
        return (EventBus) BusBuilder.builder().build();
    }

    private static ServerTickEvent.Post rootPost() {
        return new ServerTickEvent.Post(() -> true, null);
    }

    private static ListenerList listenerList(EventBus bus, Class<? extends Event> type)
        throws Exception {
        Method method = EventBus.class.getDeclaredMethod("getListenerList", Class.class);
        method.setAccessible(true);
        return (ListenerList) method.invoke(bus, type);
    }

    private static EventListener[] frozen(EventBus bus, Class<? extends Event> type)
        throws Exception {
        return listenerList(bus, type).getListeners();
    }

    private static void observe(EventBus bus, Event event, EventListener[] frozen,
                                Runnable nested) {
        PrtsEventOrderBridge.beginDispatch(bus, event, frozen);
        try {
            for (EventListener listener : frozen) {
                PrtsEventOrderBridge.noteInvocation(listener);
                if (nested != null) {
                    Runnable once = nested;
                    nested = null;
                    once.run();
                }
                listener.invoke(event);
            }
            PrtsEventOrderBridge.endDispatch(false);
        } catch (Throwable thrown) {
            PrtsEventOrderBridge.endDispatch(true);
            throw thrown;
        }
    }

    private static void observe(EventBus bus, Event event, EventListener[] frozen) {
        observe(bus, event, frozen, null);
    }

    @Test
    void theLoadedBusIsTheNeoForgeEventBusWithFivePriorities() {
        EventBus bus = bus();
        assertEquals("net.neoforged.bus.EventBus", bus.getClass().getName());
        assertEquals(5, EventPriority.values().length);
        assertTrue(PrtsEventOrderBridge.rootEvent(new ServerTickEvent.Pre(() -> true, null)));
        assertTrue(PrtsEventOrderBridge.rootEvent(rootPost()));
        assertTrue(PrtsEventOrderBridge.rootEvent(new LevelEvent.Unload(null)));
        assertFalse(PrtsEventOrderBridge.rootEvent(new SampleEvent()));
    }

    @Test
    void theRealBusKeepsThePriorityOrderAndSamePriorityRegistrationOrder() {
        EventBus bus = bus();
        bus.addListener(EventPriority.LOWEST, (SampleEvent event) -> LOG.add("lowest"));
        bus.addListener(EventPriority.HIGH, (SampleEvent event) -> LOG.add("high-1"));
        bus.addListener(EventPriority.HIGH, (SampleEvent event) -> LOG.add("high-2"));
        bus.addListener(EventPriority.HIGHEST, (SampleEvent event) -> LOG.add("highest"));

        bus.post(new SampleEvent());

        assertEquals(List.of("highest", "high-1", "high-2", "lowest"), LOG);
    }

    @Test
    void theObservedRootCarriesTheFrozenListInTheSameOrder() throws Exception {
        EventBus bus = bus();
        bus.addListener(EventPriority.LOWEST, (ServerTickEvent.Post event) -> LOG.add("lowest"));
        bus.addListener(EventPriority.HIGH, (ServerTickEvent.Post event) -> LOG.add("high"));
        bus.addListener(EventPriority.HIGHEST, (ServerTickEvent.Post event) -> LOG.add("highest"));
        EventListener[] frozen = frozen(bus, ServerTickEvent.Post.class);

        observe(bus, rootPost(), frozen);

        assertEquals(List.of("highest", "high", "lowest"), LOG);
        EventOrderProbe.Reading reading = EventOrderProbe.reading().orElseThrow();
        assertEquals(0L, reading.orderViolation());
        assertEquals(1, reading.nestingDepth());
    }

    @Test
    void aPhaseSpecificPostOnlyCarriesThatPriorityPhase() throws Exception {
        EventBus bus = (EventBus) BusBuilder.builder().allowPerPhasePost().build();
        bus.addListener(EventPriority.HIGH, (ServerTickEvent.Post event) -> LOG.add("high"));
        bus.addListener(EventPriority.LOW, (ServerTickEvent.Post event) -> LOG.add("low"));

        bus.post(EventPriority.LOW, rootPost());

        assertEquals(List.of("low"), LOG);
        EventListener[] phase = listenerList(bus, ServerTickEvent.Post.class)
            .getPhaseListeners(EventPriority.LOW);
        LOG.clear();
        observe(bus, rootPost(), phase);
        assertEquals(List.of("low"), LOG);
        assertEquals(0L, EventOrderProbe.reading().orElseThrow().orderViolation());
    }

    @Test
    void theFrozenListOfASubclassCarriesTheInheritedListener() throws Exception {
        EventBus bus = bus();
        bus.addListener(EventPriority.NORMAL, (BaseSampleEvent event) -> LOG.add("parent"));
        bus.addListener(EventPriority.NORMAL, (SubSampleEvent event) -> LOG.add("child"));
        EventListener[] frozen = frozen(bus, SubSampleEvent.class);

        bus.post(new SubSampleEvent());

        assertEquals(List.of("child", "parent"), LOG);
        assertEquals(2, frozen.length);
    }

    @Test
    void aCancelledEventKeepsTheBusCancellationSemantics() {
        EventBus bus = bus();
        AtomicReference<CancelEvent> seen = new AtomicReference<>();
        bus.addListener((CancelEvent event) -> event.setCanceled(true));
        bus.addListener((CancelEvent event) -> seen.set(event));
        CancelEvent event = new CancelEvent();

        bus.post(event);

        assertTrue(event.isCanceled());
        assertNull(seen.get());
    }

    @Test
    void registerAndUnregisterChangeOnlyTheFrozenList() throws Exception {
        EventBus bus = bus();
        Annotated annotated = new Annotated();
        bus.register(annotated);
        assertEquals(1, frozen(bus, SampleEvent.class).length);

        bus.unregister(annotated);

        assertEquals(0, frozen(bus, SampleEvent.class).length);
        bus.post(new SampleEvent());
        assertTrue(LOG.isEmpty());
    }

    @Test
    void aNestedPostReachesDepthThree() throws Exception {
        EventBus bus = bus();
        bus.addListener(EventPriority.NORMAL, (ServerTickEvent.Post event) -> LOG.add("root"));
        EventListener[] root = frozen(bus, ServerTickEvent.Post.class);
        Runnable depthThree = () -> observe(bus, rootPost(), root);
        Runnable depthTwo = () -> observe(bus, rootPost(), root, depthThree);

        observe(bus, rootPost(), root, depthTwo);

        assertEquals(3, EventOrderProbe.reading().orElseThrow().nestingDepth());
        assertEquals(0L, EventOrderProbe.reading().orElseThrow().orderViolation());
        assertEquals(3, LOG.size());
    }

    @Test
    void aNestedPostOfAnotherBusDoesNotJoinTheRootSubtree() throws Exception {
        EventBus bus = bus();
        EventBus other = bus();
        bus.addListener((ServerTickEvent.Post event) -> LOG.add("root"));
        other.addListener((SampleEvent event) -> LOG.add("foreign"));
        EventListener[] root = frozen(bus, ServerTickEvent.Post.class);
        EventListener[] foreign = frozen(other, SampleEvent.class);
        Runnable nested = () -> observe(other, new SampleEvent(), foreign);

        observe(bus, rootPost(), root, nested);

        assertEquals(1, EventOrderProbe.reading().orElseThrow().nestingDepth());
        assertEquals(List.of("foreign", "root"), LOG);
    }

    @Test
    void aRootOnAnotherThreadIsItsOwnSynchronousSubtree() throws Exception {
        EventBus bus = bus();
        EventListener[] frozen = frozen(bus, ServerTickEvent.Post.class);
        CountDownLatch done = new CountDownLatch(1);
        Thread thread = new Thread(() -> {
            observe(bus, rootPost(), frozen);
            done.countDown();
        });
        thread.start();
        done.await();
        observe(bus, rootPost(), frozen);

        assertEquals(1, EventOrderProbe.reading().orElseThrow().nestingDepth());
        assertEquals(0L, EventOrderProbe.reading().orElseThrow().orderViolation());
    }

    @Test
    void aListenerFailureRethrowsTheSameThrowable() {
        EventBus bus = bus();
        IllegalStateException failure = new IllegalStateException("listener");
        bus.addListener((SampleEvent event) -> {
            throw failure;
        });

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
            () -> bus.post(new SampleEvent()));

        assertSame(failure, thrown);
    }

    @Test
    void aFailedObservedDispatchLeavesNoReading() throws Exception {
        EventBus bus = bus();
        EventListener[] frozen = frozen(bus, ServerTickEvent.Post.class);
        PrtsEventOrderBridge.beginDispatch(bus, rootPost(), frozen);
        PrtsEventOrderBridge.endDispatch(true);

        assertTrue(EventOrderProbe.reading().isEmpty());
    }

    @Test
    void anOutOfOrderActualInvocationIsOneViolation() throws Exception {
        EventBus bus = bus();
        ServerTickEvent.Post event = rootPost();
        bus.addListener((ServerTickEvent.Post ignored) -> LOG.add("first"));
        bus.addListener((ServerTickEvent.Post ignored) -> LOG.add("second"));
        EventListener[] frozen = frozen(bus, ServerTickEvent.Post.class);

        PrtsEventOrderBridge.beginDispatch(bus, event, frozen);
        PrtsEventOrderBridge.noteInvocation(frozen[1]);
        frozen[1].invoke(event);
        PrtsEventOrderBridge.noteInvocation(frozen[0]);
        frozen[0].invoke(event);
        PrtsEventOrderBridge.endDispatch(false);

        assertEquals(1L, EventOrderProbe.reading().orElseThrow().orderViolation());
    }

    @Test
    void theSwitchPublishesNothingWhileOff() throws Exception {
        EventBus bus = bus();
        EventListener[] frozen = frozen(bus, ServerTickEvent.Post.class);
        System.setProperty(EventOrderProbe.PROPERTY, "false");
        observe(bus, rootPost(), frozen);
        assertTrue(EventOrderProbe.reading().isEmpty());

        System.setProperty(EventOrderProbe.PROPERTY, "true");
        EventOrderProbe.reset();
        assertTrue(EventOrderProbe.reading().isEmpty());
        observe(bus, rootPost(), frozen);
        assertTrue(EventOrderProbe.reading().isPresent());
    }

    @Test
    void theKernelReadingsCarryTheTwoRowsAndTheRawExportFeedsTheParser() throws Exception {
        EventBus bus = bus();
        EventListener[] frozen = frozen(bus, ServerTickEvent.Post.class);

        KernelModule module = KernelModule.instance();
        module.resetReadings();
        EventOrderProbe.reset();
        observe(bus, rootPost(), frozen);

        List<String> export = KernelReadings.export(module);
        assertTrue(export.contains("event.order_violation=0"), () -> String.join("\n", export));
        assertTrue(export.contains("event.nesting_depth=1"), () -> String.join("\n", export));

        Path directory = Path.of("build", "p3-event");
        Files.createDirectories(directory);
        writeRaw(directory.resolve("raw-zero.txt"), module);
        String runId = "unnamed";
        String marker = "product.commit.run_identity=";
        for (String line : KernelReadings.forensicReadings(module)) {
            int at = line.indexOf(marker);
            if (at < 0) {
                continue;
            }
            String value = line.substring(at + marker.length());
            int runIdAt = value.indexOf("runId=");
            if (runIdAt >= 0) {
                String rest = value.substring(runIdAt + "runId=".length());
                int space = rest.indexOf(' ');
                runId = space < 0 ? rest : rest.substring(0, space);
                break;
            }
        }
        Files.writeString(directory.resolve("run-id.txt"), runId + "\n", StandardCharsets.UTF_8);

        module.resetReadings();
        writeRaw(directory.resolve("raw-not-run.txt"), module);

        EventOrderProbe.reset();
        EventBus mutationBus = bus();
        mutationBus.addListener((ServerTickEvent.Post ignored) -> LOG.add("first"));
        mutationBus.addListener((ServerTickEvent.Post ignored) -> LOG.add("second"));
        EventListener[] mutation = frozen(mutationBus, ServerTickEvent.Post.class);
        ServerTickEvent.Post event = rootPost();
        PrtsEventOrderBridge.beginDispatch(mutationBus, event, mutation);
        PrtsEventOrderBridge.noteInvocation(mutation[1]);
        mutation[1].invoke(event);
        PrtsEventOrderBridge.noteInvocation(mutation[0]);
        mutation[0].invoke(event);
        PrtsEventOrderBridge.endDispatch(false);
        writeRaw(directory.resolve("raw-positive.txt"), module);
    }

    private static void writeRaw(Path file, KernelModule module) throws Exception {
        List<String> raw = new ArrayList<>();
        for (String line : KernelReadings.forensicReadings(module)) {
            raw.add("[PRTS] kernel " + line);
        }
        Files.write(file, raw, StandardCharsets.UTF_8);
    }
}

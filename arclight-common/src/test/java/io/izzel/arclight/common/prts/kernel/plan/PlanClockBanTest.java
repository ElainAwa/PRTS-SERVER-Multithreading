/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.plan;

import io.izzel.arclight.common.prts.kernel.plan.TickPlanStore;
import io.izzel.arclight.common.prts.kernel.KernelModule;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.RecordComponent;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The planning period may not read a wall clock. The check is taken on the compiled classes of the
 * planning and job packages: their constant pools are read and searched for the names of the clock
 * entries, and the same scan is shown to find them in a class that does read a clock, so a scan that
 * finds nothing is evidence and not an empty result. */
class PlanClockBanTest {

    /** The names a clock read leaves in the constant pool of the reading class. */
    private static final List<String> CLOCK_TOKENS = List.of("nanoTime", "currentTimeMillis",
        "java/time/Instant", "java/time/Clock", "java/time/LocalDateTime", "java/time/ZonedDateTime",
        "java/time/Duration", "java/util/Date");

    /** The packages whose classes are part of the planning period: the plan itself and the job layer
     * it freezes. */
    private static final List<String> PLAN_PACKAGES = List.of("plan", "jobs");

    @Test
    void noClassOfThePlanningPeriodNamesAClock() throws IOException, URISyntaxException {
        Path classes = classesRoot();
        List<String> scanned = new ArrayList<>();
        List<String> violations = new ArrayList<>();
        for (String subpackage : PLAN_PACKAGES) {
            Path directory = classes.resolve("io/izzel/arclight/common/prts/kernel").resolve(subpackage);
            assertTrue(Files.isDirectory(directory),
                () -> "no compiled package to scan at " + directory);
            try (Stream<Path> files = Files.walk(directory)) {
                for (Path file : files.filter(path -> path.toString().endsWith(".class")).toList()) {
                    byte[] bytes = Files.readAllBytes(file);
                    scanned.add(file.getFileName().toString());
                    List<String> found = scan(bytes);
                    if (!found.isEmpty()) {
                        violations.add(file.getFileName() + " names " + found);
                    }
                }
            }
        }

        assertFalse(scanned.isEmpty(), "the scan read no class at all");
        assertTrue(scanned.size() >= 15,
            () -> "the scan read only " + scanned.size() + " classes: " + scanned);
        assertTrue(violations.isEmpty(),
            () -> "the planning period reads a wall clock: " + violations);
    }

    @Test
    void theScannerFindsAClockInAClassThatReadsOne() throws IOException {
        // The driver of the kernel takes the tick start from the wall clock, so its own class is the
        // positive control: a scanner that cannot find the token there proves nothing about the
        // planning period.
        byte[] bytes = classBytes(KernelModule.class);

        assertFalse(scan(bytes).isEmpty());
    }

    @Test
    void theScannerFindsATokenInASyntheticClassFile() {
        byte[] planted = "not a class file, just a constant pool entry: nanoTime".getBytes(
            StandardCharsets.UTF_8);

        assertEquals(List.of("nanoTime"), scan(planted));
    }

    @Test
    void noPlanInputCarriesATimestamp() {
        List<String> offenders = new ArrayList<>();
        for (Class<?> type : List.of(TickPlanPlanner.Input.class, TickPlan.class, TickPlanStore.Control.class)) {
            for (RecordComponent component : type.getRecordComponents()) {
                String name = component.getName().toLowerCase(Locale.ROOT);
                if (name.contains("time") || name.contains("nanos") || name.contains("millis")
                    || name.contains("instant") || name.contains("date") || name.contains("clock")) {
                    offenders.add(type.getSimpleName() + "." + component.getName());
                }
                String componentType = component.getType().getName();
                if (componentType.startsWith("java.time") || componentType.equals("java.util.Date")
                    || componentType.equals("java.util.Calendar")) {
                    offenders.add(type.getSimpleName() + "." + component.getName() + ":"
                        + componentType);
                }
            }
        }

        assertTrue(offenders.isEmpty(), () -> "a plan input carries a timestamp: " + offenders);
    }

    /** The names of the clock entries one class file mentions. */
    static List<String> scan(byte[] classBytes) {
        String text = new String(classBytes, StandardCharsets.ISO_8859_1);
        List<String> found = new ArrayList<>();
        for (String token : CLOCK_TOKENS) {
            if (text.contains(token)) {
                found.add(token);
            }
        }
        return found;
    }

    private static Path classesRoot() throws URISyntaxException {
        Path location = Path.of(TickPlan.class.getProtectionDomain().getCodeSource().getLocation()
            .toURI());
        assertTrue(Files.isDirectory(location),
            () -> "the scan needs the compiled classes on disk, saw " + location);
        return location;
    }

    private static byte[] classBytes(Class<?> type) throws IOException {
        String resource = type.getName().replace('.', '/') + ".class";
        try (InputStream stream = type.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(stream, () -> "no class file for " + resource);
            return stream.readAllBytes();
        }
    }
}

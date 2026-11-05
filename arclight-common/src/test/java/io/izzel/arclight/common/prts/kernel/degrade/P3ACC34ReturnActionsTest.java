/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.degrade;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real top-down rollback: the top rung returns only when its own gate is ready, and the next
 * lower rung can then become the deepest one; no rung waits for a deeper rung to have left first. */
class P3ACC34ReturnActionsTest {

    @Test
    void allFourRealReturnActionsExistAfterTheirCleanGate() throws Exception {
        String source = Files.readString(Path.of(
            "src/main/java/io/izzel/arclight/common/prts/kernel/KernelModule.java"));
        for (String function : new String[]{"maybeReturnB1", "maybeReturnB2",
            "maybeReturnB3", "maybeReturnB5"}) {
            assertTrue(source.contains(function), function);
        }
        for (String call : new String[]{
            "ladder.noteReturned(DegradeLevel.B1)",
            "ladder.noteReturned(DegradeLevel.B2)",
            "ladder.noteReturned(DegradeLevel.B3)",
            "ladder.noteReturned(DegradeLevel.B5)"}) {
            assertTrue(source.contains(call), call);
        }
    }

    @Test
    void theTopRungIsReturnedWhileItIsStillDeepest() throws Exception {
        String source = Files.readString(Path.of(
            "src/main/java/io/izzel/arclight/common/prts/kernel/KernelModule.java"));
        assertTrue(source.contains("ladder.sign().deepest() != DegradeLevel.B2"));
        assertTrue(source.contains("ladder.sign().deepest() != DegradeLevel.B5"));
        assertTrue(source.contains("bindSecondCondition(DegradeLevel.B2"));
        assertTrue(source.contains("bindSecondCondition(DegradeLevel.B3"));
        assertTrue(source.contains("b3ReturnCondition"));
        assertTrue(source.contains("maybeReturnB4"));
        int b5 = source.indexOf("maybeReturnB5();");
        int b4 = source.indexOf("maybeReturnB4();");
        int b3 = source.indexOf("maybeReturnB3();");
        int b1 = source.indexOf("maybeReturnB1();");
        assertTrue(b5 >= 0 && b4 > b5 && b3 > b4 && b1 > b3,
            "returns run from the highest rung to the lowest");
    }
}

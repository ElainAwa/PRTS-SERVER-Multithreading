/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.degrade;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The source contract of the event rung's return: the second condition is bound to the real EVENT
 * row and the real deferral hand-off (not an internal backlog fixture), both former return entry
 * points are one decision that goes through the ladder gate, and the pump and the disabled/stop
 * paths never count a return. */
class B4ReturnSourceContractTest {

    private static final Path KERNEL = Path.of(
        "src/main/java/io/izzel/arclight/common/prts/kernel/KernelModule.java");
    private static final Path LADDER = Path.of(
        "src/main/java/io/izzel/arclight/common/prts/kernel/degrade/DegradeLadder.java");

    private static String kernel() throws Exception {
        return Files.readString(KERNEL);
    }

    private static String region(String source, String from, int length) {
        int start = source.indexOf(from);
        assertTrue(start >= 0, from);
        return source.substring(start, Math.min(source.length(), start + length));
    }

    @Test
    void theSecondConditionIsBoundToTheRealEventRowAndHandoff() throws Exception {
        String source = kernel();
        assertTrue(source.contains("bindSecondCondition(DegradeLevel.B4, \"budget.event_over\""),
            "B4 second condition binding");
        String binding = region(source, "bindSecondCondition(DegradeLevel.B4", 260);
        assertTrue(binding.contains("hasOver(ShareClass.EVENT)"), binding);
        assertTrue(binding.contains("rconDeferral.idle()"), binding);
        assertFalse(binding.contains("EventBacklog"), binding);
    }

    @Test
    void theFormerTwoReturnEntriesAreOneGateCheckedDecision() throws Exception {
        String source = kernel();
        assertTrue(source.contains("private void maybeReturnB4()"), "single B4 return entry");
        assertFalse(source.contains("private void maybeReturnRcon"), "old second entry removed");
        assertTrue(source.contains("static final class B4Return"), "decision holder");
        String decision = region(source, "static final class B4Return", 1800);
        assertTrue(decision.contains("static boolean ready("), decision);
        assertTrue(decision.contains("static boolean apply("), decision);
        assertTrue(decision.contains("ladder.gate(DegradeLevel.B4, windowTicks)"), decision);
        assertTrue(decision.contains("return ladder.noteReturned(DegradeLevel.B4);"), decision);
        assertFalse(decision.contains("noteEffective"), "a return never fakes an effective action");
        String entry = region(source, "private void maybeReturnB4()", 900);
        assertTrue(entry.contains("B4Return.apply(ladder, ladder.sign().deepest(),"),
            entry);
        assertTrue(entry.contains("rconDeferralWanted()"), entry);
        assertTrue(entry.contains("rconDeferral.idle()"), entry);
        assertTrue(entry.contains("KernelSettings.degradeRollbackTicks()"), entry);
        assertEqualsOneReturn(source);
    }

    private static void assertEqualsOneReturn(String source) {
        int count = 0;
        int from = 0;
        while (true) {
            int index = source.indexOf("ladder.noteReturned(DegradeLevel.B4)", from);
            if (index < 0) {
                break;
            }
            count++;
            from = index + 1;
        }
        assertTrue(count == 1, "B4 return counted from one place, found " + count);
    }

    @Test
    void theBuyerSidePathsDoNotCountAReturn() throws Exception {
        String source = kernel();
        String pump = region(source, "public void pumpRconDeferrals()", 700);
        assertTrue(pump.contains("rconDeferral.terminateQueued();"), pump);
        assertTrue(pump.contains("rconActionPending = false;"), pump);
        assertTrue(pump.indexOf("maybeReturnB4();") > pump.indexOf("return;"),
            "a disabled kernel leaves the pump without a return: " + pump);
        String terminate = region(source, "public void terminateRconDeferrals()", 300);
        assertTrue(terminate.contains("rconActionPending = false;"), terminate);
        assertFalse(terminate.contains("noteReturned"), terminate);
        String clear = region(source, "private void clearReadings()", 4000);
        assertTrue(clear.contains("rconDeferral.resetQueued();"), clear);
        assertTrue(clear.contains("rconActionPending = false;"), clear);
    }

    @Test
    void theLadderCommentDescribesTheRealConsumers() throws Exception {
        String ladder = Files.readString(LADDER);
        assertFalse(ladder.contains("No action of this ladder runs in this build."),
            "stale no-action statement");
        assertTrue(ladder.contains("off by default"), "flag default is stated");
        assertTrue(ladder.contains("B2") && ladder.contains("B4"),
            "the real consumers are named");
        String plan = region(kernel(), "ladder.noteTick(lastDecision.degraded())", 400);
        assertTrue(plan.contains("maybeReturnB4();"), plan);
    }
}

/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.observe;

import io.izzel.arclight.common.prts.config.PrtsCommandExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The kernel command face offers the named end flush of the ordered claim reports and answers it
 * with a count plus a non-terminal explanation; the off path answers zero without asking a log. */
class KernelDrainCommandTest {

    private final PrtsCommandExtension extension = KernelCommandExtension.extension();

    @AfterEach
    void leaveTheCategoryAsItWas() {
        System.clearProperty("arclight.prts.kernel");
    }

    @Test
    void theDrainCommandIsListedParsedAndCompleted() {
        assertTrue(extension.subcommands().contains("drain"));
        assertTrue(extension.subcommands().contains("reset"));
        assertTrue(extension.subcommands().contains("selftest"));
        assertTrue(extension.subcommands().contains("peek"));

        List<String> answer = extension.run(List.of("drain"));
        assertEquals(1, answer.size());
        assertTrue(answer.get(0).contains("drain delivered="), answer.toString());
        assertTrue(answer.get(0).contains("non-terminal"), answer.toString());

        List<String> usage = extension.run(List.of("drain", "extra"));
        assertEquals(1, usage.size());
        assertTrue(usage.get(0).contains("usage: /prts kernel [drain|peek"),
            usage.toString());

        assertTrue(extension.complete(List.of()).contains("drain"));
        assertFalse(extension.complete(List.of("drain")).contains("drain"));
    }

    @Test
    void theDrainCommandAnswersZeroWhileTheCategoryIsOffAndStaysUsable() {
        List<String> first = extension.run(List.of("drain"));
        assertEquals(1, first.size());
        assertTrue(first.get(0).contains("drain delivered=0"), first.toString());
        assertTrue(first.get(0).contains("claims are off"), first.toString());

        List<String> second = extension.run(List.of("drain"));
        assertTrue(second.get(0).contains("drain delivered=0"), second.toString());
    }

    @Test
    void theDrainCommandAnswersZeroWhenNoReportsAreBuffered() {
        System.setProperty("arclight.prts.kernel", "true");
        List<String> answer = extension.run(List.of("drain"));
        assertTrue(answer.get(0).contains("drain delivered=0"), answer.toString());
        assertFalse(answer.get(0).isBlank());
    }
}

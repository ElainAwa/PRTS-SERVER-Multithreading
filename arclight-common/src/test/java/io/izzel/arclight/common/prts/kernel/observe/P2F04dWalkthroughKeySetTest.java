/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.observe;

import io.izzel.arclight.common.prts.kernel.KernelModule;
import io.izzel.arclight.common.prts.kernel.waitpoints.SiteInventory.WaitClass;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Only the two class totals may occupy the injection walkthrough prefix; the helper counts have
 * their own keys so the acceptance command counts two rows, not four. */
class P2F04dWalkthroughKeySetTest {

    private static final Pattern INJECTION_WALKTHROUGH =
        Pattern.compile("^wp\\.injection_walkthrough_[A-Za-z0-9_]+$");

    @BeforeEach
    void enable() {
        System.setProperty("arclight.prts.kernel", "true");
    }

    @AfterEach
    void clear() {
        System.clearProperty("arclight.prts.kernel");
    }

    @Test
    void onlyAAndBUseTheInjectionWalkthroughPrefix() {
        List<String> lines = KernelReadings.export(KernelModule.instance());
        Map<String, String> values = values(lines);
        List<String> prefixed = new ArrayList<>();
        for (String line : lines) {
            String key = key(line);
            if (INJECTION_WALKTHROUGH.matcher(key).matches()) {
                prefixed.add(key);
            }
        }

        assertEquals(List.of("wp.injection_walkthrough_A", "wp.injection_walkthrough_B"),
            prefixed, "the official command counts this prefix and expects exactly two rows");
        assertTrue(values.containsKey("wp.walkthrough.classes"));
        assertTrue(values.containsKey("wp.walkthrough.unclassified"));
        assertEquals(Integer.toString(WaitClass.classified().size()),
            values.get("wp.walkthrough.classes"));
        assertFalse(values.containsKey("wp.injection_walkthrough_classes"));
        assertFalse(values.containsKey("wp.injection_walkthrough_unclassified"));
    }

    private static Map<String, String> values(List<String> lines) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String line : lines) {
            int split = line.indexOf('=');
            if (split > 0) {
                values.put(line.substring(0, split), line.substring(split + 1));
            }
        }
        return values;
    }

    private static String key(String line) {
        int split = line.indexOf('=');
        return split > 0 ? line.substring(0, split) : line;
    }
}

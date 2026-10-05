/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.observe;

import io.izzel.arclight.common.prts.PrtsMixinPlugin;
import io.izzel.arclight.common.prts.kernel.KernelModule;
import io.izzel.arclight.common.prts.support.PrtsSeams;
import io.izzel.arclight.common.prts.support.PrtsWorldWriteTaps;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The seam switch and the kernel switch, and what the readout says when they disagree.
 *
 * <p>The call-site seams of the kernel are carried by the correctness-fixes mixins, so a server that
 * turns that category off while turning the kernel on has an enabled kernel whose call sites never
 * reach it. The watcher can still be installed and the kernel still count itself active - that is the
 * false green. These tests drive the real mixin plugin decision and read the state back: the seam is
 * reported unreachable, the kernel reports that it must not be judged, and the status names the gap.
 * With both categories on, the same seam is reachable and the kernel is judgeable again.</p>
 */
class KernelSeamStatusTest {

    private static final String LEVEL_MIXIN =
        "io.izzel.arclight.common.prts.fixes.PrtsLevelStructureGrowCaptureMixin";

    @AfterEach
    void clear() {
        PrtsSeams.clearRecords();
        System.clearProperty("arclight.prts.fixes");
        System.clearProperty("arclight.prts.kernel");
        PrtsWorldWriteTaps.install(null);
    }

    @Test
    void aKernelWithTheFixesCategoryOffCannotBeJudged() {
        System.setProperty("arclight.prts.kernel", "true");
        System.setProperty("arclight.prts.fixes", "false");
        PrtsMixinPlugin plugin = new PrtsMixinPlugin();
        plugin.onLoad("io.izzel.arclight.common.prts.fixes");

        assertFalse(plugin.shouldApplyMixin("net.minecraft.world.level.Level", LEVEL_MIXIN),
            "the category switch refuses the mixin that carries the seam");
        KernelModule module = KernelModule.instance();
        PrtsWorldWriteTaps.install(module.guard());
        try {
            List<String> export = KernelReadings.export(module);
            assertTrue(export.contains("kernel.category_enabled=true"));
            assertTrue(export.contains("kernel.write_path_tap_installed=1"),
                "the watcher is installed, which is what used to look green");
            assertTrue(export.contains("kernel.seam.write_path_level.category_enabled=0"));
            assertTrue(export.contains("kernel.seam.write_path_level.decided=0"));
            assertTrue(export.contains("kernel.seam.write_path_level.applied=0"));
            assertTrue(export.contains("kernel.seam.write_path_level.reachable=0"));
            assertTrue(export.contains("kernel.seam_reachable=0"));
            assertEquals(21, PrtsSeams.kernelSeams().size(),
                "two write path seams, the twelve wait site mixins, the two mailbox seams, the two"
                    + " self cost seams and the three attribution seams");
            assertTrue(export.contains("kernel.seam_gap=" + PrtsSeams.kernelSeams().size()));
            assertTrue(export.contains("kernel.judgeable=0"));

            List<String> status = KernelStatusLines.status(module);
            assertTrue(status.stream().anyMatch(line -> line.contains("judgeable=0")),
                "the status states the kernel cannot be judged");
            assertTrue(status.stream().anyMatch(line ->
                    line.contains("seam gap") && line.contains("write_path_level")
                        && line.contains("fixes")),
                "the status names the seam and the category that gates it");
        } finally {
            PrtsWorldWriteTaps.install(null);
        }
    }

    @Test
    void aKernelWithItsSeamsInPlaceIsJudgeable() {
        System.setProperty("arclight.prts.kernel", "true");
        System.setProperty("arclight.prts.fixes", "true");
        // The positive answer is recorded the way the plugin records it when it applies the mixin.
        // Driving the plugin itself here would load the platform bootstrap, which a unit test has no
        // version for; the refusing answer above is driven through the plugin because that path stops
        // before the platform is touched.
        PrtsSeams.noteDecision(LEVEL_MIXIN, true);
        PrtsSeams.noteApplied(LEVEL_MIXIN);
        List<String> export = KernelReadings.export(KernelModule.instance());

        assertTrue(export.contains("kernel.seam.write_path_level.decided=1"));
        assertTrue(export.contains("kernel.seam.write_path_level.applied=1"));
        assertTrue(export.contains("kernel.seam.write_path_level.reachable=1"));
        assertTrue(export.contains("kernel.seam_gap=0"));
        assertTrue(export.contains("kernel.judgeable=1"));
    }

    @Test
    void everyDeclaredSeamNamesAMixinAndATarget() {
        for (PrtsSeams.Seam seam : PrtsSeams.kernelSeams()) {
            assertFalse(seam.seamId().isEmpty());
            assertTrue(seam.mixinClass().startsWith("io.izzel.arclight.common.prts."));
            assertFalse(seam.targetClass().isEmpty());
            assertFalse(seam.category().isEmpty());
        }
        assertTrue(PrtsSeams.kernelSeams().size() >= 14);
    }
}

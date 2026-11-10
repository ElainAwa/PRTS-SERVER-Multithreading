/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.observe;

import io.izzel.arclight.common.prts.kernel.KernelModule;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class I8DualExitAutoViewTest {

    @Test
    void autoViewComposesFixedSectionsFromInstance() {
        KernelReadings.DualExitView view = KernelReadings.dualExitView(KernelModule.instance());
        assertNotNull(view);
        assertTrue(view.clean(), "control and criteria sections must not share keys");
    }
}

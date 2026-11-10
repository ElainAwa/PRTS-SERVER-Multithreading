/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.observe;

import io.izzel.arclight.common.prts.kernel.generic.JobMetering;
import io.izzel.arclight.common.prts.kernel.shares.ShareClass;
import io.izzel.arclight.common.prts.kernel.shares.SharePlanner;
import io.izzel.arclight.common.prts.kernel.shares.ShareTable;
import io.izzel.arclight.common.prts.kernel.sites.WorldSetReadout;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class I8DualExitSeamTest {

    /** Test-only seam: two views with no shared map instance. */
    static final class DualExits {
        final Map<String, Object> control = new LinkedHashMap<>();
        final Map<String, Object> criteria = new LinkedHashMap<>();

        void control(String key, Object value) { control.put(key, value); }
        void criteria(String key, Object value) { criteria.put(key, value); }

        boolean clean() {
            return control.keySet().stream().noneMatch(criteria::containsKey);
        }
    }

    @Test
    void controlAndCriteriaViewsDoNotOverwriteEachOtherAndSelfCheckSeesCollision() {
        DualExits exits = new DualExits();
        exits.control("budget.share_rows", 5);
        exits.criteria("budget.share_rows", 5);
        assertFalse(exits.clean(), "same key in both exits must be visible failure");
        DualExits separate = new DualExits();
        separate.control("control.only", 1);
        separate.criteria("criteria.only", 1);
        assertTrue(separate.clean());
        assertEquals(1, separate.control.get("control.only"));
        assertEquals(1, separate.criteria.get("criteria.only"));
    }

    @Test
    void regionRowsAndDefaultOffAreObservable() {
        ShareTable table = new SharePlanner().plan(List.of("w1", "w2"), 1L, Map.of());
        assertEquals(ShareClass.rowCount(), table.rowsOf("w1").size());
        assertEquals(ShareClass.rowCount(), table.rowsOf("w2").size());
        assertTrue(table.rowsOf("w1").stream()
            .anyMatch(row -> row.shareClass() == ShareClass.ENTITY));

        JobMetering metering = new JobMetering();
        assertTrue(metering.usedMs().isEmpty(), "default off has no charge");
    }
}

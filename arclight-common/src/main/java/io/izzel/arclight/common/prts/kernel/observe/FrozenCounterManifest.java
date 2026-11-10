/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.observe;

import java.util.List;

/** The internal manifest of the frozen counters: key, zero semantics, window and dimension. */
final class FrozenCounterManifest {

    record Counter(String key, String zero, String window, String dimension, boolean literal) {
    }

    static List<Counter> counters() {
        return List.of(
            new Counter("wait.cap_defined", "0=no bound", "tick", "global", true),
            new Counter("degrade.level_rows", "0 rows", "tick", "global", true),
            new Counter("budget.reserve_row", "0 ms", "tick", "global", true),
            new Counter("self.timer_rows", "0 rows", "tick", "class", true),
            new Counter("wait.rows", "0 rows", "tick", "wait-site", false),
            new Counter("budget.share_rows", "0 asserted rows", "tick", "world x class", false));
    }
}

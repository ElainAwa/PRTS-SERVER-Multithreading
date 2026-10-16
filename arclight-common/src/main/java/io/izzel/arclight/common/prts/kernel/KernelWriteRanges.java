/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel;

import io.izzel.arclight.common.prts.kernel.jobs.JobDeclaration;
import io.izzel.arclight.common.prts.kernel.jobs.JobGraph;
import io.izzel.arclight.common.prts.kernel.sites.WriteControlledSlots;
import io.izzel.arclight.common.prts.kernel.sites.WriteControlledSlots.CellKind;

import java.util.function.Function;

/** Reads the spatial range a frozen job declared: the granularity it named is the one it is claimed
 * at and the cells are the ones it named, and a declaration that named no range is answered with
 * the absent range. A fourth granularity would be a compile error here, not a runtime answer. */
public final class KernelWriteRanges {

    private KernelWriteRanges() {
    }

    /** The reader the claim point is wired to: one range per frozen node, or the absent range. */
    public static Function<JobGraph.Node, WriteControlledSlots.WriteSpace> reader() {
        return KernelWriteRanges::fold;
    }

    private static WriteControlledSlots.WriteSpace fold(JobGraph.Node node) {
        JobDeclaration.WriteRange range = node.range();
        if (range == null || range.granularity() == null) {
            return WriteControlledSlots.WriteSpace.NONE;
        }
        CellKind kind = switch (range.granularity()) {
            case CHUNK -> CellKind.CHUNK;
            case SECTION -> CellKind.SECTION;
            case BLOCK -> CellKind.BLOCK;
        };
        return WriteControlledSlots.WriteSpace.of(kind, range.cells());
    }
}

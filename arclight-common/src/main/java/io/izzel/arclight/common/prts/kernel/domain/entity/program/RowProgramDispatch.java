/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.program;

import io.izzel.arclight.common.prts.kernel.domain.entity.EntityCandidateView;

/**
 * The per-row program dispatch: every row of one frozen batch declares its own program kind from
 * the row's own host step, so one batch may mix programs and no first row speaks for the batch. The
 * mapping is the row's host step hint, not an entity type lookup: the worker holds no entity and
 * must not ask a world which type a row has.
 */
public final class RowProgramDispatch {

    /** The frozen host step hint of a plain row. */
    public static final long STEP_PLAIN = 0L;
    /** The frozen host step hint of a living row without physics. */
    public static final long STEP_LIVING_NO_PHYSICS = 1L;
    /** The frozen host step hint of an item row in air. */
    public static final long STEP_ITEM_AIR = 2L;

    private RowProgramDispatch() {
    }

    /** The program kind one row declares from its host step hint. */
    public static EntityUpdateProgram.ProgramKind kindOf(long hostStep) {
        if (hostStep == STEP_LIVING_NO_PHYSICS) {
            return EntityUpdateProgram.ProgramKind.LIVING_TICK;
        }
        if (hostStep == STEP_ITEM_AIR) {
            return EntityUpdateProgram.ProgramKind.BASE_TICK;
        }
        return EntityUpdateProgram.ProgramKind.BASE_TICK;
    }

    /** One row's program, built from the frozen row and its own host step. */
    public static EntityUpdateProgram forRow(EntityCandidateView view, int row,
                                             EntityUpdateProgram.Input base) {
        EntityUpdateProgram.ProgramKind kind = kindOf(view.flags(row));
        return EntityUpdateProgram.dormant(kind, base);
    }
}

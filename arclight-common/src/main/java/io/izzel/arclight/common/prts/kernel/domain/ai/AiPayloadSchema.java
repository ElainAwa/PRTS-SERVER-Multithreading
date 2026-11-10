/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.ai;

/** Fixed internal layout of one AI payload row inside the P0 arena scratch columns. */
final class AiPayloadSchema {

    static final int POS_X = 0;
    static final int POS_Y = 1;
    static final int POS_Z = 2;
    static final int ENTITY_ID = 3;   // payload-only column, not entity kinematics
    static final int VERSION = 4;     // payload-only column
    static final int VISIBLE = 5;     // payload-only flag
    static final int KIND = 9;        // payload-only kind flag

    private AiPayloadSchema() {
    }
}

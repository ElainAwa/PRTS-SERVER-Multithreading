/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.auth;

/** Operation of a write attempt: a read is classified but takes no write right. */
public enum WriteOp {

    /** A read; it passes and never counts into the write accounting closure. */
    READ,
    /** A write; it must leave the decision point with exactly one disposition. */
    WRITE
}

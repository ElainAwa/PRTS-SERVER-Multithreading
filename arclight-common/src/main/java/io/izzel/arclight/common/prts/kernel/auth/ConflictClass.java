/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.auth;

/** How a conflict is handled; every class maps onto one of the three dispositions. */
public enum ConflictClass {

    /** Queue: the write becomes an intent and keeps the frozen order. */
    QUEUE,
    /** Degrade: the site or the domain runs serially for this round. */
    DEGRADE,
    /** Reject: the write does not happen. */
    REJECT,
    /** Skip: the beat is dropped and re-planned for the next tick. */
    SKIP
}

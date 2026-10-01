/* SPDX-License-Identifier: GPL-3.0-or-later */
/*
 * The hooks that report the waits the world side produces to the wait observation seam.
 */
/**
 * Reports the waits the real call sites produce.
 *
 * <p>Each class here hooks one part of the world that can wait for a chunk it does not have yet and
 * reports the duration of the hooked call to the wait observation seam. The hooks belong to the
 * shared correctness category rather than to a category of their own, because they are the same kind
 * of seam the write path hooks are: they ask one question, record the answer and change nothing.</p>
 *
 * <p>The package is separate from its neighbours because the call sites are one concern - they are
 * the world side of a reading - and because grouping them keeps the list of hooked methods in one
 * place. The class a hook targets is always named after it, so a reader can line a hook up with the
 * game class it edits without a lookup.</p>
 *
 * <p>Nothing here holds state: the observations live in the seam and in the kernel, so a hook can be
 * removed without leaving anything behind.</p>
 */
package io.izzel.arclight.common.prts.fixes.waitpoints;

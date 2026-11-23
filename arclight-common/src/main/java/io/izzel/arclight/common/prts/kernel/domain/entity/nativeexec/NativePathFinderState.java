/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.nativeexec;

/** The one finder setting a staged search has to copy exactly: a different visited-node cap turns
 * the same request into a different search, so it is part of the reuse key. */
public interface NativePathFinderState {

    int prts$maxVisitedNodes();
}

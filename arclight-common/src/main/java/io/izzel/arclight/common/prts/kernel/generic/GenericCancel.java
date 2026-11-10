/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.generic;

/** Domain-neutral cooperative cancellation token for a generic job. */
public interface GenericCancel {

    boolean cancelled();

    boolean cancel();
}

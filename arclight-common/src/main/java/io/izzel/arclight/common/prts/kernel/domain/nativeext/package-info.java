/* SPDX-License-Identifier: GPL-3.0-or-later */
/** The external-native contract: an engine has one owner and one ABI identity, its CPU tickets come
 * from the shared bounded quota, a substep's pending obligations have a hard cap, a synchronous
 * JNI-to-Java callback passes a permission gate, and a stateful step that overlapped a timeout stays
 * quarantined until its native call really returned instead of being freed or replayed. */
package io.izzel.arclight.common.prts.kernel.domain.nativeext;

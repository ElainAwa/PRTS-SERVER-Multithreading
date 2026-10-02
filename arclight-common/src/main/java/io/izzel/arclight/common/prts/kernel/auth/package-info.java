/* SPDX-License-Identifier: GPL-3.0-or-later */
/**
 * The write decision point: which holder may write which world domain, and what happens when it may
 * not. Owns the owner token and its registry, the write attempt and its verdicts, and the accounting
 * closure. Design reference: the write-right section.
 */
package io.izzel.arclight.common.prts.kernel.auth;

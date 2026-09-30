/* SPDX-License-Identifier: GPL-3.0-or-later */
/**
 * The kernel settings: one accessor per key declared in the kernel configuration file.
 *
 * <p>The declaration and the defaults live in the configuration layer; this package only names the
 * keys and reads them at the moment a piece runs, so a reload applies without a restart and a
 * default is never repeated. Every read falls back to the declared default when the configuration
 * cannot answer, which keeps a broken file from turning behaviour on.</p>
 */
package io.izzel.arclight.common.prts.kernel.config;

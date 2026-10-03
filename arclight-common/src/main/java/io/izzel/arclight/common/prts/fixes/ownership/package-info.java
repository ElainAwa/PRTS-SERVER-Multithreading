/* SPDX-License-Identifier: GPL-3.0-or-later */
/*
 * Accessors for the ownership fixture. The whole-tick model reads and writes a few entity fields
 * that vanilla keeps protected or private; these interfaces add the getters and setters only, they
 * change no behaviour of their own and are inert while the fixture is off.
 */
package io.izzel.arclight.common.prts.fixes.ownership;

/* SPDX-License-Identifier: GPL-3.0-or-later */
/**
 * PRTS category: optional ServerCore layer.
 *
 * <p>Opt-in (disabled by default) and free of kernel coupling: it must be possible to delete this
 * subtree, together with its mixin configuration, without touching anything the kernel owns.</p>
 *
 * <p>Scope is one member only: the reliable chunk-save journal, that is write-ahead recovery plus
 * chunked flush, together with the configuration keys that journal needs. Every other asset of the
 * original set is out of scope here.</p>
 *
 * <p>The chunk pipeline, entity tracking and networking seams are owned by the kernel. They must
 * never be implemented, injected into or configured from this subtree, so this layer can be
 * dropped as a whole without stranding kernel work.</p>
 *
 * <p>An external ServerCore installation replaces this layer; the two must not be enabled on the
 * same server.</p>
 *
 * <p>The implementation lives one package up ({@code prts.optional.journal}): Mixin refuses to load
 * any class inside the package tree a mixin configuration owns, so a class the handlers call cannot
 * sit under this package.</p>
 *
 * <p>The member it ships is the recovery journal: {@code ChunkJournal} writes the unsaved
 * chunks of a cycle ahead of the region files and replays them after an unclean exit, and
 * {@link io.izzel.arclight.common.prts.optional.servercore.ChunkJournalMixin_Recovery} drives it
 * from the tick loop, the world lifecycle and the shutdown path. Both are opt-in: the category is
 * off by default and the journal switch inside it is off as well.</p>
 */
package io.izzel.arclight.common.prts.optional.servercore;

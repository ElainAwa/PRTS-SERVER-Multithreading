/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.diff;

/**
 * What a comparison of the two arms saw.
 *
 * <p>The report keeps the pair count and the equal count - the rate is their quotient - and the
 * first fork down to one entity and one field. A comparison without a located fork is not a
 * verdict: the unattributed count says how many mismatches the descent could not place.</p>
 *
 * @param tickPairs         ticks both arms were hashed for
 * @param equal             ticks whose two hashes were equal
 * @param rate              equal divided by pairs, or zero when there are no pairs
 * @param algorithmId       the algorithm both arms used
 * @param firstForkTick     the first tick the arms disagreed on, or -1
 * @param firstForkWorld    the world of the first fork, or empty
 * @param firstForkRegion   the region of the first fork, or empty
 * @param firstForkBatch    the batch of the first fork, or -1
 * @param firstForkEntitySeq the entity of the first fork, or -1
 * @param firstForkField    the field the first fork was seen in, or empty
 * @param unattributed      mismatches the descent could not place
 * @param fieldCount        distinct fields that forked
 * @param attributedSites   call sites a fork was attributed to
 * @param attributedWorlds  worlds a fork was attributed to
 */
public record DiffReport(long tickPairs, long equal, double rate, String algorithmId,
                         long firstForkTick, String firstForkWorld, String firstForkRegion,
                         long firstForkBatch, long firstForkEntitySeq, String firstForkField,
                         long unattributed, long fieldCount, long attributedSites,
                         long attributedWorlds) {

    /** @return a report of a comparison that saw nothing */
    public static DiffReport empty() {
        return new DiffReport(0L, 0L, 0.0, "", -1L, "", "", -1L, -1L, "", 0L, 0L, 0L, 0L);
    }

    /**
     * Renders the first fork as one line.
     *
     * @return the location line, in the fixed six-level order
     */
    public String forkLine() {
        if (firstForkTick < 0) {
            return "first_fork=none pairs=" + tickPairs + " equal=" + equal;
        }
        return "first_fork tick=" + firstForkTick + " world=" + firstForkWorld + " region="
            + firstForkRegion + " batch=" + firstForkBatch + " entity_seq=" + firstForkEntitySeq
            + " field=" + firstForkField + " unattributed=" + unattributed + " fields="
            + fieldCount;
    }
}

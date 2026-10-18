/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.commit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The one source of the instance numbers a freeze seals: each declaration carrying its artefact and
 * naming a live world gets a positive number in plan order, the counter only moves forward, and no
 * number is handed out twice. A number is not an identity across runs; the mapping the declaration
 * sealed identifies the line, and the freeze point hands this source out to no one. */
public final class DeclarationInstanceAllocator {

    private long minted;

    /** Takes the next number of the same counter the declarations are numbered from; a taken number
     * is spent whatever happens next. */
    public synchronized long mintOne() {
        return ++minted;
    }

    /** Copies the declarations with the next numbers of this source, joined to the places the plan
     * gave them. A declaration the plan gave no place, one that carries no artefact, or one whose
     * world generation is not live is copied unchanged: nothing to number means nothing is spent. */
    public List<FrozenDeclarations.Candidate> mint(List<FrozenDeclarations.Candidate> candidates,
                                                   List<FrozenDeclarations.Assignment> places) {
        List<FrozenDeclarations.Candidate> declared = candidates == null ? List.of() : candidates;
        if (declared.isEmpty()) {
            return List.of();
        }
        Map<FrozenDeclarations.NodeKey, Integer> at = new LinkedHashMap<>();
        for (int index = 0; index < declared.size(); index++) {
            FrozenDeclarations.Candidate candidate = declared.get(index);
            if (candidate != null) {
                at.putIfAbsent(nodeOf(candidate), index);
            }
        }
        long[] numbers = new long[declared.size()];
        List<FrozenDeclarations.Assignment> ordered = places == null ? List.of() : places;
        for (FrozenDeclarations.Assignment place : ordered) {
            if (place == null) {
                continue;
            }
            Integer index = at.get(new FrozenDeclarations.NodeKey(place.worldId(), place.domainId(),
                place.nodeKey()));
            if (index == null) {
                continue;
            }
            FrozenDeclarations.Candidate candidate = declared.get(index);
            if (numbers[index] != 0L || !candidate.artefactDeclared()
                || !CommitLog.liveWorldEpoch(candidate.worldEpoch())) {
                continue;
            }
            numbers[index] = ++minted;
        }
        List<FrozenDeclarations.Candidate> numbered = new ArrayList<>(declared.size());
        for (int index = 0; index < declared.size(); index++) {
            FrozenDeclarations.Candidate candidate = declared.get(index);
            long number = candidate == null ? 0L : numbers[index];
            numbered.add(number == 0L ? candidate : withInstance(candidate, number));
        }
        return numbered;
    }

    private static FrozenDeclarations.Candidate withInstance(FrozenDeclarations.Candidate candidate,
                                                             long number) {
        return new FrozenDeclarations.Candidate(candidate.worldId(), candidate.domainId(),
            candidate.nodeKey(), candidate.chunk(), candidate.targetStatus(), number,
            candidate.worldEpoch());
    }

    private static FrozenDeclarations.NodeKey nodeOf(FrozenDeclarations.Candidate candidate) {
        return new FrozenDeclarations.NodeKey(candidate.worldId(), candidate.domainId(),
            candidate.nodeKey());
    }
}

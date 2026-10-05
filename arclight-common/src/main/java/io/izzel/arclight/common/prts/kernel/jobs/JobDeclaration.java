/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.jobs;

import io.izzel.arclight.common.prts.kernel.shares.ShareClass;

import java.util.ArrayList;
import java.util.List;

/** One job as a domain declares it, before anything is frozen: its identity, the read and write
 * domains it may touch, its true predecessors, its priority, its affinity group, the scope a
 * cancellation of it reaches, the bound of its batch and the share class it is metered under. */
public record JobDeclaration(String key, long handle, String worldId, String domainId, int level,
                             List<String> predecessors, int priority, String affinityKey,
                             String cancelScope, List<DomainRef> readSet, List<DomainRef> writeSet,
                             ShareClass shareClass, SiteClass siteClass, int batchKind,
                             int batchBound) {

    /** One read or write domain: the world, the domain inside it and the ownership level. The world is
     * part of the identity, so a set that spans two worlds is visible as such before anything is
     * frozen. */
    public record DomainRef(String worldId, String domainId, int level) {

        public DomainRef {
            if (worldId == null) {
                throw new IllegalArgumentException("a domain reference needs a world");
            }
            if (domainId == null || domainId.isEmpty()) {
                throw new IllegalArgumentException("a domain reference needs a domain");
            }
            if (level < 0) {
                throw new IllegalArgumentException("a domain reference needs an ownership level");
            }
        }

        public String key() {
            return worldId + "/" + domainId + "@" + level;
        }
    }

    /** How a site of this job is known to the planning period. An unknown site may not be run in
     * parallel: it is forced onto the conservative mode and counted, never silently defaulted. */
    public enum SiteClass {
        PARALLEL,
        SERIAL,
        UNKNOWN
    }

    public JobDeclaration {
        if (key == null || key.isEmpty()) {
            throw new IllegalArgumentException("a job needs a key");
        }
        if (worldId == null || worldId.isEmpty()) {
            throw new IllegalArgumentException("a job needs a world");
        }
        if (domainId == null || domainId.isEmpty()) {
            throw new IllegalArgumentException("a job needs a domain");
        }
        if (level < 0) {
            throw new IllegalArgumentException("a job needs an ownership level");
        }
        if (affinityKey == null || affinityKey.isEmpty()) {
            throw new IllegalArgumentException("a job needs an affinity group");
        }
        if (cancelScope == null || cancelScope.isEmpty()) {
            throw new IllegalArgumentException("a job needs a cancellation scope");
        }
        if (shareClass == null || siteClass == null) {
            throw new IllegalArgumentException("a job needs a share class and a site class");
        }
        predecessors = List.copyOf(predecessors == null ? List.of() : predecessors);
        readSet = List.copyOf(readSet == null ? List.of() : readSet);
        writeSet = List.copyOf(writeSet == null ? List.of() : writeSet);
    }

    /** The affinity of one job: two jobs with the same affinity keep their relative order. */
    public String affinity() {
        return worldId + "/" + domainId + "/" + affinityKey;
    }

    /** The write domains this job declares, without the level and without duplicates. */
    public List<String> writeDomains() {
        List<String> domains = new ArrayList<>();
        for (DomainRef ref : writeSet) {
            if (!domains.contains(ref.domainId())) {
                domains.add(ref.domainId());
            }
        }
        return domains;
    }

    /** Whether the declared write set spans more than one domain: such a job is split into an
     * in-domain job and an intent, and the planning period is where that split is decided. */
    public boolean crossesDomains() {
        return writeDomains().size() > 1;
    }

    /** Whether any declared reference belongs to another world than the job itself. A cross-world
     * write is never split silently: the two worlds have no common order, so it is refused. */
    public boolean crossesWorlds() {
        for (DomainRef ref : writeSet) {
            if (!ref.worldId().equals(worldId)) {
                return true;
            }
        }
        return false;
    }
}

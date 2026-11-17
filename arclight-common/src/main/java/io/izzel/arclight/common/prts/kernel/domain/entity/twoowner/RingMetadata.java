/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.twoowner;

import io.izzel.arclight.common.prts.kernel.domain.entity.OwnerSite;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The global metadata of the owner lanes: every ring key has exactly one writer site, a second
 * writer is refused, and the published view is an immutable copy taken under the lock. Nothing
 * outside the lock ever writes a shared map, so a reader publishes a whole consistent view instead
 * of racing a half-updated one. The metadata runs no host code and writes no world object.
 */
public final class RingMetadata {

    private final Map<RingKey, OwnerSite> writers = new LinkedHashMap<>();
    private final Map<RingKey, String> values = new LinkedHashMap<>();

    /** Claims the single writer of one ring key; a second site is refused. */
    public synchronized boolean claim(RingKey key, OwnerSite site) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(site, "site");
        OwnerSite installed = writers.get(key);
        if (installed == null) {
            writers.put(key, site);
            return true;
        }
        return installed.equals(site);
    }

    /** Writes one ring value; only the claimed writer may write it. */
    public synchronized boolean write(RingKey key, String value, OwnerSite site) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(site, "site");
        OwnerSite writer = writers.get(key);
        if (writer == null) {
            return false;
        }
        if (!writer.equals(site)) {
            return false;
        }
        values.put(key, value);
        return true;
    }

    /** The writer of one ring key, or null when nobody claimed it. */
    public synchronized OwnerSite writerOf(RingKey key) {
        return writers.get(key);
    }

    /** The immutable published view of every ring value. */
    public synchronized Map<RingKey, String> published() {
        return Map.copyOf(values);
    }

    public synchronized int claimedKeys() {
        return writers.size();
    }

    public synchronized int publishedKeys() {
        return values.size();
    }
}

/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.publish;

import io.izzel.arclight.common.prts.kernel.domain.entity.migration.CompositeWorldIdentity;

import java.util.Objects;

/**
 * The read wrapper the tracker path asks: while the dormant internal property is off, the tracker
 * keeps its host live read; when it is on, the wrapper returns the store's committed snapshot and
 * never falls back to the live row for a half-written object. The wrapper runs no host code and
 * creates no network pool; making the tracker overwrite an anchor stays a host decision.
 */
public final class TrackerReadView {

    /** The internal dormant property; not a configuration key and off in every shipped build. */
    public static final String PROPERTY = "arclight.prts.publishTracker";

    /** One tracker read answer: a committed snapshot or the host live fallback. */
    public record View(boolean published, PublishedSnapshot snapshot) {

        public View {
            if (published && snapshot == null) {
                throw new IllegalArgumentException("a published view carries its snapshot");
            }
        }
    }

    private final PublishStore store;

    public TrackerReadView(PublishStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    /** Whether the dormant property routes the tracker through the committed store. */
    public static boolean enabled() {
        return Boolean.getBoolean(PROPERTY);
    }

    /** Reads the committed state of one object; an absent or stale state leaves the host fallback. */
    public View read(CompositeWorldIdentity identity, long readerGeneration) {
        PublishStore.Read read = store.readCommitted(identity, readerGeneration);
        return read.committed() ? new View(true, read.snapshot()) : new View(false, null);
    }
}

/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.degrade;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/** Registers the A-ring materializer of each wait site. A site without a registration counts an
 * overrun and reports LIMITED rather than pretending an action ran. */
public final class ForcedMaterializerRegistry {

    private final Map<String, ForcedMaterializer> materializers = new ConcurrentHashMap<>();
    private final LongAdder overruns = new LongAdder();
    private final LongAdder executed = new LongAdder();
    private final LongAdder limited = new LongAdder();
    private volatile boolean enabled;

    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public boolean register(String waitSite, ForcedMaterializer materializer) {
        if (waitSite == null || waitSite.isEmpty() || materializer == null) {
            return false;
        }
        return materializers.putIfAbsent(waitSite, materializer) == null;
    }

    /** Called when a wait site exceeds its bound; off/limited paths only count. */
    public boolean overrun(String waitSite) {
        overruns.increment();
        if (!enabled) return false;
        ForcedMaterializer materializer = materializers.get(waitSite);
        if (materializer == null) {
            limited.increment();
            return false;
        }
        boolean ran = materializer.materialize(waitSite);
        if (ran) executed.increment();
        return ran;
    }

    public long overruns() { return overruns.sum(); }
    public long executed() { return executed.sum(); }
    public long limited() { return limited.sum(); }
    public int registered() { return materializers.size(); }
}

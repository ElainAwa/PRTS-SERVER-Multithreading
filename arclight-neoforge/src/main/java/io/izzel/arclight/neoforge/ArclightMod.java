package io.izzel.arclight.neoforge;

import io.izzel.arclight.api.Arclight;
import io.izzel.arclight.common.mod.server.ArclightServer;
import io.izzel.arclight.common.prts.PrtsSwitches;
import io.izzel.arclight.neoforge.mod.NeoForgeArclightServer;
import io.izzel.arclight.neoforge.mod.event.ArclightEventDispatcherRegistry;
import io.izzel.arclight.neoforge.prts.fixes.PrtsCommandRegistration;
import io.izzel.arclight.neoforge.prts.kernel.PrtsKernelEvents;
import io.izzel.arclight.neoforge.prts.optional.PrtsJournalEvents;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.OutputStream;
import java.io.PrintStream;

@Mod("arclight")
public class ArclightMod {

    public ArclightMod() {
        ArclightServer.LOGGER.info("mod-load");
        Arclight.setServer(new NeoForgeArclightServer());
        System.setOut(new LoggingPrintStream("STDOUT", System.out, Level.INFO));
        System.setErr(new LoggingPrintStream("STDERR", System.err, Level.ERROR));
        ArclightEventDispatcherRegistry.registerAllEventDispatchers();
        NeoForge.EVENT_BUS.addListener(PrtsCommandRegistration::onRegisterCommands);
        // The optional layer is opt-in: with the category off its listeners are never subscribed,
        // and with it on they drive the journal from platform events instead of kernel seams.
        if (PrtsSwitches.enabled(PrtsSwitches.OPTIONAL_SERVERCORE)) {
            PrtsJournalEvents.register();
        }
        // The kernel scaffolding is off by default as well: with the category off no listener is
        // subscribed, and with it on the four pieces are driven from the platform tick event
        // without touching a world write path.
        if (PrtsSwitches.enabled(PrtsSwitches.KERNEL)) {
            PrtsKernelEvents.register();
        }
    }

    private static class LoggingPrintStream extends PrintStream {

        private final Logger logger;
        private final Level level;

        public LoggingPrintStream(String name, @NotNull OutputStream out, Level level) {
            super(out);
            this.logger = LogManager.getLogger(name);
            this.level = level;
        }

        @Override
        public void println(@Nullable String x) {
            logger.log(level, x);
        }

        @Override
        public void println(@Nullable Object x) {
            logger.log(level, String.valueOf(x));
        }
    }
}

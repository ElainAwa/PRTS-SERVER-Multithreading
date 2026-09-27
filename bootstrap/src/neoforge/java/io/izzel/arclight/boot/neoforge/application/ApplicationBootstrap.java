package io.izzel.arclight.boot.neoforge.application;

import io.izzel.arclight.api.ArclightPlatform;
import io.izzel.arclight.api.EnumHelper;
import io.izzel.arclight.api.Unsafe;
import io.izzel.arclight.boot.AbstractBootstrap;
import io.izzel.arclight.i18n.ArclightConfig;
import io.izzel.arclight.i18n.ArclightLocale;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;

import java.util.Arrays;
import java.util.ServiceLoader;
import java.util.function.Consumer;

public class ApplicationBootstrap implements Consumer<String[]>, AbstractBootstrap {

    private static final int MIN_DEPRECATED_VERSION = 60;
    private static final int MIN_DEPRECATED_JAVA_VERSION = 16;

    @Override
    @SuppressWarnings("unchecked")
    public void accept(String[] args) {
        System.setProperty("java.util.logging.manager", "org.apache.logging.log4j.jul.LogManager");
        System.setProperty("log4j.jul.LoggerAdapter", "io.izzel.arclight.boot.log.ArclightLoggerAdapter");
        // Extract Arclight's log4j2 config into .arclight/ (hidden) so it does not clutter
        // the server root directory, then point log4j at it.
        extractLog4jConfig();
        String configPath = new java.io.File(".arclight/arclight-log4j2.xml").getAbsolutePath();
        System.setProperty("log4j2.configurationFile", configPath);
        System.setProperty("log4j.configurationFile", configPath);
        // Force-reconfigure log4j with our xml so TerminalConsole + minecraftFormatting
        // (§→ANSI) converter is active BEFORE setupMod() prints the colored banner.
        // Without this, FML may have already initialised the LoggerContext with its own
        // config, and § codes in banner/plugin messages render as plain white text.
        reconfigureLogging();
        ArclightLocale.info("i18n.using-language", ArclightConfig.spec().getLocale().getCurrent(), ArclightConfig.spec().getLocale().getFallback());
        try {
            int javaVersion = (int) Float.parseFloat(System.getProperty("java.class.version"));
            if (javaVersion < MIN_DEPRECATED_VERSION) {
                ArclightLocale.error("java.deprecated", System.getProperty("java.version"), MIN_DEPRECATED_JAVA_VERSION);
                Thread.sleep(3000);
            }
            Unsafe.ensureClassInitialized(EnumHelper.class);
        } catch (Throwable t) {
            System.err.println("Your Java is not compatible with Arclight.");
            t.printStackTrace();
            return;
        }
        try {
            this.setupMod(ArclightPlatform.NEOFORGE);
            this.dirtyHacks();
            int targetIndex = Arrays.asList(args).indexOf("--launchTarget");
            if (targetIndex >= 0 && targetIndex < args.length - 1) {
                args[targetIndex + 1] = "arclightserver";
            }
            ServiceLoader.load(getClass().getModule().getLayer(), Consumer.class).stream()
                    .filter(it -> !it.type().getName().contains("arclight"))
                    .findFirst().orElseThrow().get().accept(args);
        } catch (Exception e) {
            e.printStackTrace();
            System.err.println("Fail to launch Arclight.");
        }
    }

    private void extractLog4jConfig() {
        try {
            var resource = getClass().getClassLoader().getResourceAsStream("arclight-log4j2.xml");
            if (resource == null) {
                return;
            }
            var dir = java.nio.file.Path.of(".arclight");
            java.nio.file.Files.createDirectories(dir);
            var target = dir.resolve("arclight-log4j2.xml");
            // Always overwrite: the embedded config is the single source of truth for a self-contained
            // core, so a freshly built core reliably applies its own logging (incl. the status level)
            // instead of silently reusing a stale extracted copy from a previous deploy.
            try (var in = resource) {
                java.nio.file.Files.copy(in, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Throwable t) {
            // Non-fatal: if extraction fails, log4j falls back to FML's default format.
        }
    }

    /**
     * Force log4j2 to load our configuration (with TerminalConsole + minecraftFormatting)
     * so that § color codes in banner text and Bukkit plugin messages are converted to
     * ANSI escape sequences.  Mirrors 1.20.1's LoggingConfigurator.apply() → reconfigure().
     */
    private void reconfigureLogging() {
        try {
            java.io.File configFile = new java.io.File(".arclight/arclight-log4j2.xml");
            if (!configFile.exists()) {
                return;
            }
            LoggerContext context = (LoggerContext) LogManager.getContext(false);
            context.setConfigLocation(configFile.toURI());
            context.reconfigure();
        } catch (Throwable t) {
            // Non-fatal: if reconfigure fails, fall through with whatever config FML left us.
            // %style/%highlight (cyan time, green INFO) still works via AnsiRenderer;
            // only minecraftFormatting (§→ANSI in message body) will be inactive.
        }
    }
}

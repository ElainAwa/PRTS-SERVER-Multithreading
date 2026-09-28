package io.izzel.arclight.server;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Properties;

public class Launcher {

    private static final int MIN_CLASS_VERSION = 65;
    private static final int MIN_JAVA_VERSION = 21;

    private static final int MAX_CLASS_VERSION = 66;
    private static final int MAX_JAVA_VERSION = 22;

    private static final String EULA_URL = "https://aka.ms/MinecraftEULA";
    private static final String EULA_FILE = "eula.txt";

    /** Text used to ask the console encoding whether it can carry non-ASCII characters at all. */
    private static final String NON_ASCII_SAMPLE = "\u4e2d\u6587";

    public static void main(String[] args) throws Throwable {
        preserveLocalizedOutput();
        int javaVersion = (int) Float.parseFloat(System.getProperty("java.class.version"));
        if (javaVersion < MIN_CLASS_VERSION) {
            System.err.println("Arclight requires Java " + MIN_JAVA_VERSION);
            System.err.println("Current: " + System.getProperty("java.version"));
            System.exit(-1);
            return;
        }

        if (javaVersion > MAX_CLASS_VERSION) {
            System.err.println("Warning: Arclight is known to be compatible with up to Java " + MAX_JAVA_VERSION + " and may not run on later versions");
            System.err.println("Current: " + System.getProperty("java.version"));
            System.err.flush();
            Thread.sleep(3000);
        }

        // The launcher starts the server itself, so the agreement has to be checked here as well:
        // otherwise a server directory without an accepted licence reaches the game and fails there.
        if (!checkEula(Paths.get(EULA_FILE))) {
            System.err.println("You need to agree to the Minecraft EULA to run this server. Read " + EULA_URL
                + " and set eula=true in " + EULA_FILE + ".");
            System.exit(1);
            return;
        }

        try (InputStream input = Launcher.class.getResourceAsStream("/arclight-server-launch.properties")) {
            Properties properties = new Properties();
            properties.load(input);

            String target = properties.getProperty("launch.mainClass");
            MethodHandle main = MethodHandles.lookup().findStatic(Class.forName(target), "main", MethodType.methodType(void.class, String[].class));
            main.invoke((Object) args);
        }
    }

    /**
     * Keeps the localized startup text readable.
     *
     * <p>The banner and the i18n messages are written straight to the standard streams, before any
     * logger exists. A JVM started under a locale whose console encoding cannot carry non-ASCII
     * characters at all - the POSIX "C" locale, for example - silently replaces every one of them
     * with a question mark. When this sample cannot be encoded by the console,
     * the streams are replaced with UTF-8 writers; that is also the encoding the log files and any
     * process reading a pipe expect. An encoding that can already carry the text, a Windows code
     * page for instance, is left exactly as it is.</p>
     */
    private static void preserveLocalizedOutput() {
        Charset console = consoleCharset();
        if (console != null && console.newEncoder().canEncode(NON_ASCII_SAMPLE)) {
            return;
        }
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8));
    }

    /**
     * Reports the encoding the JVM uses for its standard streams.
     *
     * @return the console encoding, or the platform default when the JVM does not name one
     */
    private static Charset consoleCharset() {
        for (String key : new String[]{"stdout.encoding", "native.encoding"}) {
            String name = System.getProperty(key);
            if (name != null) {
                try {
                    return Charset.forName(name);
                } catch (Exception ignored) {
                    // an unusable property value: try the next source
                }
            }
        }
        return Charset.defaultCharset();
    }

    /**
     * Reads the licence agreement out of the server directory.
     *
     * @param eulaFile path of the file that holds the agreement
     * @return {@code true} when the file exists and holds the accepted setting
     * @throws java.io.IOException when the file exists but cannot be read
     */
    static boolean checkEula(Path eulaFile) throws java.io.IOException {
        if (!Files.isRegularFile(eulaFile)) {
            return false;
        }
        List<String> lines = Files.readAllLines(eulaFile, StandardCharsets.UTF_8);
        return lines.stream().anyMatch("eula=true"::equals);
    }
}

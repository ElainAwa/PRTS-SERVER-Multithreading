package io.izzel.arclight.server;

import java.io.Console;
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
import java.util.Properties;

public class Launcher {

    private static final int MIN_CLASS_VERSION = 65;
    private static final int MIN_JAVA_VERSION = 21;

    private static final int MAX_CLASS_VERSION = 66;
    private static final int MAX_JAVA_VERSION = 22;

    private static final String EULA_URL = "https://aka.ms/MinecraftEULA";
    private static final String EULA_PROMPT_URL = "https://account.mojang.com/documents/minecraft_eula";
    private static final String EULA_FILE = "eula.txt";
    private static final String EULA_TEXT = "#By changing the setting below to TRUE you are indicating your agreement"
        + " to our EULA (" + EULA_PROMPT_URL + ").\n#Automatically generated after the interactive prompt.\neula=true\n";

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
        if (!checkEula(Paths.get(EULA_FILE)) && !promptForEula()) {
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

    /** Keeps the startup text readable by forcing UTF-8 only when the console encoding cannot carry it. */
    private static void preserveLocalizedOutput() {
        Charset console = consoleCharset();
        if (console != null && console.newEncoder().canEncode(NON_ASCII_SAMPLE)) {
            return;
        }
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8));
    }

    /** Reports the encoding the JVM uses for its standard streams. */
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

    /** Asks the console for the agreement and writes it after a yes; no console, a declined answer
     * or a failed write keeps the non-interactive error path and never blocks. */
    private static boolean promptForEula() {
        Console console = System.console();
        if (console == null) {
            return false;
        }
        console.writer().println("By running this server, you agree to the Minecraft EULA.");
        console.writer().println("Read the EULA at: " + EULA_PROMPT_URL);
        console.writer().print("Do you agree to the EULA? (y/N): ");
        console.writer().flush();
        String answer = console.readLine();
        if (answer == null) {
            return false;
        }
        String normalized = answer.trim();
        if (!normalized.equalsIgnoreCase("y") && !normalized.equalsIgnoreCase("yes")) {
            return false;
        }
        try {
            Files.writeString(Paths.get(EULA_FILE), EULA_TEXT, StandardCharsets.UTF_8);
            return true;
        } catch (java.io.IOException failure) {
            System.err.println("Could not write " + EULA_FILE + ": " + failure.getMessage());
            return false;
        }
    }

    /** Reads the agreement with the properties rules, so the exact line and the spaced forms both
     * count; a missing file is not accepted. */
    static boolean checkEula(Path eulaFile) throws java.io.IOException {
        if (!Files.isRegularFile(eulaFile)) {
            return false;
        }
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(eulaFile)) {
            properties.load(input);
        }
        String accepted = properties.getProperty("eula");
        return accepted != null && "true".equalsIgnoreCase(accepted.trim());
    }
}

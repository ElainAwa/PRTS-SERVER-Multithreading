package io.izzel.arclight.i18n;

import ninja.leaping.configurate.ValueType;
import ninja.leaping.configurate.commented.CommentedConfigurationNode;
import ninja.leaping.configurate.hocon.HoconConfigurationLoader;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.MessageFormat;
import java.util.AbstractMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.concurrent.Callable;

public class ArclightLocale {

    private static ArclightLocale instance;

    private final String current, fallback;
    private final CommentedConfigurationNode node;

    public ArclightLocale(String current, String fallback, CommentedConfigurationNode node) {
        this.current = current;
        this.fallback = fallback;
        this.node = node;
    }

    public String getCurrent() {
        return current;
    }

    public String getFallback() {
        return fallback;
    }

    public CommentedConfigurationNode getNode() {
        return node;
    }

    public String format(String node, Object... args) {
        return MessageFormat.format(get(node), args);
    }

    public String get(String path) {
        return getOption(path).orElse(path);
    }

    public Optional<String> getOption(String path) {
        CommentedConfigurationNode node = this.node.getNode((Object[]) path.split("\\."));
        if (node.getValueType() == ValueType.LIST) {
            StringJoiner joiner = new StringJoiner("\n");
            for (CommentedConfigurationNode configurationNode : node.getChildrenList()) {
                joiner.add(configurationNode.getString());
            }
            return Optional.ofNullable(joiner.toString());
        } else {
            return Optional.ofNullable(node.getString());
        }
    }

    public static void info(String path, Object... args) {
        System.out.println(colorize(instance.format(path, args)));
    }

    public static void error(String path, Object... args) {
        System.err.println(colorize(instance.format(path, args)));
    }

    /**
     * Convert Minecraft section-sign color codes (§x) into ANSI escape sequences so that
     * banner/version lines printed directly to System.out (which bypasses the log4j Console
     * appender and its minecraftFormatting converter) are still colored in any ANSI-capable
     * terminal. Matches the §→ANSI mapping used by TerminalConsoleAppender's minecraftFormatting.
     */
    public static String colorize(String s) {
        if (s == null) return null;
        int idx = s.indexOf('\u00A7');
        if (idx < 0) return s;
        StringBuilder sb = new StringBuilder(s.length() + 24);
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\u00A7' && i + 1 < s.length()) {
                String ansi = toAnsi(Character.toLowerCase(s.charAt(i + 1)));
                if (ansi != null) {
                    sb.append(ansi);
                    i += 2;
                    continue;
                }
            }
            sb.append(c);
            i++;
        }
        sb.append("\u001b[0m");
        return sb.toString();
    }

    private static String toAnsi(char code) {
        return switch (code) {
            case '0' -> "\u001b[30m"; // black
            case '1' -> "\u001b[34m"; // dark blue
            case '2' -> "\u001b[32m"; // dark green
            case '3' -> "\u001b[36m"; // dark aqua
            case '4' -> "\u001b[31m"; // dark red
            case '5' -> "\u001b[35m"; // dark purple
            case '6' -> "\u001b[33m"; // gold
            case '7' -> "\u001b[37m"; // gray
            case '8' -> "\u001b[90m"; // dark gray
            case '9' -> "\u001b[94m"; // blue
            case 'a' -> "\u001b[92m"; // green
            case 'b' -> "\u001b[96m"; // aqua
            case 'c' -> "\u001b[91m"; // red
            case 'd' -> "\u001b[95m"; // light purple
            case 'e' -> "\u001b[93m"; // yellow
            case 'f' -> "\u001b[97m"; // white
            case 'l' -> "\u001b[1m";  // bold
            case 'm' -> "\u001b[9m";  // strikethrough
            case 'n' -> "\u001b[4m";  // underline
            case 'o' -> "\u001b[3m";  // italic
            case 'r' -> "\u001b[0m";  // reset
            default -> null;          // §k (obfuscated) and unknown -> skip
        };
    }

    public static ArclightLocale getInstance() {
        return instance;
    }

    private static void init() throws Exception {
        Map.Entry<String, String> entry = getLocale();
        String current = entry.getKey();
        String fallback = entry.getValue();
        InputStream stream = ArclightLocale.class.getResourceAsStream("/META-INF/i18n/" + fallback + ".conf");
        if (stream == null) throw new RuntimeException("Fallback locale is not found: " + fallback);
        CommentedConfigurationNode node = HoconConfigurationLoader.builder().setSource(localeSource(fallback)).build().load();
        instance = new ArclightLocale(current, fallback, node);
        if (!current.equals(fallback)) {
            try {
                CommentedConfigurationNode curNode = HoconConfigurationLoader.builder().setSource(localeSource(current)).build().load();
                curNode.mergeValuesFrom(node);
                instance = new ArclightLocale(current, fallback, curNode);
            } catch (Exception e) {
                System.err.println(instance.format("i18n.current-not-available", current));
            }
        }
    }

    private static Callable<BufferedReader> localeSource(String path) {
        return () -> new BufferedReader(new InputStreamReader(ArclightLocale.class.getResourceAsStream("/META-INF/i18n/" + path + ".conf"), StandardCharsets.UTF_8));
    }

    private static Map.Entry<String, String> getLocale() {
        try {
            Path path = Paths.get("arclight.conf");
            if (!Files.exists(path)) {
                throw new Exception();
            } else {
                CommentedConfigurationNode node = HoconConfigurationLoader.builder().setPath(path).build().load();
                CommentedConfigurationNode locale = node.getNode("locale");
                String current = locale.getNode("current").getString(currentLocale());
                String fallback = locale.getNode("fallback").getString("zh_cn");
                return new AbstractMap.SimpleImmutableEntry<>(current, fallback);
            }
        } catch (Throwable t) {
            return new AbstractMap.SimpleImmutableEntry<>(currentLocale(), "zh_cn");
        }
    }

    private static String currentLocale() {
        Locale locale = Locale.getDefault();
        return locale.getLanguage().toLowerCase(Locale.ROOT) + "_" + locale.getCountry().toLowerCase(Locale.ROOT);
    }

    static {
        try {
            init();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}

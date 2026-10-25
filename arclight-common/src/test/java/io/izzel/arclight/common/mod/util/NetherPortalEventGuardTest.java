/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.mod.util;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Source-text guard: reads mixin sources and does not run the mixin. It pins the shape that
// matters at class transformation time, because a second decoration callsite op inside one
// @Decorate handler fails the whole mixin at bootstrap even though unit tests do not run mixins.
class NetherPortalEventGuardTest {

    @Test
    void portalHandlersReadTheWorldWithoutThrowing() throws Exception {
        String text = source("mixin/core/world/level/block/NetherPortalBlockMixin.java");
        assertFalse(text.contains(".bridge$getWorld()"), "nether portals do not call the strict accessor");
        String enter = between(text, "void arclight$portalEnter", "DimensionTransition arclight$createPortalEvent");
        String exit = text.substring(text.indexOf("DimensionTransition arclight$createPortalEvent"));
        assertTrue(enter.contains("bridge$getWorldOrNull()") && enter.indexOf("new EntityPortalEnterEvent") > enter.indexOf("bworld == null"));
        assertTrue(exit.contains("bridge$getWorldOrNull()"));
        assertTrue(countOccurrences(exit, "DecorationOps.callsite()") == 1,
            "the portal decoration handler carries exactly one decoration callsite op");
    }

    @Test
    void missingDestinationWorldFallsThroughToVanillaWithoutASecondCallsite() throws Exception {
        String exit = source("mixin/core/world/level/block/NetherPortalBlockMixin.java");
        exit = exit.substring(exit.indexOf("DimensionTransition arclight$createPortalEvent"));
        int guard = exit.indexOf("toWorld != null");
        int event = exit.indexOf("bridge$callPortalEvent");
        assertTrue(guard >= 0 && event > guard,
            "the portal event is only built when a Bukkit world can host it");
        assertTrue(countOccurrences(exit, "DecorationOps.callsite()") == 1,
            "a missing destination world only needs the vanilla exit, not a branch-local callsite op");
    }

    @Test
    void everyDecorateHandlerHasExactlyOneCallsiteInItsBody() throws Exception {
        Path root = Path.of("src/main/java");
        if (!Files.isDirectory(root)) {
            root = Path.of("arclight-common/src/main/java");
        }
        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            // Only the core mixin tree is checked. Optional mod-support mixins use
            // @Decorate(inject = true) with one call per branch on purpose and are outside
            // this guard; this test pins the upstream mixin shape that broke at bootstrap.
            for (Path file : files.filter(p -> p.toString().endsWith(".java"))
                .filter(p -> p.toString().replace('\\', '/').contains("/mixin/")).toList()) {
                String text = Files.readString(file);
                int from = 0;
                while (true) {
                    int at = text.indexOf("@Decorate(", from);
                    if (at < 0) {
                        break;
                    }
                    int bodyStart = text.indexOf('{', at);
                    int bodyEnd = bodyStart < 0 ? -1 : matchingBrace(text, bodyStart);
                    if (bodyEnd < 0) {
                        violations.add(file + " @Decorate body not found");
                        break;
                    }
                    int ops = countOccurrences(text.substring(bodyStart, bodyEnd), "DecorationOps.callsite()");
                    if (ops > 1) {
                        violations.add(file + " has " + ops + " decoration callsite ops in one @Decorate handler");
                    }
                    from = bodyEnd;
                }
            }
        }
        assertTrue(violations.isEmpty(), String.join("\n", violations));
    }

    private static int matchingBrace(String text, int open) {
        int depth = 0;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static int countOccurrences(String text, String token) {
        int count = 0;
        int from = 0;
        while (true) {
            int at = text.indexOf(token, from);
            if (at < 0) {
                return count;
            }
            count++;
            from = at + token.length();
        }
    }

    private static String between(String text, String startToken, String endToken) {
        int start = text.indexOf(startToken);
        int end = text.indexOf(endToken, start + startToken.length());
        assertTrue(start >= 0 && end > start);
        return text.substring(start, end);
    }

    private static String source(String relative) throws Exception {
        Path path = Path.of("src/main/java/io/izzel/arclight/common/" + relative);
        if (!Files.exists(path)) {
            path = Path.of("arclight-common/src/main/java/io/izzel/arclight/common/" + relative);
        }
        return Files.readString(path);
    }
}

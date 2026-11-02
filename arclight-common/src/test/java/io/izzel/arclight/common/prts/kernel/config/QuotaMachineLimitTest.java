/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.config;

import io.izzel.arclight.common.prts.config.PrtsConfigManager;
import io.izzel.arclight.common.prts.kernel.observe.QuotaMachineWindow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The MACHINE family has its own declared quota: a low machine limit can be over while the global
 * tick budget is fifty, and a global budget of one does not stand in for a machine limit of fifty. */
class QuotaMachineLimitTest {

    private static final Path CONFIG_DIR = PrtsConfigManager.directory();
    private static final long WINDOW_TICKS = 20L;

    @BeforeEach
    void resetWindow() {
        QuotaMachineWindow.reset();
    }

    @AfterEach
    void restoreDefaults() throws IOException {
        QuotaMachineWindow.reset();
        ExecutableProductFace.restore();
    }

    @Test
    void theMachineLimitDefaultsToFiftyAndIsNotTheGlobalBudget() throws IOException {
        load(50, 50);
        assertEquals(50, KernelSettings.quotaMachineMs());
        assertEquals(50L, KernelSettings.eBudgetMs());
    }

    @Test
    void aLowMachineLimitFiresWhileTheGlobalBudgetIsStillFifty() throws IOException {
        load(50, 1);
        assertEquals(50L, KernelSettings.eBudgetMs());
        assertEquals(1, KernelSettings.quotaMachineMs());

        feedMachineWindow(20_000_000L, KernelSettings.quotaMachineMs());
        assertEquals(1L, QuotaMachineWindow.overCount(),
            "the machine family's own limit arms the over while the global budget stays at fifty");
    }

    @Test
    void aLowGlobalBudgetDoesNotStandInForTheMachineLimit() throws IOException {
        load(1, 50);
        assertEquals(1L, KernelSettings.eBudgetMs());
        assertEquals(50, KernelSettings.quotaMachineMs());

        feedMachineWindow(20_000_000L, KernelSettings.quotaMachineMs());
        assertEquals(0L, QuotaMachineWindow.overCount(),
            "a global budget of one is not the machine limit and must not arm it");
    }

    private static void feedMachineWindow(long usedNanos, double limitMs) {
        QuotaMachineWindow.noteTick(0L, 0L, WINDOW_TICKS, 0L, limitMs);
        QuotaMachineWindow.noteTick(WINDOW_TICKS, usedNanos, WINDOW_TICKS, 0L, limitMs);
    }

    private static void load(int eBudgetMs, int quotaMachineMs) throws IOException {
        PrtsConfigManager.Entry entry = PrtsConfigManager.entries().get(PrtsConfigManager.KERNEL);
        String content = PrtsConfigManager.defaults(entry);
        content = number(content, "e-budget-ms", eBudgetMs);
        content = number(content, "quota-machine-ms", quotaMachineMs);
        Files.createDirectories(CONFIG_DIR);
        Files.writeString(CONFIG_DIR.resolve("kernel.yml"), content, StandardCharsets.UTF_8);
        PrtsConfigManager.ensureAndLoad();
    }

    private static String number(String content, String name, int value) {
        Pattern pattern = Pattern.compile(
            "(?m)^([ \\t]*" + Pattern.quote(name) + ":\\s*)(-?\\d+)(\\s*)$");
        if (!pattern.matcher(content).find()) {
            throw new IllegalStateException("the kernel defaults carry no number for " + name);
        }
        return pattern.matcher(content).replaceFirst("$1" + value);
    }
}

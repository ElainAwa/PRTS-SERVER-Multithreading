/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.observe;

import io.izzel.arclight.common.prts.config.PrtsCommandExtension;
import io.izzel.arclight.common.prts.kernel.KernelModule;

import java.util.ArrayList;
import java.util.List;

/** The extension is what keeps the command layer and the kernel apart: the configuration package
 * owns the command and the extension point, this class implements the point, and neither imports
 * the other's internals. */
public final class KernelCommandExtension implements PrtsCommandExtension {

    private static final String NAME = "kernel";
    private static final String SELFTEST = "selftest";
    private static final String PREFIX = "[PRTS] kernel ";

    private KernelCommandExtension() {
    }

    public static PrtsCommandExtension extension() {
        return new KernelCommandExtension();
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public List<String> subcommands() {
        return List.of(SELFTEST);
    }

    @Override
    public List<String> run(List<String> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return export();
        }
        if (arguments.size() == 1 && SELFTEST.equalsIgnoreCase(arguments.get(0))) {
            return selftest();
        }
        return List.of("[PRTS] kernel usage: /prts kernel [" + SELFTEST + "]");
    }

    @Override
    public List<String> complete(List<String> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return List.of(SELFTEST);
        }
        return List.of();
    }

    @Override
    public List<String> statusLines() {
        return KernelStatusLines.status(KernelModule.instance());
    }

    private static List<String> export() {
        List<String> lines = new ArrayList<>();
        lines.add("[PRTS] kernel export (observation requests; zero values are published too)");
        for (String line : KernelReadings.export(KernelModule.instance())) {
            lines.add(PREFIX + line);
        }
        return lines;
    }

    private static List<String> selftest() {
        List<String> lines = new ArrayList<>();
        for (String line : KernelSelfCheck.run()) {
            lines.add(PREFIX + line);
        }
        return lines;
    }
}

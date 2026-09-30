/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.config;

import java.util.List;

/**
 * A command subtree another layer contributes to {@code /prts}.
 *
 * <p>The configuration layer owns the command and this extension point, but it does not know the
 * layers that use it. An extension registers itself, names its literal and answers with plain text
 * lines, so the configuration package never depends on the kernel or on any other feature package
 * and the command stays reachable from both command worlds.</p>
 *
 * <p>An extension is registered through {@link PrtsCommand#registerExtension}. Registering the same
 * name twice replaces the earlier instance, which keeps a command rebuild idempotent.</p>
 */
public interface PrtsCommandExtension {

    /** @return the literal this extension answers to, without the command label */
    String name();

    /** @return one level of literals below the extension name, in the order they are offered */
    default List<String> subcommands() {
        return List.of();
    }

    /**
     * Runs the extension.
     *
     * @param arguments the arguments behind the extension name; empty for the bare name
     * @return the lines to send, one message per line; never {@code null}
     */
    List<String> run(List<String> arguments);

    /**
     * Completes the arguments behind the extension name.
     *
     * @param arguments the arguments typed so far, without the extension name
     * @return the candidates; never {@code null}
     */
    default List<String> complete(List<String> arguments) {
        return List.of();
    }

    /** @return the lines this extension adds to {@code /prts status} */
    default List<String> statusLines() {
        return List.of();
    }
}

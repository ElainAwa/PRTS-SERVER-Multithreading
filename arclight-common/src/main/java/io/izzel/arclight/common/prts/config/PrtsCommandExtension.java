/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.config;

import java.util.List;

/**
 * A command subtree another layer contributes to {@code /prts}: it names its literal and answers
 * with plain text lines, so the configuration package never depends on the layers it serves.
 * Registering the same name twice replaces the earlier instance.
 */
public interface PrtsCommandExtension {

    /** @return the literal this extension answers to, without the command label */
    String name();

    /** @return one level of literals below the extension name, in the order they are offered */
    default List<String> subcommands() {
        return List.of();
    }

    /** @return the lines to send, one message per line; never {@code null} */
    List<String> run(List<String> arguments);

    /** @return the candidates for the arguments behind the extension name; never {@code null} */
    default List<String> complete(List<String> arguments) {
        return List.of();
    }

    /** @return the lines this extension adds to {@code /prts status} */
    default List<String> statusLines() {
        return List.of();
    }
}

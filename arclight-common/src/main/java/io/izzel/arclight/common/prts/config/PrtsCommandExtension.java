/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.config;

import java.util.List;

/** A command subtree another layer contributes to {@code /prts}; a repeated name replaces the earlier one. */
public interface PrtsCommandExtension {

    String name();

    default List<String> subcommands() {
        return List.of();
    }

    /** @return the lines to send, one message per line; never {@code null} */
    List<String> run(List<String> arguments);

    default List<String> complete(List<String> arguments) {
        return List.of();
    }

    default List<String> statusLines() {
        return List.of();
    }
}

/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.player;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** The execution contract of one command, function or packet: the permission check runs before any
 * side effect and a denied command writes nothing; the effects apply in their declared order; an
 * accepted asynchronous ticket is not a success, only the real effect completion is; a cancelled or
 * timed-out ticket never reports success and a repeated command id applies its effects once. */
public final class CommandExecutionContract {

    /** The real completion states; only EXECUTED_SUCCESS counts as success. */
    public enum State {
        DENIED,
        TICKET_ACCEPTED,
        EXECUTED_SUCCESS,
        EXECUTED_FAILURE,
        CANCELLED,
        TIMED_OUT
    }

    /** One command with its permission and declared effects, in order. */
    public record Command(String commandId, String permission, List<String> effects) {

        public Command {
            Objects.requireNonNull(commandId, "commandId");
            Objects.requireNonNull(permission, "permission");
            Objects.requireNonNull(effects, "effects");
            if (commandId.isEmpty() || permission.isEmpty()) {
                throw new IllegalArgumentException("a command names itself and its permission");
            }
        }
    }

    /** One accepted asynchronous ticket; it is not a completion. */
    public record Ticket(String ticketId, String commandId) {
    }

    private final Map<String, State> states = new LinkedHashMap<>();
    private final Map<String, State> tickets = new LinkedHashMap<>();
    private final Map<String, List<String>> appliedEffects = new LinkedHashMap<>();
    private long succeeded;
    private long denied;
    private long cancelled;
    private long timedOut;

    /** Executes a command synchronously only after its permission is granted. */
    public synchronized State execute(Command command, boolean permissionGranted) {
        Objects.requireNonNull(command, "command");
        State known = states.get(command.commandId());
        if (known != null) {
            return known;
        }
        if (!permissionGranted) {
            states.put(command.commandId(), State.DENIED);
            denied++;
            return State.DENIED;
        }
        applyEffects(command);
        states.put(command.commandId(), State.EXECUTED_SUCCESS);
        succeeded++;
        return State.EXECUTED_SUCCESS;
    }

    /** Accepts one command for later execution; the returned ticket is never a success. */
    public synchronized Ticket submitAsync(Command command) {
        Objects.requireNonNull(command, "command");
        String ticketId = "ticket:" + command.commandId();
        tickets.put(ticketId, State.TICKET_ACCEPTED);
        return new Ticket(ticketId, command.commandId());
    }

    /** Completes one accepted ticket with the real result of its effects. */
    public synchronized State complete(Ticket ticket, boolean success) {
        Objects.requireNonNull(ticket, "ticket");
        if (tickets.get(ticket.ticketId()) != State.TICKET_ACCEPTED) {
            return tickets.getOrDefault(ticket.ticketId(), State.EXECUTED_FAILURE);
        }
        State state = success ? State.EXECUTED_SUCCESS : State.EXECUTED_FAILURE;
        tickets.put(ticket.ticketId(), state);
        states.put(ticket.commandId(), state);
        if (success) {
            succeeded++;
        }
        return state;
    }

    /** Cancels one open ticket; a cancelled ticket is complete but never successful. */
    public synchronized State cancel(Ticket ticket) {
        Objects.requireNonNull(ticket, "ticket");
        if (tickets.get(ticket.ticketId()) != State.TICKET_ACCEPTED) {
            return tickets.getOrDefault(ticket.ticketId(), State.CANCELLED);
        }
        tickets.put(ticket.ticketId(), State.CANCELLED);
        states.put(ticket.commandId(), State.CANCELLED);
        cancelled++;
        return State.CANCELLED;
    }

    /** Marks one open ticket timed out; a timeout is never a success. */
    public synchronized State timedOut(Ticket ticket) {
        Objects.requireNonNull(ticket, "ticket");
        if (tickets.get(ticket.ticketId()) != State.TICKET_ACCEPTED) {
            return tickets.getOrDefault(ticket.ticketId(), State.TIMED_OUT);
        }
        tickets.put(ticket.ticketId(), State.TIMED_OUT);
        states.put(ticket.commandId(), State.TIMED_OUT);
        timedOut++;
        return State.TIMED_OUT;
    }

    /** Whether the command really completed its effects. */
    public synchronized boolean completed(String commandId) {
        return states.get(commandId) == State.EXECUTED_SUCCESS;
    }

    /** The effects applied so far, in their declared order. */
    public synchronized List<String> appliedEffects(String commandId) {
        return List.copyOf(appliedEffects.getOrDefault(commandId, List.of()));
    }

    public synchronized long succeeded() {
        return succeeded;
    }

    public synchronized long denied() {
        return denied;
    }

    public synchronized long cancelled() {
        return cancelled;
    }

    public synchronized long timedOut() {
        return timedOut;
    }

    private void applyEffects(Command command) {
        List<String> applied = new ArrayList<>();
        for (String effect : command.effects()) {
            applied.add(effect);
        }
        appliedEffects.put(command.commandId(), List.copyOf(applied));
    }
}

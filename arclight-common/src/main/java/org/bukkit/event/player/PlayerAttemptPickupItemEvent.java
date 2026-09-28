/*
 * Ported from the FeudalKings fork of Arclight, commit e195f79e06113774502c4986da0b3e63150f1455
 * ("add player attempt pickup event").
 * The class mirrors the API contract plugins compile against; see THIRD-PARTY.md.
 */
package org.bukkit.event.player;

import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * Thrown when a player attempts to pick an item up from the ground.
 *
 * <p>Fired before the server decides whether the stack fits into the player inventory, so a plugin
 * can suppress the pickup, or keep the item from flying at the player, without touching the
 * inventory.</p>
 */
public class PlayerAttemptPickupItemEvent extends PlayerEvent implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Item item;
    private final int remaining;
    private boolean flyAtPlayer = true;
    private boolean cancelled;

    /**
     * Creates the event without a remaining count.
     *
     * @param player player attempting the pickup
     * @param item   item on the ground
     * @deprecated use {@link #PlayerAttemptPickupItemEvent(Player, Item, int)}
     */
    @Deprecated
    public PlayerAttemptPickupItemEvent(@NotNull Player player, @NotNull Item item) {
        this(player, item, 0);
    }

    /**
     * Creates the event.
     *
     * @param player    player attempting the pickup
     * @param item      item on the ground
     * @param remaining amount that stays on the ground when the pickup succeeds
     */
    public PlayerAttemptPickupItemEvent(@NotNull Player player, @NotNull Item item, int remaining) {
        super(player);
        this.item = item;
        this.remaining = remaining;
    }

    /**
     * Returns the handler list of this event.
     *
     * @return the static handler list
     */
    @NotNull
    public static HandlerList getHandlerList() {
        return HANDLERS;
    }

    /**
     * Returns the item the player attempted to pick up.
     *
     * @return the item on the ground
     */
    @NotNull
    public Item getItem() {
        return this.item;
    }

    /**
     * Returns the amount that stays on the ground when the pickup succeeds.
     *
     * @return the remaining item count
     */
    public int getRemaining() {
        return this.remaining;
    }

    /**
     * Returns whether the pickup animation reaches the player when this event prevents the pickup.
     *
     * @return {@code true} when the item flies at the player
     */
    public boolean getFlyAtPlayer() {
        return this.flyAtPlayer;
    }

    /**
     * Sets whether the pickup animation reaches the player when this event prevents the pickup.
     *
     * @param flyAtPlayer {@code true} when the item should fly at the player
     */
    public void setFlyAtPlayer(boolean flyAtPlayer) {
        this.flyAtPlayer = flyAtPlayer;
    }

    @Override
    public boolean isCancelled() {
        return this.cancelled;
    }

    @Override
    public void setCancelled(boolean cancelled) {
        this.cancelled = cancelled;
        this.flyAtPlayer = !cancelled;
    }

    @NotNull
    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }
}

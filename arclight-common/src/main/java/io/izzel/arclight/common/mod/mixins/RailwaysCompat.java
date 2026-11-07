package io.izzel.arclight.common.mod.mixins;

import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;

/** Result conversions used by the runtime ABI bridge for old block-use entry points. */
public final class RailwaysCompat {

    private RailwaysCompat() {
    }

    public static InteractionResult toInteractionResult(ItemInteractionResult result) {
        return result == null ? InteractionResult.PASS : result.result();
    }

    public static ItemInteractionResult toItemInteractionResult(InteractionResult result) {
        if (result == null) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        return switch (result) {
            case SUCCESS, SUCCESS_NO_ITEM_USED -> ItemInteractionResult.SUCCESS;
            case CONSUME -> ItemInteractionResult.CONSUME;
            case CONSUME_PARTIAL -> ItemInteractionResult.CONSUME_PARTIAL;
            case FAIL -> ItemInteractionResult.FAIL;
            case PASS -> ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        };
    }
}

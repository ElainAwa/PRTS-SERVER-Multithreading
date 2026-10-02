/* SPDX-License-Identifier: GPL-3.0-or-later */
/* Times the five structure index questions, which reach the chunks the structures live in. */
package io.izzel.arclight.common.prts.fixes.waitpoints;

import io.izzel.arclight.api.ArclightPlatform;
import io.izzel.arclight.common.mod.mixins.annotation.OnlyInPlatform;
import io.izzel.arclight.common.prts.support.PrtsWaitSites;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;

@OnlyInPlatform(ArclightPlatform.NEOFORGE)
@Mixin(StructureManager.class)
public abstract class PrtsStructureManagerWaitSitesMixin {

    @Inject(method = "startsForStructure(Lnet/minecraft/world/level/ChunkPos;"
        + "Ljava/util/function/Predicate;)Ljava/util/List;", at = @At("HEAD"))
    private void prts$openStartsForChunkPos(ChunkPos pos, Predicate<Structure> filter,
                                            CallbackInfoReturnable<List<StructureStart>> cir) {
        PrtsWaitSites.begin(PrtsWaitSites.STRUCTURE_MANAGER_STARTS_FOR_CHUNK_POS);
    }

    @Inject(method = "startsForStructure(Lnet/minecraft/world/level/ChunkPos;"
        + "Ljava/util/function/Predicate;)Ljava/util/List;", at = @At("RETURN"))
    private void prts$closeStartsForChunkPos(ChunkPos pos, Predicate<Structure> filter,
                                             CallbackInfoReturnable<List<StructureStart>> cir) {
        PrtsWaitSites.end(PrtsWaitSites.STRUCTURE_MANAGER_STARTS_FOR_CHUNK_POS);
    }

    @Inject(method = "startsForStructure(Lnet/minecraft/core/SectionPos;"
        + "Lnet/minecraft/world/level/levelgen/structure/Structure;)Ljava/util/List;",
        at = @At("HEAD"))
    private void prts$openStartsForSection(SectionPos pos, Structure structure,
                                           CallbackInfoReturnable<List<StructureStart>> cir) {
        PrtsWaitSites.begin(PrtsWaitSites.STRUCTURE_MANAGER_STARTS_FOR_SECTION);
    }

    @Inject(method = "startsForStructure(Lnet/minecraft/core/SectionPos;"
        + "Lnet/minecraft/world/level/levelgen/structure/Structure;)Ljava/util/List;",
        at = @At("RETURN"))
    private void prts$closeStartsForSection(SectionPos pos, Structure structure,
                                            CallbackInfoReturnable<List<StructureStart>> cir) {
        PrtsWaitSites.end(PrtsWaitSites.STRUCTURE_MANAGER_STARTS_FOR_SECTION);
    }

    @Inject(method = "fillStartsForStructure", at = @At("HEAD"))
    private void prts$openFillStarts(Structure structure, LongSet references,
                                     Consumer<StructureStart> consumer, CallbackInfo ci) {
        PrtsWaitSites.begin(PrtsWaitSites.STRUCTURE_MANAGER_FILL_STARTS);
    }

    @Inject(method = "fillStartsForStructure", at = @At("RETURN"))
    private void prts$closeFillStarts(Structure structure, LongSet references,
                                      Consumer<StructureStart> consumer, CallbackInfo ci) {
        PrtsWaitSites.end(PrtsWaitSites.STRUCTURE_MANAGER_FILL_STARTS);
    }

    @Inject(method = "hasAnyStructureAt", at = @At("HEAD"))
    private void prts$openHasAnyStructureAt(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        PrtsWaitSites.begin(PrtsWaitSites.STRUCTURE_MANAGER_HAS_ANY_STRUCTURE_AT);
    }

    @Inject(method = "hasAnyStructureAt", at = @At("RETURN"))
    private void prts$closeHasAnyStructureAt(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        PrtsWaitSites.end(PrtsWaitSites.STRUCTURE_MANAGER_HAS_ANY_STRUCTURE_AT);
    }

    @Inject(method = "getAllStructuresAt", at = @At("HEAD"))
    private void prts$openGetAllStructuresAt(BlockPos pos,
                                             CallbackInfoReturnable<Map<Structure, LongSet>> cir) {
        PrtsWaitSites.begin(PrtsWaitSites.STRUCTURE_MANAGER_GET_ALL_STRUCTURES_AT);
    }

    @Inject(method = "getAllStructuresAt", at = @At("RETURN"))
    private void prts$closeGetAllStructuresAt(BlockPos pos,
                                              CallbackInfoReturnable<Map<Structure, LongSet>> cir) {
        PrtsWaitSites.end(PrtsWaitSites.STRUCTURE_MANAGER_GET_ALL_STRUCTURES_AT);
    }
}

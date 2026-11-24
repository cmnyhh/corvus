package dev.dronepal.client.mixin;

import dev.dronepal.client.DroneLamps;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelRenderer.class)
abstract class DroneLampMixin {
    @Inject(method="getLightColor(Lnet/minecraft/world/level/BlockAndTintGetter;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;)I", at=@At("RETURN"), cancellable=true)
    private static void dronepal$light(BlockAndTintGetter level, BlockState state, BlockPos pos, CallbackInfoReturnable<Integer> result) {
        int packed=result.getReturnValueI();
        int block=DroneLamps.sample(pos);
        if (block>((packed>>4)&15)) result.setReturnValue((packed & ~0xf0) | block<<4);
    }
}

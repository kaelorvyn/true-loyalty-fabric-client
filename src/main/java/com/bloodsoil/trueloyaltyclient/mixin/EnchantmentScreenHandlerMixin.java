package com.bloodsoil.trueloyaltyclient.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.EnchantmentScreenHandler;
import net.minecraft.server.network.ServerPlayerEntity;

import com.bloodsoil.trueloyaltyclient.TrueLoyaltyManager;
import com.bloodsoil.trueloyaltyclient.TrueLoyaltyServer;

@Environment(EnvType.CLIENT)
@Mixin(EnchantmentScreenHandler.class)
public class EnchantmentScreenHandlerMixin {

    @Inject(method = "onButtonClick", at = @At("TAIL"))
    private void trueloyalty$grant(PlayerEntity player, int id, CallbackInfoReturnable<Boolean> cir) {
        if (!(player instanceof ServerPlayerEntity serverPlayer) || TrueLoyaltyServer.manager == null) {
            return;
        }
        ItemStack result = ((EnchantmentScreenHandler) (Object) this).getSlot(0).getStack();
        if (TrueLoyaltyManager.hasTrueLoyalty(result)) {
            TrueLoyaltyServer.manager.grantAdvancement(serverPlayer, "sea_god_trident", "enchanted");
        }
    }
}

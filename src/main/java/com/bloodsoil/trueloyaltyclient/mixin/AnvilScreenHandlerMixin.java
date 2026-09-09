package com.bloodsoil.trueloyaltyclient.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.AnvilScreenHandler;
import net.minecraft.server.network.ServerPlayerEntity;

import com.bloodsoil.trueloyaltyclient.TrueLoyaltyManager;
import com.bloodsoil.trueloyaltyclient.TrueLoyaltyServer;

@Environment(EnvType.CLIENT)
@Mixin(AnvilScreenHandler.class)
public class AnvilScreenHandlerMixin {

    @Inject(method = "onTakeOutput", at = @At("HEAD"))
    private void trueloyalty$grant(PlayerEntity player, ItemStack stack, CallbackInfo ci) {
        if (player instanceof ServerPlayerEntity serverPlayer
                && TrueLoyaltyServer.manager != null
                && TrueLoyaltyManager.hasTrueLoyalty(stack)) {
            TrueLoyaltyServer.manager.grantAdvancement(serverPlayer, "sea_god_trident", "enchanted");
        }
    }
}

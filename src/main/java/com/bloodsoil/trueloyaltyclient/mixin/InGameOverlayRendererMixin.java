package com.bloodsoil.trueloyaltyclient.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.InGameOverlayRenderer;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.math.random.Random;

@Environment(EnvType.CLIENT)
@Mixin(InGameOverlayRenderer.class)
public class InGameOverlayRendererMixin {

    @Shadow
    private ItemStack floatingItem;

    @Shadow
    private int floatingItemTimer;

    @Shadow
    private float floatingItemOffsetX;

    @Shadow
    private float floatingItemOffsetY;

    @Inject(method = "setFloatingItem", at = @At("HEAD"), cancellable = true)
    private void trueloyalty$replaceWithTrident(ItemStack stack, Random random, CallbackInfo ci) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) {
            return;
        }
        ItemStack trident = findTrueLoyaltyTrident(client.player);
        if (trident == null) {
            return;
        }
        this.floatingItem = trident.copy();
        this.floatingItemTimer = 40;
        this.floatingItemOffsetX = random.nextFloat() * 0.2F - 0.1F;
        this.floatingItemOffsetY = random.nextFloat() * 0.2F - 0.1F;
        ci.cancel();
    }

    private static ItemStack findTrueLoyaltyTrident(PlayerEntity player) {
        ItemStack main = player.getMainHandStack();
        if (hasTrueLoyalty(main)) {
            return main;
        }
        ItemStack off = player.getOffHandStack();
        if (hasTrueLoyalty(off)) {
            return off;
        }
        return null;
    }

    private static boolean hasTrueLoyalty(ItemStack stack) {
        if (stack.getItem() != Items.TRIDENT) {
            return false;
        }
        ItemEnchantmentsComponent enchantments = stack.get(DataComponentTypes.ENCHANTMENTS);
        if (enchantments == null) {
            return false;
        }
        for (RegistryEntry<Enchantment> entry : enchantments.getEnchantments()) {
            if (entry.getKey().isPresent()
                    && entry.getKey().get().getValue().toString().equals("bloodsoil:true_loyalty")) {
                return true;
            }
        }
        return false;
    }
}

package com.bloodsoil.trueloyaltyclient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.advancement.AdvancementEntry;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.DeathProtectionComponent;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.LightningEntity;
import net.minecraft.entity.boss.WitherEntity;
import net.minecraft.entity.boss.dragon.EnderDragonEntity;
import net.minecraft.entity.boss.dragon.EnderDragonPart;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.damage.DamageTypes;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.Monster;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.entity.projectile.PersistentProjectileEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.s2c.play.EntityStatusS2CPacket;
import net.minecraft.network.packet.s2c.play.SubtitleS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleFadeS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleS2CPacket;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;

public class TrueLoyaltyManager {

    public static final String BOLT_TAG_PREFIX = "trueloyalty_bolt_";

    private final List<TrueLoyaltySequence> sequences = new ArrayList<>();
    private final List<DelayedTask> delayed = new ArrayList<>();
    private final Map<UUID, Integer> lastTriggerTick = new HashMap<>();
    private final Map<UUID, Integer> lastCombatTick = new HashMap<>();
    private final List<BoltRecord> boltRecords = new ArrayList<>();

    private record DelayedTask(int atTick, Runnable run) {
    }

    private record BoltRecord(UUID owner, Vec3d pos, int tick) {
    }

    public boolean onAllowDamage(LivingEntity entity, DamageSource source, float amount) {
        if (!(entity instanceof ServerPlayerEntity player)) {
            return true;
        }
        MinecraftServer server = serverOf(player);
        if (server == null) {
            return true;
        }
        if (player.isDead() || lastTriggerTick.getOrDefault(player.getUuid(), -1) == server.getTicks()) {
            return true;
        }
        recordCombat(player, source);
        if (isOwnLightning(player, source)) {
            return false;
        }
        if (amount < player.getHealth() + player.getAbsorptionAmount()) {
            return true;
        }
        if (!isCombatKill(player, source)) {
            return true;
        }
        return !trigger(player, source);
    }

    public boolean onAllowDeath(LivingEntity entity, DamageSource source, float amount) {
        if (!(entity instanceof ServerPlayerEntity player)) {
            return true;
        }
        MinecraftServer server = serverOf(player);
        if (server == null) {
            return true;
        }
        if (lastTriggerTick.getOrDefault(player.getUuid(), -1) == server.getTicks()) {
            return true;
        }
        if (!isCombatKill(player, source)) {
            return true;
        }
        return !trigger(player, source);
    }

    public void tick(MinecraftServer server) {
        int ticks = server.getTicks();
        boltRecords.removeIf(record -> ticks - record.tick() > 20);
        delayed.removeIf(task -> {
            if (task.atTick() <= ticks) {
                task.run().run();
                return true;
            }
            return false;
        });
        Iterator<TrueLoyaltySequence> it = sequences.iterator();
        while (it.hasNext()) {
            TrueLoyaltySequence sequence = it.next();
            sequence.tick();
            if (sequence.isDone()) {
                it.remove();
            }
        }
    }

    public void runLater(MinecraftServer server, int delayTicks, Runnable runnable) {
        delayed.add(new DelayedTask(server.getTicks() + delayTicks, runnable));
    }

    private boolean trigger(ServerPlayerEntity player, DamageSource source) {
        MinecraftServer server = serverOf(player);
        if (server == null) {
            return false;
        }
        PlayerInventory inventory = player.getInventory();
        int slot = inventory.getSelectedSlot();
        ItemStack main = inventory.getStack(slot);
        ItemStack visual = null;
        int consumedSlot = -1;
        if (hasTrueLoyalty(main)) {
            visual = main.copy();
            main.decrement(1);
            inventory.setStack(slot, main);
            consumedSlot = slot;
        } else {
            ItemStack off = inventory.getStack(40);
            if (hasTrueLoyalty(off)) {
                visual = off.copy();
                off.decrement(1);
                inventory.setStack(40, off);
                consumedSlot = 40;
            }
        }
        if (visual == null || consumedSlot == -1) {
            return false;
        }
        player.currentScreenHandler.sendContentUpdates();
        lastTriggerTick.put(player.getUuid(), server.getTicks());
        grantAdvancement(player, "sea_god_dusk", "triggered");
        player.setHealth(1.0F);
        playTotemEffects(player, visual, server, consumedSlot);
        PlayerEntity revenge = revengeFrom(source);
        TrueLoyaltySequence sequence = new TrueLoyaltySequence(player, revenge, this);
        sequences.add(sequence);
        return true;
    }

    private PlayerEntity revengeFrom(DamageSource source) {
        Entity attacker = source.getAttacker();
        if (attacker instanceof PlayerEntity player) {
            return player;
        }
        if (attacker instanceof PersistentProjectileEntity projectile && projectile.getOwner() instanceof PlayerEntity player) {
            return player;
        }
        return null;
    }

    private void playTotemEffects(ServerPlayerEntity player, ItemStack visual, MinecraftServer server, int consumedSlot) {
        ServerWorld world = (ServerWorld) player.getEntityWorld();
        Vec3d pos = player.getEntityPos();
        world.spawnParticles(ParticleTypes.TOTEM_OF_UNDYING, pos.x, pos.y + 1, pos.z, 60, 0.6, 0.8, 0.6, 0.1);
        world.spawnParticles(ParticleTypes.END_ROD, pos.x, pos.y + 0.5, pos.z, 80, 1.0, 1.2, 1.0, 0.15);
        world.playSound(null, pos.x, pos.y, pos.z, SoundEvents.ITEM_TOTEM_USE, SoundCategory.PLAYERS, 1.0F, 1.0F);

        PlayerInventory inventory = player.getInventory();
        ItemStack oldStack = inventory.getStack(consumedSlot).copy();
        ItemStack floating = visual.copy();
        DeathProtectionComponent protection = new ItemStack(Items.TOTEM_OF_UNDYING).get(DataComponentTypes.DEATH_PROTECTION);
        if (protection != null) {
            floating.set(DataComponentTypes.DEATH_PROTECTION, protection);
        }
        inventory.setStack(consumedSlot, floating.copy());
        player.currentScreenHandler.sendContentUpdates();

        runLater(server, 1, () -> player.networkHandler.sendPacket(new EntityStatusS2CPacket(player, (byte) 35)));
        runLater(server, 3, () -> {
            if (player.isDisconnected()) {
                return;
            }
            ItemStack current = inventory.getStack(consumedSlot);
            if (current.getItem() == Items.TRIDENT
                    && current.contains(DataComponentTypes.DEATH_PROTECTION)) {
                inventory.setStack(consumedSlot, oldStack);
                player.currentScreenHandler.sendContentUpdates();
            }
        });

        player.clearStatusEffects();
        player.addStatusEffect(new StatusEffectInstance(StatusEffects.ABSORPTION, 100, 1, false, true, true));
        player.addStatusEffect(new StatusEffectInstance(StatusEffects.REGENERATION, 900, 1, false, true, true));
        player.addStatusEffect(new StatusEffectInstance(StatusEffects.FIRE_RESISTANCE, 800, 0, false, true, true));
        player.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, 100, 1, false, true, true));
        player.networkHandler.sendPacket(new TitleFadeS2CPacket(10, 60, 20));
        player.networkHandler.sendPacket(new TitleS2CPacket(Text.empty()));
        player.networkHandler.sendPacket(new SubtitleS2CPacket(Text.literal("休伤吾主！")));
    }

    public void grantAdvancement(ServerPlayerEntity player, String key, String criterion) {
        MinecraftServer server = serverOf(player);
        if (server == null) {
            return;
        }
        AdvancementEntry advancement = server.getAdvancementLoader().get(Identifier.of("bloodsoil", key));
        if (advancement != null) {
            player.getAdvancementTracker().grantCriterion(advancement, criterion);
        }
    }

    private void recordCombat(ServerPlayerEntity player, DamageSource source) {
        Entity attacker = source.getAttacker();
        if (isHostile(attacker)) {
            lastCombatTick.put(player.getUuid(), serverOf(player).getTicks());
        } else if (attacker instanceof PersistentProjectileEntity projectile && isHostile(projectile.getOwner())) {
            lastCombatTick.put(player.getUuid(), serverOf(player).getTicks());
        }
    }

    private boolean isCombatKill(ServerPlayerEntity player, DamageSource source) {
        if (source.isOf(DamageTypes.OUT_OF_WORLD)) {
            return false;
        }
        Entity attacker = source.getAttacker();
        if (isHostile(attacker)) {
            return true;
        }
        if (attacker instanceof PersistentProjectileEntity projectile && isHostile(projectile.getOwner())) {
            return true;
        }
        if (source.isOf(DamageTypes.DRAGON_BREATH) || source.isOf(DamageTypes.WITHER)
                || source.isOf(DamageTypes.MAGIC) || source.isOf(DamageTypes.LIGHTNING_BOLT)) {
            return true;
        }
        Integer last = lastCombatTick.get(player.getUuid());
        return last != null && serverOf(player).getTicks() - last <= 100;
    }

    private boolean isHostile(Entity entity) {
        if (entity instanceof EnderDragonPart part) {
            entity = part.owner;
        }
        return entity instanceof Monster || entity instanceof PlayerEntity
                || entity instanceof EnderDragonEntity || entity instanceof WitherEntity;
    }

    private boolean isOwnLightning(ServerPlayerEntity player, DamageSource source) {
        if (!source.isOf(DamageTypes.LIGHTNING_BOLT)) {
            return false;
        }
        int ticks = serverOf(player).getTicks();
        Vec3d playerPos = player.getEntityPos();
        for (BoltRecord record : boltRecords) {
            if (record.owner().equals(player.getUuid())
                    && ticks - record.tick() <= 10
                    && record.pos().squaredDistanceTo(playerPos) <= 64.0) {
                player.setFireTicks(0);
                return true;
            }
        }
        return false;
    }

    public void registerBolt(UUID ownerUuid, Vec3d pos, int tick) {
        boltRecords.add(new BoltRecord(ownerUuid, pos, tick));
    }

    private MinecraftServer serverOf(ServerPlayerEntity player) {
        return ((ServerWorld) player.getEntityWorld()).getServer();
    }

    public static boolean hasTrueLoyalty(ItemStack stack) {
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

    public void finishSequence(TrueLoyaltySequence sequence, boolean completed) {
        if (completed && !sequence.owner().isDisconnected()) {
            ServerPlayerEntity player = sequence.owner();
            player.networkHandler.sendPacket(new TitleFadeS2CPacket(10, 50, 20));
            player.networkHandler.sendPacket(new TitleS2CPacket(Text.literal("三叉戟为你耗尽力量")));
        }
    }
}

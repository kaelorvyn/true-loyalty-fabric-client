package com.bloodsoil.trueloyaltyclient;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LightningEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.boss.WitherEntity;
import net.minecraft.entity.boss.dragon.EnderDragonEntity;
import net.minecraft.entity.mob.Monster;
import net.minecraft.entity.passive.TameableEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.TridentEntity;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

public class TrueLoyaltySequence {

    private enum Phase {
        RING, FLY, CONVERGE, HOLD, HOMING
    }

    private static final int COUNT = 128;
    private static final double START_RADIUS = 2.0;
    private static final double TOP_HEIGHT = 15.0;
    private static final double RING_Y = 1.0;
    private static final int ARC_TICKS = 44;
    private static final int CONVERGE_TICKS = 18;
    private static final int HOLD_TICKS = 8;
    private static final int RING_TICKS = 100;
    private static final double RING_TURNS = 15.0;
    private static final double RING_DAMAGE = 1.5;
    private static final int RING_DAMAGE_EVERY = 4;
    private static final double RING_BAND = 1.5;
    private static final double RING_KNOCKBACK = 0.8;
    private static final double SPIRAL_TURNS = 0.6;
    private static final double HOMING_SPEED = 3.0;
    private static final double HOMING_HIT_DISTANCE = 5.0;
    private static final int BOSS_FORCE_TICKS = 60;
    private static final int LARGE_FORCE_TICKS = 120;
    private static final double LARGE_FORCE_RANGE = 16.0;
    private static final int HOMING_MAX = 240;
    private static final double TARGET_RADIUS = 64.0;
    private static final double TARGET_VERTICAL = 8.0;
    private static final double BOSS_RADIUS = 64.0;
    private static final double BOSS_RADIUS_Y = 256.0;
    private static final double BOSS_PERCENT = 0.8;
    private static final double DAMAGE_PER_TRIDENT = 15.0;
    private static final int STRIKE_WINDOW_TICKS = 100;
    private static final int PIERCING_LEVEL = 3;
    private static final boolean LIGHTNING = true;

    private final ServerWorld world;
    private final ServerPlayerEntity owner;
    private final PlayerEntity revenge;
    private final TrueLoyaltyManager manager;
    private final List<Phantom> phantoms = new ArrayList<>();
    private final List<Phantom> pendingStrikes = new ArrayList<>();
    private final List<LivingEntity> bosses = new ArrayList<>();
    private final List<LivingEntity> others = new ArrayList<>();

    private Phase phase = Phase.RING;
    private int tick;
    private int strikeWindowStart = -1;
    private boolean targetsPending = true;
    private int homingRetry;
    private boolean done;
    private Vec3d flyBase;

    public TrueLoyaltySequence(ServerPlayerEntity owner, PlayerEntity revenge, TrueLoyaltyManager manager) {
        this.owner = owner;
        this.revenge = revenge;
        this.manager = manager;
        this.world = (ServerWorld) owner.getEntityWorld();
    }

    public ServerPlayerEntity owner() {
        return owner;
    }

    public boolean isDone() {
        return done;
    }

    public void tick() {
        if (done) {
            return;
        }
        if (owner.isDisconnected() || owner.isDead()) {
            finish(false);
            return;
        }
        if (phantoms.isEmpty() && tick > 0) {
            spawnPhantoms();
        }
        tick++;
        switch (phase) {
            case RING -> {
                ring();
                if (tick >= RING_TICKS) {
                    flyBase = owner.getEntityPos();
                    phase = Phase.FLY;
                    tick = 0;
                }
            }
            case FLY -> {
                fly();
                if (tick >= ARC_TICKS) {
                    phase = Phase.CONVERGE;
                    tick = 0;
                }
            }
            case CONVERGE -> {
                converge();
                if (tick >= CONVERGE_TICKS) {
                    phase = Phase.HOLD;
                    tick = 0;
                }
            }
            case HOLD -> {
                if (tick == 1) {
                    convergeBurst();
                }
                if (tick >= HOLD_TICKS) {
                    phase = Phase.HOMING;
                    tick = 0;
                }
            }
            case HOMING -> {
                if (targetsPending) {
                    homingRetry++;
                    assignTargets();
                    if (targetsPending && homingRetry >= 20) {
                        finish(true);
                        return;
                    }
                    return;
                }
                homing();
                if (phantoms.isEmpty()) {
                    finish(true);
                }
            }
        }
    }

    private void spawnPhantoms() {
        List<Vec3d> ring = ringDirections(COUNT);
        for (int i = 0; i < COUNT; i++) {
            Vec3d dir = ring.get(i);
            Vec3d start = owner.getEntityPos().add(0, RING_Y, 0).add(dir.multiply(START_RADIUS));
            ItemStack item = new ItemStack(Items.TRIDENT);
            RegistryEntry<Enchantment> piercing = world.getRegistryManager()
                    .getOrThrow(RegistryKeys.ENCHANTMENT)
                    .getOptional(Enchantments.PIERCING)
                    .orElseThrow();
            item.addEnchantment(piercing, PIERCING_LEVEL);
            TridentEntity trident = new TridentEntity(world, start.x, start.y, start.z, item);
            trident.setNoGravity(true);
            trident.noClip = true;
            trident.setSilent(true);
            trident.setGlowing(true);
            trident.setInvulnerable(true);
            trident.setDamage(0.0);
            trident.setVelocity(dir.multiply(1.0));
            world.spawnEntity(trident);
            phantoms.add(new Phantom(trident, start, Math.atan2(dir.z, dir.x)));
            world.spawnParticles(ParticleTypes.END_ROD, start.x, start.y, start.z, 10, 0.3, 0.3, 0.3, 0.05);
            world.spawnParticles(ParticleTypes.ELECTRIC_SPARK, start.x, start.y, start.z, 4, 0.2, 0.2, 0.2, 0.05);
        }
    }

    private void ring() {
        Vec3d center = owner.getEntityPos().add(0, RING_Y, 0);
        double angular = RING_TURNS * 2.0 * Math.PI / RING_TICKS;
        boolean damageTick = tick % RING_DAMAGE_EVERY == 0;
        List<LivingEntity> ringTargets = damageTick ? ringTargets(center) : List.of();
        for (Phantom phantom : phantoms) {
            double angle = phantom.azimuth + angular * tick;
            Vec3d pos = center.add(new Vec3d(Math.cos(angle) * START_RADIUS, 0, Math.sin(angle) * START_RADIUS));
            phantom.pos = pos;
            phantom.trident.refreshPositionAfterTeleport(pos.x, pos.y, pos.z);
            Vec3d flat = new Vec3d(pos.x - center.x, 0, pos.z - center.z);
            Vec3d outward = flat.lengthSquared() > 1.0E-6 ? flat.normalize() : new Vec3d(1, 0, 0);
            phantom.trident.setVelocity(outward.multiply(0.5));
            if (damageTick) {
                for (LivingEntity target : ringTargets) {
                    ringHit(target);
                }
            }
        }
    }

    private List<LivingEntity> ringTargets(Vec3d center) {
        List<LivingEntity> targets = new ArrayList<>();
        double reach = START_RADIUS + RING_BAND;
        Box box = new Box(center, center).expand(reach, 4.0, reach);
        for (Entity entity : world.getOtherEntities(null, box, e -> e instanceof LivingEntity)) {
            LivingEntity living = (LivingEntity) entity;
            if (living == owner || living.isDead()) {
                continue;
            }
            Vec3d d = living.getEntityPos().subtract(center);
            double horizontal = Math.sqrt(d.x * d.x + d.z * d.z);
            if (Math.abs(horizontal - START_RADIUS) <= RING_BAND) {
                targets.add(living);
            }
        }
        return targets;
    }

    private void ringHit(LivingEntity target) {
        if (target.isDead()) {
            return;
        }
        target.damage(world, world.getDamageSources().mobAttack(owner), (float) RING_DAMAGE);
        Vec3d awayVec = target.getEntityPos().subtract(owner.getEntityPos());
        Vec3d awayFlat = new Vec3d(awayVec.x, 0, awayVec.z);
        Vec3d away = (awayFlat.lengthSquared() > 1.0E-6 ? awayFlat.normalize() : new Vec3d(1, 0, 0))
                .multiply(RING_KNOCKBACK).add(0, 0.3, 0);
        target.setVelocity(away);
        Vec3d pos = target.getEntityPos();
        world.spawnParticles(ParticleTypes.CRIT, pos.x, pos.y + 1, pos.z, 4, 0.2, 0.2, 0.2, 0.05);
    }

    private void fly() {
        double[] geometry = sphereGeometry();
        double centerY = flyBase.y + geometry[0];
        double radius = geometry[1];
        double startPhi = Math.acos(Math.max(-1.0, Math.min(1.0, (RING_Y - geometry[0]) / radius)));
        Vec3d center = new Vec3d(flyBase.x, centerY, flyBase.z);
        for (Phantom phantom : phantoms) {
            double t = Math.min(1.0, tick / (double) ARC_TICKS);
            double t2 = Math.min(1.0, (tick + 1) / (double) ARC_TICKS);
            Vec3d pos = spherePoint(center, radius, startPhi, phantom.azimuth, t);
            Vec3d next = spherePoint(center, radius, startPhi, phantom.azimuth, t2);
            phantom.pos = pos;
            phantom.trident.setVelocity(next.subtract(pos));
            phantom.trident.refreshPositionAfterTeleport(pos.x, pos.y, pos.z);
            world.spawnParticles(ParticleTypes.END_ROD, pos.x, pos.y, pos.z, 8, 0.08, 0.08, 0.08, 0.02);
            if (tick % 2 == 0) {
                world.spawnParticles(ParticleTypes.ELECTRIC_SPARK, pos.x, pos.y, pos.z, 1, 0.05, 0.05, 0.05, 0.01);
            }
        }
        for (int i = 0; i < 24; i++) {
            double angle = i * 2.0 * Math.PI / 24;
            double t = 0.2 + 0.6 * (i / 24.0);
            Vec3d p = spherePoint(center, radius, startPhi, angle, t);
            world.spawnParticles(ParticleTypes.END_ROD, p.x, p.y, p.z, 1, 0.08, 0.08, 0.08, 0.01);
        }
        if (tick % 8 == 0) {
            world.playSound(null, flyBase.x, flyBase.y, flyBase.z, SoundEvents.ITEM_TRIDENT_RIPTIDE_1, SoundCategory.PLAYERS, 0.5F, 1.1F);
        }
    }

    private void converge() {
        double progress = Math.min(1.0, tick / (double) CONVERGE_TICKS);
        Vec3d converge = convergePoint();
        for (Phantom phantom : phantoms) {
            Vec3d pos = phantom.pos.add(converge.subtract(phantom.pos).multiply(progress));
            movePhantom(phantom, pos);
            world.spawnParticles(ParticleTypes.END_ROD, pos.x, pos.y, pos.z, 2, 0.05, 0.05, 0.05, 0.02);
        }
    }

    private void convergeBurst() {
        Vec3d p = convergePoint();
        world.spawnParticles(ParticleTypes.TOTEM_OF_UNDYING, p.x, p.y, p.z, 40, 0.8, 0.8, 0.8, 0.12);
        world.spawnParticles(ParticleTypes.END_ROD, p.x, p.y, p.z, 50, 1.0, 1.0, 1.0, 0.15);
        world.spawnParticles(ParticleTypes.EXPLOSION, p.x, p.y, p.z, 1, 0, 0, 0, 0);
        world.playSound(null, p.x, p.y, p.z, SoundEvents.ITEM_TRIDENT_THUNDER, SoundCategory.PLAYERS, 1.0F, 0.9F);
    }

    private void assignTargets() {
        bosses.clear();
        others.clear();
        if (revenge != null && revenge.isAlive()) {
            others.add(revenge);
        }
        Vec3d ownerPos = owner.getEntityPos();
        Box bossBox = new Box(ownerPos, ownerPos).expand(BOSS_RADIUS, BOSS_RADIUS_Y, BOSS_RADIUS);
        for (Entity entity : world.getOtherEntities(null, bossBox, e -> e instanceof LivingEntity && e.isAlive())) {
            LivingEntity living = (LivingEntity) entity;
            if (living != owner && (living instanceof EnderDragonEntity || living instanceof WitherEntity)) {
                bosses.add(living);
            }
        }
        Box mobBox = new Box(ownerPos, ownerPos).expand(TARGET_RADIUS, TARGET_VERTICAL, TARGET_RADIUS);
        for (Entity entity : world.getOtherEntities(null, mobBox, e -> e instanceof LivingEntity && e.isAlive())) {
            LivingEntity living = (LivingEntity) entity;
            if (living == owner || living instanceof PlayerEntity) {
                continue;
            }
            if (living instanceof TameableEntity tame && tame.isTamed()) {
                continue;
            }
            if (isHostile(living) && !(living instanceof EnderDragonEntity) && !(living instanceof WitherEntity)) {
                others.add(living);
            }
        }
        if (bosses.isEmpty() && others.isEmpty()) {
            return;
        }
        targetsPending = false;
        int bossSlots = bosses.isEmpty() ? 0 : (int) Math.round(phantoms.size() * BOSS_PERCENT);
        for (int i = 0; i < phantoms.size(); i++) {
            Phantom phantom = phantoms.get(i);
            LivingEntity target;
            if (i < bossSlots) {
                target = bosses.get(i % bosses.size());
            } else if (!others.isEmpty()) {
                target = others.get((i - bossSlots) % others.size());
            } else {
                target = bosses.get(i % bosses.size());
            }
            phantom.target = target;
        }
    }

    private boolean isHostile(LivingEntity entity) {
        if (entity instanceof Monster) {
            return true;
        }
        EntityType<?> type = entity.getType();
        return type == EntityType.SLIME || type == EntityType.MAGMA_CUBE || type == EntityType.SHULKER
                || type == EntityType.GUARDIAN || type == EntityType.ELDER_GUARDIAN;
    }

    private void homing() {
        List<Phantom> done = new ArrayList<>();
        for (Phantom phantom : phantoms) {
            if (phantom.pending) {
                LivingEntity target = phantom.target;
                if (target != null && target.isAlive() && target.getEntityWorld() != world) {
                    vanish(phantom);
                    done.add(phantom);
                    continue;
                }
                Vec3d eye;
                if (target != null && target.isAlive()) {
                    eye = new Vec3d(target.getX(), target.getEyeY(), target.getZ());
                } else {
                    eye = phantom.lastTarget;
                }
                if (eye == null) {
                    vanish(phantom);
                    done.add(phantom);
                    continue;
                }
                phantom.lastTarget = eye;
                homeMove(phantom, eye);
                continue;
            }
            if (phantom.life++ >= HOMING_MAX && !(phantom.target instanceof PlayerEntity)) {
                vanish(phantom);
                done.add(phantom);
                continue;
            }
            LivingEntity target = phantom.target;
            if (target != null && target.isAlive() && target.getEntityWorld() != world) {
                vanish(phantom);
                done.add(phantom);
                continue;
            }
            Vec3d eye;
            if (target != null && target.isAlive()) {
                eye = new Vec3d(target.getX(), target.getEyeY(), target.getZ());
            } else {
                eye = phantom.lastTarget;
            }
            if (eye == null) {
                vanish(phantom);
                done.add(phantom);
                continue;
            }
            boolean forceStrike = false;
            if (target != null && target.isAlive()) {
                if (isBoss(target) && phantom.life > BOSS_FORCE_TICKS) {
                    forceStrike = true;
                } else if (!isBoss(target) && phantom.life > LARGE_FORCE_TICKS
                        && phantom.pos.squaredDistanceTo(eye) < LARGE_FORCE_RANGE * LARGE_FORCE_RANGE) {
                    forceStrike = true;
                }
            }
            if (forceStrike || isInHitRange(phantom, target, eye)) {
                queueStrike(phantom, eye);
                continue;
            }
            phantom.lastTarget = eye;
            homeMove(phantom, eye);
            Vec3d pos = phantom.pos;
            world.spawnParticles(ParticleTypes.END_ROD, pos.x, pos.y, pos.z, 5, 0.06, 0.06, 0.06, 0.02);
            world.spawnParticles(ParticleTypes.CRIT, pos.x, pos.y, pos.z, 3, 0.06, 0.06, 0.06, 0.02);
        }
        drainStrikes(done);
        phantoms.removeAll(done);
    }

    private void homeMove(Phantom phantom, Vec3d eye) {
        Vec3d desired = eye.subtract(phantom.pos);
        if (desired.lengthSquared() > 1.0E-6) {
            desired = desired.normalize().multiply(HOMING_SPEED);
        }
        Vec3d velocity = phantom.velocity.multiply(0.90).add(desired.multiply(0.18));
        if (velocity.lengthSquared() > HOMING_SPEED * HOMING_SPEED) {
            velocity = velocity.normalize().multiply(HOMING_SPEED);
        }
        phantom.velocity = velocity;
        movePhantom(phantom, phantom.pos.add(velocity));
    }

    private void queueStrike(Phantom phantom, Vec3d eye) {
        if (phantom.pending) {
            return;
        }
        phantom.pending = true;
        phantom.lastTarget = eye;
        pendingStrikes.add(phantom);
    }

    private void drainStrikes(List<Phantom> done) {
        if (strikeWindowStart < 0 && !pendingStrikes.isEmpty()) {
            strikeWindowStart = tick;
        }
        if (strikeWindowStart < 0) {
            return;
        }
        int elapsed = tick - strikeWindowStart;
        int remainingTicks = Math.max(1, STRIKE_WINDOW_TICKS - elapsed);
        int perTick = Math.max(1, (int) Math.ceil(pendingStrikes.size() / (double) remainingTicks));
        for (int i = 0; i < perTick && !pendingStrikes.isEmpty(); i++) {
            Phantom phantom = pendingStrikes.remove(0);
            LivingEntity target = phantom.target;
            Vec3d eye = phantom.lastTarget != null ? phantom.lastTarget
                    : (target != null && target.isAlive()
                            ? new Vec3d(target.getX(), target.getEyeY(), target.getZ()) : null);
            if (eye != null) {
                strike(phantom, target, eye);
            } else {
                vanish(phantom);
            }
            done.add(phantom);
        }
    }

    private boolean isInHitRange(Phantom phantom, LivingEntity target, Vec3d eye) {
        if (target == null || !target.isAlive()) {
            return phantom.pos.squaredDistanceTo(eye) < HOMING_HIT_DISTANCE * HOMING_HIT_DISTANCE;
        }
        Box box = target.getBoundingBox().expand(HOMING_HIT_DISTANCE);
        if (box.contains(phantom.pos)) {
            return true;
        }
        return phantom.pos.squaredDistanceTo(eye) < HOMING_HIT_DISTANCE * HOMING_HIT_DISTANCE;
    }

    private boolean isBoss(LivingEntity target) {
        return target instanceof EnderDragonEntity || target instanceof WitherEntity;
    }

    private void strike(Phantom phantom, LivingEntity target, Vec3d eye) {
        if (target != null && target.isAlive()) {
            target.timeUntilRegen = 0;
            target.damage(world, world.getDamageSources().mobAttack(owner), (float) DAMAGE_PER_TRIDENT);
        }
        world.spawnParticles(ParticleTypes.EXPLOSION, eye.x, eye.y, eye.z, 1, 0, 0, 0, 0);
        world.spawnParticles(ParticleTypes.CRIT, eye.x, eye.y, eye.z, 40, 0.6, 0.6, 0.6, 0.2);
        world.spawnParticles(ParticleTypes.END_ROD, eye.x, eye.y, eye.z, 30, 0.8, 0.8, 0.8, 0.15);
        world.spawnParticles(ParticleTypes.TOTEM_OF_UNDYING, eye.x, eye.y, eye.z, 15, 0.5, 0.5, 0.5, 0.1);
        world.playSound(null, eye.x, eye.y, eye.z, SoundEvents.ENTITY_GENERIC_EXPLODE, SoundCategory.PLAYERS, 1.0F, 1.0F);
        world.playSound(null, eye.x, eye.y, eye.z, SoundEvents.ITEM_TRIDENT_THUNDER, SoundCategory.PLAYERS, 1.0F, 0.8F);
        if (LIGHTNING) {
            LightningEntity bolt = new LightningEntity(EntityType.LIGHTNING_BOLT, world);
            bolt.refreshPositionAfterTeleport(target == null ? eye.x : target.getX(),
                    target == null ? eye.y : target.getY(), target == null ? eye.z : target.getZ());
            manager.registerBolt(owner.getUuid(), new Vec3d(bolt.getX(), bolt.getY(), bolt.getZ()),
                    world.getServer().getTicks());
            world.spawnEntity(bolt);
        }
        vanish(phantom);
    }

    private void movePhantom(Phantom phantom, Vec3d pos) {
        phantom.trident.setVelocity(pos.subtract(phantom.pos));
        phantom.pos = pos;
        phantom.trident.refreshPositionAfterTeleport(pos.x, pos.y, pos.z);
    }

    private void vanish(Phantom phantom) {
        Vec3d pos = phantom.pos;
        world.spawnParticles(ParticleTypes.END_ROD, pos.x, pos.y, pos.z, 10, 0.4, 0.4, 0.4, 0.08);
        world.spawnParticles(ParticleTypes.TOTEM_OF_UNDYING, pos.x, pos.y, pos.z, 5, 0.3, 0.3, 0.3, 0.06);
        phantom.trident.discard();
    }

    private void finish(boolean completed) {
        if (done) {
            return;
        }
        done = true;
        for (Phantom phantom : phantoms) {
            vanish(phantom);
        }
        phantoms.clear();
        manager.finishSequence(this, completed);
    }

    private double[] sphereGeometry() {
        double topY = TOP_HEIGHT;
        double ringY = RING_Y;
        double centerY = (topY * topY - ringY * ringY - START_RADIUS * START_RADIUS)
                / (2.0 * (topY - ringY));
        return new double[] { centerY, topY - centerY };
    }

    private Vec3d convergePoint() {
        return flyBase.add(0, TOP_HEIGHT, 0);
    }

    private static Vec3d spherePoint(Vec3d center, double radius, double startPhi, double azimuth, double t) {
        double phi = startPhi * (1 - t);
        double theta = azimuth + SPIRAL_TURNS * 2.0 * Math.PI * t;
        return center.add(new Vec3d(
                radius * Math.sin(phi) * Math.cos(theta),
                radius * Math.cos(phi),
                radius * Math.sin(phi) * Math.sin(theta)));
    }

    private static List<Vec3d> ringDirections(int count) {
        List<Vec3d> points = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            double angle = i * 2.0 * Math.PI / count;
            points.add(new Vec3d(Math.cos(angle), 0, Math.sin(angle)));
        }
        return points;
    }

    private static final class Phantom {
        final TridentEntity trident;
        final double azimuth;
        Vec3d pos;
        Vec3d velocity = Vec3d.ZERO;
        LivingEntity target;
        Vec3d lastTarget;
        boolean pending;
        int life;

        Phantom(TridentEntity trident, Vec3d pos, double azimuth) {
            this.trident = trident;
            this.pos = pos;
            this.azimuth = azimuth;
        }
    }
}

package com.bloodsoil.trueloyaltyclient;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

public class TrueLoyaltyServer implements ModInitializer {

    public static TrueLoyaltyManager manager;

    @Override
    public void onInitialize() {
        manager = new TrueLoyaltyManager();
        ServerLivingEntityEvents.ALLOW_DAMAGE.register(manager::onAllowDamage);
        ServerLivingEntityEvents.ALLOW_DEATH.register(manager::onAllowDeath);
        ServerTickEvents.END_SERVER_TICK.register(manager::tick);
    }
}

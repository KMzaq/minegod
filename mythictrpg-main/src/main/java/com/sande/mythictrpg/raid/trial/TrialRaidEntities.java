package com.sande.mythictrpg.raid.trial;

import com.sande.mythictrpg.MythicTrpg;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class TrialRaidEntities {
    private static final DeferredRegister<EntityType<?>> TYPES = DeferredRegister.create(
            Registries.ENTITY_TYPE, MythicTrpg.MOD_ID);
    public static final DeferredHolder<EntityType<?>, EntityType<SealedGuardianEntity>> SEALED_GUARDIAN =
            TYPES.register("sealed_guardian", () -> EntityType.Builder.of(SealedGuardianEntity::new, MobCategory.MONSTER)
                    .sized(2.0F, 3.4F).clientTrackingRange(16).updateInterval(2).fireImmune()
                    .build("sealed_guardian"));

    private TrialRaidEntities() { }

    public static void register(IEventBus modBus) {
        TYPES.register(modBus);
        modBus.addListener(TrialRaidEntities::attributes);
    }

    private static void attributes(EntityAttributeCreationEvent event) {
        event.put(SEALED_GUARDIAN.get(), SealedGuardianEntity.createAttributes().build());
    }
}

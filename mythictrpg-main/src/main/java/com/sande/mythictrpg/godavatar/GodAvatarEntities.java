package com.sande.mythictrpg.godavatar;

import com.sande.mythictrpg.MythicTrpg;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class GodAvatarEntities {
    private static final DeferredRegister<EntityType<?>> TYPES = DeferredRegister.create(
            Registries.ENTITY_TYPE, MythicTrpg.MOD_ID);
    public static final DeferredHolder<EntityType<?>, EntityType<GodAvatarEntity>> GOD_AVATAR =
            TYPES.register("god_avatar", () -> EntityType.Builder.of(GodAvatarEntity::new, MobCategory.CREATURE)
                    .sized(0.6F, 1.8F).clientTrackingRange(10).build("god_avatar"));

    private GodAvatarEntities() {}

    public static void register(IEventBus modBus) {
        TYPES.register(modBus);
        modBus.addListener(GodAvatarEntities::attributes);
    }

    private static void attributes(EntityAttributeCreationEvent event) {
        event.put(GOD_AVATAR.get(), GodAvatarEntity.createAttributes().build());
    }
}

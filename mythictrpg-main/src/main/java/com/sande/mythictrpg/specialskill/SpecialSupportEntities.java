package com.sande.mythictrpg.specialskill;

import com.sande.mythictrpg.MythicTrpg;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.*;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.*;

public final class SpecialSupportEntities {
    private static final DeferredRegister<EntityType<?>> TYPES=DeferredRegister.create(Registries.ENTITY_TYPE,MythicTrpg.MOD_ID);
    public static final DeferredHolder<EntityType<?>,EntityType<SupportAvatarEntity>> SUPPORT_AVATAR=TYPES.register("support_avatar",()->
            EntityType.Builder.of(SupportAvatarEntity::new,MobCategory.MISC).sized(.6F,1.8F).clientTrackingRange(10).noSave().noSummon().build("support_avatar"));
    private SpecialSupportEntities() { }
    public static void register(IEventBus bus) {
        TYPES.register(bus); bus.addListener((net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent event)->
                event.put(SUPPORT_AVATAR.get(),SupportAvatarEntity.attributes().build()));
    }
}

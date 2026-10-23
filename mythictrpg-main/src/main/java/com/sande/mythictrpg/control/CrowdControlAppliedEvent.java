package com.sande.mythictrpg.control;

import net.minecraft.world.entity.LivingEntity;
import net.neoforged.bus.api.Event;

/** Posted after a server-owned source is stored; accessory cleansing may consume this source. */
public final class CrowdControlAppliedEvent extends Event {
    private final LivingEntity entity;
    private final boolean dispellable, mechanic;
    public CrowdControlAppliedEvent(LivingEntity entity, boolean dispellable, boolean mechanic) {
        this.entity=entity; this.dispellable=dispellable; this.mechanic=mechanic;
    }
    public LivingEntity entity() { return entity; }
    public boolean dispellable() { return dispellable; }
    public boolean mechanic() { return mechanic; }
}

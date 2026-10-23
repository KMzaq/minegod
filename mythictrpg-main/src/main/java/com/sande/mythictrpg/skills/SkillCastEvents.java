package com.sande.mythictrpg.skills;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.bus.api.Event;
import java.util.Objects;
import java.util.UUID;

/** Observation hooks for actual paid casts; no listener can turn a rejected input into a cast. */
public final class SkillCastEvents {
    private SkillCastEvents() { }
    public enum Source { WEAPON, LEARNED, SPECIAL }
    public record Context(UUID castId,ServerPlayer player,Source source,ResourceLocation skillId,int manaCost,int baseCooldownTicks,boolean offensive) {
        public Context(UUID castId,ServerPlayer player,Source source,ResourceLocation skillId,int manaCost,int baseCooldownTicks) {
            this(castId,player,source,skillId,manaCost,baseCooldownTicks,true);
        }
        public Context { Objects.requireNonNull(castId);Objects.requireNonNull(player);Objects.requireNonNull(source);Objects.requireNonNull(skillId); }
    }
    public static class Start extends Event {
        private final Context context;
        public Start(Context context){this.context=Objects.requireNonNull(context);}
        public Context context(){return context;}
    }
    public static class Hit extends Event {
        private final Context context;private final LivingEntity target;
        public Hit(Context context,LivingEntity target){this.context=Objects.requireNonNull(context);this.target=Objects.requireNonNull(target);}
        public Context context(){return context;}public LivingEntity target(){return target;}
    }
    public static class Finished extends Event {
        private final Context context;private final int hitCount;private final boolean cancelled;
        public Finished(Context context,int hitCount,boolean cancelled){this.context=Objects.requireNonNull(context);this.hitCount=hitCount;this.cancelled=cancelled;}
        public Context context(){return context;}public int hitCount(){return hitCount;}public boolean cancelled(){return cancelled;}
    }
}

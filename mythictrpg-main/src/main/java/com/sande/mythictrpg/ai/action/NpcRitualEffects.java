package com.sande.mythictrpg.ai.action;

import com.sande.mythictrpg.godavatar.GodAvatarEntity;
import com.sande.mythictrpg.godavatar.activity.NpcActivityAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;

/** NPC-local authored sound/particle ritual only. Never grants player blessings/rewards or impersonates a session. */
public final class NpcRitualEffects {
    private NpcRitualEffects() { }
    public static boolean available(GodAvatarEntity avatar, BlockPos site, ResourceLocation templateId) {
        if (!(avatar.level() instanceof ServerLevel level) || !level.getServer().isSameThread()
                || !avatar.hasAuthoritativeBinding() || !level.hasChunkAt(site)
                || avatar.distanceToSqr(site.getX()+0.5,site.getY()+0.5,site.getZ()+0.5)>25
                || !NpcActivityAccess.canUse(avatar,site)) return false;
        var raw = avatar.godId().flatMap(god -> AiActionTemplateManager.INSTANCE.find(templateId, AiActionTypes.WORLD_INTERACTION, god)).orElse(null);
        if (!(raw instanceof WorldInteractionTemplate template) || avatar.godId().filter(template.godId()::equals).isEmpty()) return false;
        if(!Double.isFinite(template.spread()) || !Double.isFinite(template.speed())
                || !Float.isFinite(template.volume()) || !Float.isFinite(template.pitch()))return false;
        return template.eventKind()==WorldInteractionTemplate.EventKind.SOUND
                ? BuiltInRegistries.SOUND_EVENT.containsKey(template.eventId())
                : BuiltInRegistries.PARTICLE_TYPE.get(template.eventId()) instanceof SimpleParticleType;
    }
    public static boolean execute(GodAvatarEntity avatar, BlockPos site, ResourceLocation templateId) {
        if (!available(avatar,site,templateId)) return false;
        var template=(WorldInteractionTemplate)AiActionTemplateManager.INSTANCE.find(templateId, AiActionTypes.WORLD_INTERACTION, avatar.godId().orElseThrow()).orElseThrow();
        var level=(ServerLevel)avatar.level();
        if(template.eventKind()==WorldInteractionTemplate.EventKind.SOUND) level.playSound(null,site,
                BuiltInRegistries.SOUND_EVENT.get(template.eventId()),SoundSource.NEUTRAL,template.volume(),template.pitch());
        else level.sendParticles((SimpleParticleType)BuiltInRegistries.PARTICLE_TYPE.get(template.eventId()),
                site.getX()+0.5,site.getY()+1,site.getZ()+0.5,template.count(),template.spread(),template.spread(),template.spread(),template.speed());
        return true;
    }
}

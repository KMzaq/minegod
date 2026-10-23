package com.sande.mythictrpg.control;

import com.google.gson.JsonParser;
import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.godavatar.GodAvatarEntity;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.*;
import net.minecraft.server.packs.resources.*;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import java.util.*;

/** Optional authored normal-CC immunity by existing God ID or registered entity type. */
@EventBusSubscriber(modid=MythicTrpg.MOD_ID)
public final class CrowdControlImmunities extends SimplePreparableReloadListener<CrowdControlImmunities.Snapshot> {
    public record Snapshot(Set<ResourceLocation> godIds,Set<ResourceLocation> entityTypes,boolean valid) {
        public Snapshot { godIds=Set.copyOf(godIds);entityTypes=Set.copyOf(entityTypes); }
    }
    public static final CrowdControlImmunities INSTANCE=new CrowdControlImmunities();
    private volatile Snapshot snapshot=new Snapshot(Set.of(),Set.of(),true);
    private CrowdControlImmunities() { }
    public boolean immune(LivingEntity entity) {
        var view=snapshot;return !view.valid()||view.entityTypes().contains(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()))
                ||entity instanceof GodAvatarEntity god&&god.godId().filter(view.godIds()::contains).isPresent();
    }
    @SubscribeEvent public static void reload(AddReloadListenerEvent event) { event.addListener(INSTANCE); }
    @Override protected Snapshot prepare(ResourceManager resources,ProfilerFiller profiler) {
        try {
            var files=FileToIdConverter.json("mythictrpg/control_immunities").listMatchingResources(resources);
            if(files.size()>64)throw new IllegalArgumentException("Too many control immunity documents");
            Set<ResourceLocation> gods=new HashSet<>(),entities=new HashSet<>();
            for(var entry:files.values())try(var reader=entry.openAsReader()) {
                StringBuilder text=new StringBuilder();char[] buffer=new char[1024];int read;
                while((read=reader.read(buffer))!=-1) { if(text.length()+read>16384)throw new IllegalArgumentException("Immunity file too large");text.append(buffer,0,read); }
                var json=JsonParser.parseString(text.toString()).getAsJsonObject();
                if(!json.keySet().equals(Set.of("schemaVersion","godIds","entityTypes"))||json.get("schemaVersion").getAsInt()!=1)
                    throw new IllegalArgumentException("Invalid control immunity fields");
                for(var id:json.getAsJsonArray("godIds"))gods.add(ResourceLocation.parse(id.getAsString()));
                for(var id:json.getAsJsonArray("entityTypes")) {
                    var type=ResourceLocation.parse(id.getAsString());
                    if(!BuiltInRegistries.ENTITY_TYPE.containsKey(type))throw new IllegalArgumentException("Unknown immune entity type");entities.add(type);
                }
                if(gods.size()+entities.size()>4096)throw new IllegalArgumentException("Too many immunity entries");
            }
            return new Snapshot(gods,entities,true);
        }catch(Exception error) { MythicTrpg.LOGGER.error("Normal crowd control disabled because immunity definitions are invalid",error);return new Snapshot(Set.of(),Set.of(),false); }
    }
    @Override protected void apply(Snapshot loaded,ResourceManager resources,ProfilerFiller profiler) { snapshot=loaded; }
}

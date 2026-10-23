package com.sande.mythictrpg.specialskill;

import com.google.gson.JsonParser;
import com.sande.mythictrpg.MythicTrpg;
import net.minecraft.resources.*;
import net.minecraft.server.packs.resources.*;
import net.minecraft.util.profiling.ProfilerFiller;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import java.util.*;

@EventBusSubscriber(modid=MythicTrpg.MOD_ID)
public final class SpecialSkillDefinitions extends SimplePreparableReloadListener<Map<ResourceLocation,SpecialSkillDefinition>> {
    public static final SpecialSkillDefinitions INSTANCE=new SpecialSkillDefinitions();
    private volatile Map<ResourceLocation,SpecialSkillDefinition> snapshot=Map.of();
    private SpecialSkillDefinitions() { }
    public Map<ResourceLocation,SpecialSkillDefinition> snapshot() { return snapshot; }
    public Optional<SpecialSkillDefinition> find(ResourceLocation id) { return Optional.ofNullable(snapshot.get(id)); }
    @SubscribeEvent public static void reload(AddReloadListenerEvent event) { event.addListener(INSTANCE); }
    @Override protected Map<ResourceLocation,SpecialSkillDefinition> prepare(ResourceManager resources,ProfilerFiller profiler) {
        var converter=FileToIdConverter.json("mythictrpg/special_skills");
        try {
            var files=converter.listMatchingResources(resources); if(files.size()>1024)throw new IllegalArgumentException("Too many special skills");
            Map<ResourceLocation,SpecialSkillDefinition> definitions=new HashMap<>();
            for(var entry:files.entrySet())try(var reader=entry.getValue().openAsReader()) {
                StringBuilder text=new StringBuilder(); char[] buffer=new char[2048]; int count;
                while((count=reader.read(buffer))!=-1) { if(text.length()+count>64000)throw new IllegalArgumentException("Special skill document too large"); text.append(buffer,0,count); }
                var id=converter.fileToId(entry.getKey()); definitions.put(id,SpecialSkillDefinition.parse(id,JsonParser.parseString(text.toString()).getAsJsonObject()));
            }
            return Map.copyOf(definitions);
        }catch(Exception error) { MythicTrpg.LOGGER.error("Special skills disabled because a definition is invalid",error); return Map.of(); }
    }
    @Override protected void apply(Map<ResourceLocation,SpecialSkillDefinition> loaded,ResourceManager resources,ProfilerFiller profiler) { snapshot=Map.copyOf(loaded); }
}

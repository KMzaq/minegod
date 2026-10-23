package com.sande.mythictrpg.skills;

import com.google.gson.JsonParser;
import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.weaponskill.*;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.*;
import net.minecraft.util.profiling.ProfilerFiller;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import java.util.*;

@EventBusSubscriber(modid=MythicTrpg.MOD_ID)
public final class LearnedSkillDefinitions extends SimplePreparableReloadListener<Map<ResourceLocation,LearnedSkillDefinition>> {
    public static final LearnedSkillDefinitions INSTANCE=new LearnedSkillDefinitions();
    private volatile Map<ResourceLocation,LearnedSkillDefinition> snapshot=Map.of();
    private LearnedSkillDefinitions() { }
    public Optional<LearnedSkillDefinition> find(ResourceLocation id){return Optional.ofNullable(snapshot.get(id));}
    public Map<ResourceLocation,LearnedSkillDefinition> all(){return snapshot;}
    @SubscribeEvent public static void reload(AddReloadListenerEvent event){event.addListener(INSTANCE);}
    @Override protected Map<ResourceLocation,LearnedSkillDefinition> prepare(ResourceManager resources,ProfilerFiller profiler) {
        try {
            var converter=FileToIdConverter.json("mythictrpg/learned_skills");var files=converter.listMatchingResources(resources);
            if(files.size()>1024)throw new IllegalArgumentException("Learned skill capacity");
            Map<ResourceLocation,LearnedSkillDefinition> definitions=new HashMap<>();
            for(var entry:files.entrySet())try(var reader=entry.getValue().openAsReader()) {
                StringBuilder text=new StringBuilder();char[] buffer=new char[2048];int count;
                while((count=reader.read(buffer))!=-1){if(text.length()+count>16384)throw new IllegalArgumentException("Skill definition too large");text.append(buffer,0,count);}
                var id=converter.fileToId(entry.getKey());var skill=LearnedSkillDefinition.parse(id,JsonParser.parseString(text.toString()).getAsJsonObject());
                WeaponSkillDefinitions.validateEffect(new WeaponSkillDefinition(id,skill.name(),skill.manaCost(),skill.cooldownTicks(),skill.action()));
                definitions.put(id,skill);
            }
            return Map.copyOf(definitions);
        } catch(Exception failure){MythicTrpg.LOGGER.error("Learned skills disabled: invalid definitions",failure);return Map.of();}
    }
    @Override protected void apply(Map<ResourceLocation,LearnedSkillDefinition> definitions,ResourceManager resources,ProfilerFiller profiler){snapshot=Map.copyOf(definitions);}
}

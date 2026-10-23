package com.sande.mythictrpg.accessory;

import com.google.gson.JsonParser;
import com.sande.mythictrpg.MythicTrpg;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.*;
import net.minecraft.server.packs.resources.*;
import net.minecraft.util.profiling.ProfilerFiller;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import java.util.*;

@EventBusSubscriber(modid=MythicTrpg.MOD_ID)
public final class AccessoryDefinitions extends SimplePreparableReloadListener<Map<ResourceLocation,AccessoryDefinition>> {
    public static final AccessoryDefinitions INSTANCE=new AccessoryDefinitions();
    private volatile Map<ResourceLocation,AccessoryDefinition> snapshot=Map.of();
    private AccessoryDefinitions(){}
    public Optional<AccessoryDefinition> find(ResourceLocation id){return Optional.ofNullable(snapshot.get(id));}
    public Map<ResourceLocation,AccessoryDefinition> snapshot(){return snapshot;}
    @SubscribeEvent public static void reload(AddReloadListenerEvent event){event.addListener(INSTANCE);}
    @Override protected Map<ResourceLocation,AccessoryDefinition> prepare(ResourceManager resources,ProfilerFiller profiler){
        var files=FileToIdConverter.json("mythictrpg/accessories");
        try {
            var entries=files.listMatchingResources(resources);if(entries.size()>1024)throw new IllegalArgumentException("Too many accessories");
            Map<ResourceLocation,AccessoryDefinition> result=new HashMap<>();
            for(var entry:entries.entrySet())try(var reader=entry.getValue().openAsReader()){
                StringBuilder text=new StringBuilder();char[] buffer=new char[2048];int n;
                while((n=reader.read(buffer))!=-1){if(text.length()+n>32768)throw new IllegalArgumentException("Accessory document size");text.append(buffer,0,n);}
                var id=files.fileToId(entry.getKey());var definition=AccessoryDefinition.parse(id,JsonParser.parseString(text.toString()).getAsJsonObject());
                if(!BuiltInRegistries.ITEM.containsKey(definition.itemId()))throw new IllegalArgumentException("Unknown accessory display item");
                result.put(id,definition);
            }
            return Map.copyOf(result);
        }catch(Exception invalid){MythicTrpg.LOGGER.error("Accessory definitions disabled; invalid datapack",invalid);return Map.of();}
    }
    @Override protected void apply(Map<ResourceLocation,AccessoryDefinition> prepared,ResourceManager resources,ProfilerFiller profiler){snapshot=Map.copyOf(prepared);}
}

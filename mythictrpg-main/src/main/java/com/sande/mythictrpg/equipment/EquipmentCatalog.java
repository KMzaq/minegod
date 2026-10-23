package com.sande.mythictrpg.equipment;

import com.google.gson.JsonParser;
import com.sande.mythictrpg.power.CombatPowerPolicyManager;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.item.SwordItem;
import net.neoforged.fml.ModList;

import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Definitions and derived power rules are always read from the same atomic manager snapshot. */
public final class EquipmentCatalog {
    public static final int MAX_DEFINITIONS=1024;
    private static final int MAX_FILE_CHARS=32_768;
    private EquipmentCatalog() { }
    public static Optional<EquipmentDefinition> find(ResourceLocation id){return Optional.ofNullable(CombatPowerPolicyManager.INSTANCE.current().equipmentDefinitions().get(id));}
    public static Collection<EquipmentDefinition> all(){return List.copyOf(CombatPowerPolicyManager.INSTANCE.current().equipmentDefinitions().values());}

    /** Called only while the combat-policy manager prepares its complete replacement snapshot. */
    public static Map<ResourceLocation,EquipmentDefinition> load(ResourceManager resources) throws IOException {
        var converter=FileToIdConverter.json("mythictrpg/equipment");var files=converter.listMatchingResources(resources);
        if(files.size()>MAX_DEFINITIONS)throw new IllegalArgumentException("Too many equipment definitions");
        Map<ResourceLocation,EquipmentDefinition> definitions=new LinkedHashMap<>();
        for(var entry:files.entrySet().stream().sorted(Comparator.comparing(e->converter.fileToId(e.getKey()).toString())).toList()) {
            var id=converter.fileToId(entry.getKey());
            try(var reader=entry.getValue().openAsReader()) {
                var text=new StringBuilder();char[] buffer=new char[4096];int count;
                while((count=reader.read(buffer))!=-1){if(text.length()+count>MAX_FILE_CHARS)throw new IllegalArgumentException("Equipment file too large: "+id);text.append(buffer,0,count);}
                var json=JsonParser.parseString(text.toString()).getAsJsonObject();
                // Always parse the complete shape first: a missing optional mod never conceals malformed definitions.
                var definition=EquipmentDefinition.parse(id,json);
                if(EquipmentDefinition.requiredMods(json).stream().anyMatch(mod->!ModList.get().isLoaded(mod)))continue;
                if(!BuiltInRegistries.ITEM.containsKey(definition.reward().itemId())
                        ||!(BuiltInRegistries.ITEM.get(definition.reward().itemId()) instanceof SwordItem))
                    throw new IllegalArgumentException("Weapon equipment appearance must be a registered SwordItem: "+definition.reward().itemId());
                SimplySwordAppearances.validateResources(resources,definition.reward().itemId());
                if(definitions.putIfAbsent(id,definition)!=null)throw new IllegalArgumentException("Duplicate equipment definition: "+id);
            }
        }
        return Collections.unmodifiableMap(definitions);
    }
}

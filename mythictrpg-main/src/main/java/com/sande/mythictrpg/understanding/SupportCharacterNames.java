package com.sande.mythictrpg.understanding;

import com.google.gson.JsonParser;
import com.sande.mythictrpg.data.god.*;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Exact game-authored labels only. Dedicated-server English rendering must not erase the bundled Korean name. */
public final class SupportCharacterNames {
    private static final Map<String,String> KOREAN=loadKorean();
    private SupportCharacterNames() { }
    private static Map<String,String> loadKorean() {
        try(var input=SupportCharacterNames.class.getResourceAsStream("/assets/mythictrpg/lang/ko_kr.json")) {
            if(input==null)return Map.of();
            byte[] bytes=input.readNBytes(1_048_577);if(bytes.length>1_048_576)return Map.of();
            var result=new HashMap<String,String>();
            JsonParser.parseString(new String(bytes,StandardCharsets.UTF_8)).getAsJsonObject().entrySet().forEach(e->{
                if(e.getValue().isJsonPrimitive()&&e.getValue().getAsJsonPrimitive().isString())result.put(e.getKey(),e.getValue().getAsString());
            });
            return Map.copyOf(result);
        }catch(java.io.IOException|RuntimeException unavailable){return Map.of();}
    }
    public static String preferred(GodDefinition definition) {
        var name=definition.displayName();
        if(name.getContents() instanceof TranslatableContents translated&&translated.getArgs().length==0&&name.getSiblings().isEmpty())
            return KOREAN.getOrDefault(translated.getKey(),name.getString());
        return name.getString();
    }
    public static Set<String> names(GodDefinition definition) {
        var result=new HashSet<String>();result.add(definition.displayName().getString());result.add(preferred(definition));
        result.removeIf(n->n.isBlank()||n.length()>128);return Set.copyOf(result);
    }
    public static List<String> candidates(String currentText) {
        return GodDefinitionManager.INSTANCE.definitions().values().stream().flatMap(d->names(d).stream())
                .filter(currentText::contains).distinct().sorted().limit(16).toList();
    }
    public static List<ResourceLocation> resolve(String exactName) {
        return GodDefinitionManager.INSTANCE.definitions().entrySet().stream().filter(e->names(e.getValue()).contains(exactName))
                .map(Map.Entry::getKey).sorted(Comparator.comparing(ResourceLocation::toString)).toList();
    }
}

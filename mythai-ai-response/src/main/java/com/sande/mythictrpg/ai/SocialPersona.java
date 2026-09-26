package com.sande.mythictrpg.ai;

import com.google.gson.Gson;
import net.minecraft.resources.ResourceLocation;
import java.util.*;

/** Reuses the existing registry bridge. No lore, quest, hidden knowledge, other god or player data is exposed. */
public final class SocialPersona {
    public record Snapshot(String god,String text,long generation) { }
    public static Snapshot read(String god) {
        if(god.isEmpty())return new Snapshot("","",0);
        var content=new AiTestContentRegistryBridge().load(ResourceLocation.parse(god),"R_NEUTRAL",List.of(ResourceLocation.parse(god)));
        var p=content.profile();
        String text=new Gson().toJson(Map.of("personality",p.personality(),"values",p.values(),"restrictions",p.restrictions()));
        if(text.length()>6000)throw new IllegalArgumentException("social persona budget");
        return new Snapshot(god,text,content.generation());
    }
    public static boolean current(Snapshot snapshot){return snapshot.equals(read(snapshot.god()));}
    private SocialPersona() { }
}

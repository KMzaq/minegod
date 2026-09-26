package com.sande.mythictrpg.rumor;

import com.google.gson.Gson;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Authored routes, not inferred mythology. No configuration creates or respawns an entity. */
public record CourierSettings(int schemaVersion, boolean enabled, boolean automaticSpawn, boolean automaticRespawn,
        int deliveriesPerTick, Set<String> courierTypes, List<Rule> rules) {
    public enum Source { GAME_EVENT, DISCLOSED_DIALOGUE }
    public enum Publication { AUTHORED, CANDIDATE }
    public enum Reception { IGNORE, CAUTIOUS, INTERESTED }
    public record Rule(String id, String eventType, Source source, String dimension, double radius,
            int cooldownTicks, int maximumAgeTicks, Map<String,Reception> receivers,
            Publication publication, String authoredText, String epithet) {
        public Rule {
            id=identifier(id);eventType=identifier(eventType);dimension=identifier(dimension);
            Objects.requireNonNull(source);Objects.requireNonNull(publication);receivers=Map.copyOf(receivers);
            if(!Double.isFinite(radius)||radius<1||radius>64||cooldownTicks<1||cooldownTicks>72000||maximumAgeTicks<1||maximumAgeTicks>72000
                    ||receivers.isEmpty()||receivers.size()>32)throw new IllegalArgumentException("courier rule budget");
            receivers.forEach((god,reception)->{identifier(god);Objects.requireNonNull(reception);});
            Objects.requireNonNull(authoredText);Objects.requireNonNull(epithet);
            if(authoredText.length()>300||epithet.length()>60||publication==Publication.AUTHORED&&(authoredText.isBlank()||source!=Source.GAME_EVENT))
                throw new IllegalArgumentException("dialogue interpretation requires a candidate, not an automatic fixed allegation");
        }
        public String fingerprint(){var sorted=new TreeMap<String,String>();receivers.forEach((g,r)->sorted.put(g,r.name()));
            return hash(new Gson().toJson(List.of(id,eventType,source.name(),dimension,radius,cooldownTicks,maximumAgeTicks,sorted,publication.name(),authoredText,epithet)));}
    }
    public static final CourierSettings OFF=new CourierSettings(1,false,false,false,1,Set.of(),List.of());
    public CourierSettings {
        courierTypes=Set.copyOf(courierTypes);rules=List.copyOf(rules);
        if(schemaVersion!=1||automaticSpawn||automaticRespawn||deliveriesPerTick<1||deliveriesPerTick>32||courierTypes.size()>32||rules.size()>64)
            throw new IllegalArgumentException("courier settings: automatic lifecycle is unsupported");
        courierTypes.forEach(CourierSettings::identifier);
        if(rules.stream().map(Rule::id).distinct().count()!=rules.size()||enabled&&(courierTypes.isEmpty()||rules.isEmpty()))throw new IllegalArgumentException("explicit types and unique rules required");
    }
    public static CourierSettings load(Path path){try{
        if(!Files.isRegularFile(path)||Files.size(path)>65536)return OFF;
        var value=new Gson().fromJson(Files.readString(path),CourierSettings.class);return value==null?OFF:value;
    }catch(Exception invalid){return OFF;}}
    static String identifier(String id){if(id==null||id.length()>160||!id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))throw new IllegalArgumentException("game ID");return id;}
    static String hash(String text){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}catch(Exception impossible){throw new IllegalStateException(impossible);}}
}

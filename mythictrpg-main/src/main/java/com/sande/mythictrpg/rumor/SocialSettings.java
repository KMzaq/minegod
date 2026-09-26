package com.sande.mythictrpg.rumor;

import com.google.gson.Gson;
import com.sande.mythictrpg.gameplay.ledger.ActionRecord;
import com.sande.mythictrpg.gameplay.watch.WatchContract.Area;
import java.nio.file.*;
import java.util.*;

/** Technical opt-in and explicit public game-content routes. No default mythology or numerical penalties. */
public record SocialSettings(int schemaVersion, boolean enabled, boolean ordinaryDialogueObservable,
        Set<String> privateGods, List<Area> blockedAreas, List<EventRoute> eventRoutes,
        Map<String,String> publicationGuidance, Map<String,String> recoveryGuidance) {
    public static final SocialSettings OFF = new SocialSettings(1,false,true,Set.of(),List.of(),List.of(),Map.of(),Map.of());
    public record EventRoute(ActionRecord.Type type, String targetId, String result, String eventType, String publicDescription) {
        public EventRoute {
            if (!Set.of(ActionRecord.Type.BATTLE_RESULT, ActionRecord.Type.QUEST_TRANSITION, ActionRecord.Type.ADVANCEMENT_EARNED).contains(type))
                throw new IllegalArgumentException("important results only");
            CourierSettings.identifier(targetId); CourierSettings.identifier(eventType);
            if (result == null || result.isBlank() || result.length() > 100 || publicDescription == null || publicDescription.isBlank()
                    || publicDescription.length() > 300) throw new IllegalArgumentException("explicit public event description");
        }
        public boolean matches(ActionRecord.Draft event) {
            String actual = switch (type) {
                case BATTLE_RESULT -> event.payload().get("battle_result");
                case QUEST_TRANSITION -> event.payload().get("transition");
                default -> event.outcome();
            };
            return type == event.type() && targetId.equals(event.subject().typeId()) && result.equals(actual);
        }
    }
    public SocialSettings {
        privateGods = Set.copyOf(privateGods); blockedAreas = List.copyOf(blockedAreas); eventRoutes = List.copyOf(eventRoutes);
        publicationGuidance = Map.copyOf(publicationGuidance); recoveryGuidance = Map.copyOf(recoveryGuidance);
        if (schemaVersion != 1 || privateGods.size() > 128 || blockedAreas.size() > 128 || eventRoutes.size() > 128
                || publicationGuidance.size() > 64 || recoveryGuidance.size() > 128) throw new IllegalArgumentException("social settings budget");
        privateGods.forEach(CourierSettings::identifier);
        for (var map : List.of(publicationGuidance,recoveryGuidance)) map.forEach((k,v) -> {
            CourierSettings.identifier(k); if (v.isBlank() || v.length() > 1200) throw new IllegalArgumentException("guidance budget");
        });
        if (eventRoutes.stream().map(r -> r.type()+"/"+r.targetId()+"/"+r.result()).distinct().count() != eventRoutes.size())
            throw new IllegalArgumentException("duplicate public event route");
    }
    public static boolean inside(Area area, String dimension, int x, int y, int z) {
        return area.dimensionId().equals(dimension) && x >= area.minX() && x <= area.maxX()
                && y >= area.minY() && y <= area.maxY() && z >= area.minZ() && z <= area.maxZ();
    }
    public boolean blocked(String dimension,int x,int y,int z) { return blockedAreas.stream().anyMatch(a -> inside(a,dimension,x,y,z)); }
    public static SocialSettings load(Path path) {
        if (!Files.exists(path)) return OFF;
        try { if (Files.size(path) > 262144) throw new IllegalArgumentException("settings budget");
            return Objects.requireNonNull(new Gson().fromJson(Files.readString(path),SocialSettings.class));
        } catch (Exception invalid) { throw new IllegalArgumentException("Invalid social-rumor settings",invalid); }
    }
}

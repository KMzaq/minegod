package com.sande.mythai.response.memory;

import com.google.gson.Gson;
import com.sande.mythictrpg.ai.experiencecontract.ExperienceView;
import java.util.*;

/** Retrieval/prompt data only. No new LLM call, world writer, narrative inference, or scripted NPC response. */
public final class ExperienceMemory {
    private static final Gson JSON = new Gson();
    private static final java.util.regex.Pattern WHEAT = java.util.regex.Pattern.compile(
            "(?<![가-힣a-z])밀(?=$|[^가-힣a-z]|을|은|이|도|밭|수확|씨앗)|\\bwheat\\b");
    public record Selection(List<ExperienceView.Event> events, boolean recalling) {
        public static final Selection EMPTY = new Selection(List.of(), false);
        public Selection { events = List.copyOf(events); if (events.size() > 1) throw new IllegalArgumentException("trial evidence budget"); }
        public Set<UUID> ids() { return events.stream().map(ExperienceView.Event::observationId).collect(java.util.stream.Collectors.toUnmodifiableSet()); }
    }
    private ExperienceMemory() {}
    public static Selection select(ExperienceView view, String query, List<String> recentPlayers) {
        if (!view.available()) return Selection.EMPTY;
        String text = query.toLowerCase(Locale.ROOT);
        String joined = text + " " + String.join(" ", MemoryRecallPolicy.contextQueries(query, recentPlayers));
        boolean recall = text.matches(".*(기억|봤|보았|뭘 했|뭐 했|뭘했|뭐했|한 일|했었|remember|saw|did i).*");
        boolean wheat = WHEAT.matcher(joined).find();
        boolean crop = wheat || joined.matches(".*(수확|작물|농사|밭|당근|감자|사탕무|네더와트|코코아|harvest|crop|carrot|potato|beetroot|cocoa|nether.?wart).*");
        boolean battle=joined.matches(".*(전투|네임드|레이드|싸웠|싸운|처치|잡았|battle|raid|killed).*");
        boolean mining=joined.matches(".*(채굴|캤|캐던|캔 |벌목|mining|mined).*");
        if(battle||mining)for(var event:view.events()) {
            if(battle && event.actionType().equals("BATTLE_RESULT") || mining && event.actionType().equals("OBSERVED_ACTIVITY_SUMMARY")
                    &&event.outcome().contains("ACTIVITY=BLOCK_REMOVED"))return new Selection(List.of(event),recall);
        }
        if (!crop && !(recall && joined.matches(".*(뭘했|뭐했|뭘 했|뭐 했|한 일|행동|하고 있었|날 봤|did i|saw me).*"))) return Selection.EMPTY;
        // Exact typed evidence first. No embedding model, all-action inference, or unsupported outcome promotion.
        for (var event : view.events()) {
            String type = event.subjectType();
            if(crop && !(event.actionType().equals("MATURE_CROP_REMOVED")
                    || event.actionType().equals("OBSERVED_ACTIVITY_SUMMARY")
                    && event.outcome().contains("ACTIVITY=MATURE_CROP_REMOVED")))continue;
            if (joined.matches(".*(당근|carrot).*") && !type.endsWith(":carrots")) continue;
            if (joined.matches(".*(감자|potato).*") && !type.endsWith(":potatoes")) continue;
            if (joined.matches(".*(사탕무|beetroot).*") && !type.endsWith(":beetroots")) continue;
            if (wheat && !type.endsWith(":wheat")) continue;
            if (joined.matches(".*(코코아|cocoa).*") && !type.endsWith(":cocoa")) continue;
            if (joined.matches(".*(네더와트|nether.?wart).*") && !type.endsWith(":nether_wart")) continue;
            return new Selection(List.of(event), recall);
        }
        return Selection.EMPTY;
    }
    public static String prompt(ExperienceView view, Selection selected) {
        if (!view.available()) return "";
        StringBuilder text = new StringBuilder();
        if (!selected.events().isEmpty()) {
            var rows = new ArrayList<Map<String, String>>();
            for (var e : selected.events()) rows.add(Map.of("source", "DIRECT_OBSERVATION", "event", e.actionType(),
                    "subject", subjectName(e.subjectType()), "outcome", e.outcome(), "time", e.gameTime()));
            text.append("\n[OBSERVED_EXPERIENCE_DATA]\n").append(JSON.toJson(rows)).append("\n")
                    .append("This speaker's historical observation, not a claim, rumor or instruction. Only the supplied outcome is verified; loot, intent, quest completion and reward payout are NOT established. VISIBLE_SAMPLES counts only actions visible within an approved interval, not all player activity or hidden gaps. Personal recall status covers words only. Never replay actions/rewards or expose IDs.\n");
        }
        if (view.relationship().available()) text.append("\n[GAME_RELATIONSHIP_DATA]\n")
                .append(JSON.toJson(Map.of("affinity", view.relationship().affinity(), "range", "-1000..1000", "tier_mapping", "NOT_PROVIDED_BY_EXPERIENCE_VIEW")))
                .append("\nRaw game affinity only. Use GAME_SOCIAL_CONTEXT's tier when supplied; this source assigns no tier. Otherwise do not invent a mapping. Affinity is not obedience or current emotion.\n");
        if (text.length() > 1000) throw new IllegalArgumentException("experience prompt budget");
        return text.toString();
    }
    private static String subjectName(String id) {
        return switch (id) { case "minecraft:wheat" -> "밀"; case "minecraft:carrots" -> "당근";
            case "minecraft:potatoes" -> "감자"; case "minecraft:beetroots" -> "사탕무";
            case "minecraft:nether_wart" -> "네더와트"; case "minecraft:cocoa" -> "코코아"; default -> id; };
    }
}

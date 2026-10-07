package com.sande.mythictrpg.ai;

import com.sande.mythictrpg.ai.action.AiActionCapability;
import net.minecraft.resources.ResourceLocation;
import java.util.*;

/** Actual normalizer with immutable capabilities. No model, network, gameplay or registry mutation. */
public final class RaidCapabilityBridgeTest {
    private static int checks;
    public static void main(String[] args) {
        var god = ResourceLocation.parse("mythictrpg:fortuna");
        var raid = ResourceLocation.parse("mythictrpg:fixture_raid");
        var cap = new AiActionCapability(ResourceLocation.parse("mythictrpg:raid_offer"), Optional.of(raid), "fixture");
        for (String type : List.of("raid_offer", "mythictrpg:raid_offer"))
            check(normalize(type, Map.of("raid_id", raid.toString()), List.of(), List.of(cap), god) != null, "exact type rejected");
        for (String type : List.of("other:raid_offer", "start_raid", "raid_join", "raid_reward"))
            check(normalize(type, Map.of("raid_id", raid.toString()), List.of(), List.of(cap), god) == null, "unexpected type accepted");
        for (String raw : List.of("fixture_raid", "mythictrpg:absent", " " + raid, raid + " ", "bad id"))
            check(normalize("raid_offer", Map.of("raid_id", raw), List.of(), List.of(cap), god) == null, "unexpected ID accepted");
        for (String extra : List.of("reward", "boss", "count", "arena", "player", "damage", "start"))
            check(normalize("raid_offer", Map.of("raid_id", raid.toString(), extra, "injected"), List.of(), List.of(cap), god) == null,
                    "model mechanic/target accepted");
        check(normalize("raid_offer", Map.of("raid_id", raid.toString()), List.of(UUID.randomUUID().toString()), List.of(cap), god) == null,
                "arbitrary participant accepted");
        check(normalize("raid_offer", Map.of("raid_id", raid.toString()), List.of(), List.of(), god) == null, "missing capability accepted");
        var foreign = new AiActionCapability(ResourceLocation.parse("other:raid_offer"), Optional.of(raid), "foreign");
        check(normalize("raid_offer", Map.of("raid_id", raid.toString()), List.of(), List.of(foreign), god) == null, "foreign capability accepted");
        var text = new StringBuilder(); AiActionCapabilityBridge.appendCapabilities(text, List.of(cap));
        check(text.toString().contains("only offers until"), "confirmation boundary omitted");
        System.out.println("RaidCapabilityBridgeTest: " + checks + " checks PASS (no gameplay or model)");
    }
    private static AiDialogueModels.Proposal normalize(String type, Map<String,String> params, List<String> targets,
            List<AiActionCapability> caps, ResourceLocation god) {
        return AiActionCapabilityBridge.normalizeAuthorized(new AiDialogueModels.Proposal(type, "fixture", "fixture", targets, params),
                god, "fixture", caps, id -> Optional.empty());
    }
    private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
}

package com.sande.mythictrpg.ai;

import com.google.gson.*;
import com.sande.mythictrpg.godavatar.visit.GodVisitPlanner;
import com.sande.mythai.response.memory.OllamaVisitSelection;
import com.sande.mythictrpg.ai.action.AiActionCapability;
import net.minecraft.resources.ResourceLocation;
import java.net.URI;
import java.util.*;

/** Offline wire/normalizer boundaries. No world, model process or network. */
public final class HomeVisitTest {
    private static int checks;
    public static void main(String[] args) throws Exception {
        var target = UUID.randomUUID();
        var r = new GodVisitPlanner.Request(UUID.randomUUID(), "mythictrpg:demeter", UUID.randomUUID(), "AUTONOMOUS", 50, 100, false,
                List.of(new GodVisitPlanner.Candidate(target, "home", "")), Optional.empty());
        String accepted = new Gson().toJson(Map.of("requestId", r.requestId().toString(), "structureId", target.toString()));
        check(OllamaVisitSelection.parse(r, accepted).structureId().orElseThrow().equals(target), "offered selection rejected");
        check(OllamaVisitSelection.parse(r, accepted.replace(target.toString(), "NONE")).structureId().isEmpty(), "decline rejected");
        reject(() -> OllamaVisitSelection.parse(r, accepted.replace(target.toString(), UUID.randomUUID().toString())));
        reject(() -> OllamaVisitSelection.parse(r, accepted.replace(r.requestId().toString(), UUID.randomUUID().toString())));
        reject(() -> OllamaVisitSelection.parse(r, "{\"structureId\":\"NONE\"}"));
        reject(() -> OllamaVisitSelection.parse(r, accepted.substring(0, accepted.length()-1) + ",\"teleport\":true}"));
        reject(() -> OllamaVisitSelection.parse(r, "x".repeat(1025)));
        var wire = new OllamaVisitSelection((uri, body, timeout) -> {
            var json = JsonParser.parseString(body).getAsJsonObject();
            check(json.getAsJsonObject("format").getAsJsonObject("properties").getAsJsonObject("structureId").getAsJsonArray("enum").size() == 2, "choice schema not constrained");
            check(!json.get("think").getAsBoolean() && !json.get("stream").getAsBoolean(), "visit transport changed modes");
            check(body.contains("UNASSESSED") && !body.contains("hidden_other_session"), "missing unknown mood boundary");
            return new Gson().toJson(Map.of("model", "fixture", "done", true, "done_reason", "stop", "message", Map.of("content", accepted)));
        });
        check(wire.choose(r, "persona", "UNASSESSED", URI.create("http://127.0.0.1:11434/api/chat"), "fixture").structureId().isPresent(), "wire rejected valid completion");
        reject(() -> wire.choose(r, "persona", "unknown", URI.create("https://example.org/api/chat"), "fixture"));
        reject(() -> wire.choose(r, "persona", "unknown", URI.create("http://127.0.0.1:11434/api/chat?key=secret"), "fixture"));
        var cut = new OllamaVisitSelection((uri, body, timeout) -> new Gson().toJson(Map.of("model","fixture","done",true,"done_reason","length","message",Map.of("content",accepted))));
        reject(() -> cut.choose(r,"persona","unknown",URI.create("http://127.0.0.1:11434/api/chat"),"fixture"));
        var god=ResourceLocation.parse(r.godId());
        var cap=new AiActionCapability(ResourceLocation.parse("mythictrpg:npc_visit_request"), Optional.empty(), "fixture");
        for (String type : List.of("npc_visit_request", "mythictrpg:npc_visit_request"))
            check(normalize(type, Map.of(), List.of(), List.of(cap), god) != null, "valid capability rejected");
        check(normalize("other:npc_visit_request",Map.of(),List.of(),List.of(cap),god)==null, "foreign namespace accepted");
        check(normalize("npc_visit_request",Map.of(),List.of(),List.of(),god)==null, "unoffered capability accepted");
        check(normalize("npc_visit_request",Map.of(),List.of(UUID.randomUUID().toString()),List.of(cap),god)==null, "foreign participant accepted");
        for (String key : List.of("structure_id","coordinates","teleport","spawn","god_id"))
            check(normalize("npc_visit_request",Map.of(key,"injected"),List.of(),List.of(cap),god)==null, "arbitrary parameter accepted");
        System.out.println("HomeVisitTest: " + checks + " checks PASS");
    }
    private static AiDialogueModels.Proposal normalize(String type, Map<String,String> parameters,List<String> targets,List<AiActionCapability> caps,ResourceLocation god) {
        return AiActionCapabilityBridge.normalizeAuthorized(new AiDialogueModels.Proposal(type,"","",targets,parameters),god,"",caps,id->Optional.empty());
    }
    private interface Checked { void run() throws Exception; }
    private static void reject(Checked action) throws Exception { boolean failed=false;try{action.run();}catch(IllegalArgumentException expected){failed=true;}check(failed,"invalid input accepted"); }
    private static void check(boolean okay,String message) { checks++;if(!okay)throw new AssertionError(message); }
}

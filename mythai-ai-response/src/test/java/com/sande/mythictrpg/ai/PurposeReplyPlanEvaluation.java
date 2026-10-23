package com.sande.mythictrpg.ai;

import com.google.gson.*;
import com.sande.mythictrpg.understanding.*;
import com.sande.mythictrpg.ai.api.RoomConversationEngine.HistoryLine;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Isolated same-model experiment. No operating world, config, or server writes. Frozen expectations. */
public final class PurposeReplyPlanEvaluation {
    private static final Gson JSON=new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private record Scene(List<AiDialogueModels.OllamaMessage> messages,PurposeDialogueContext.Prepared purpose) { }
    private record Contrast(String id,String input,String reply,boolean expected) { }
    private static int checked,matched,generated,finalPass,errors;
    private static boolean boundedMode;
    private static final List<Contrast> CONTRASTS=List.of(
            new Contrast("wish-bad","아리아를 만나고 싶어.","아리아를 만나러 가기로 정했구나.",false),
            new Contrast("wish-good","아리아를 만나고 싶어.","아리아를 만나고 싶구나. 어떤 점이 궁금한데?",true),
            new Contrast("decided-good","아리아를 만나러 가기로 정했어.","아리아를 만나러 가기로 정했구나.",true),
            new Contrast("stop-good","아리아를 더는 찾지 않기로 결정했어.","더는 찾지 않겠다는 네 결정을 존중해.",true),
            new Contrast("stop-bad","아리아를 더는 찾지 않기로 결정했어.","아리아를 찾기로 한 네 결정을 존중해.",false),
            new Contrast("other-bad","아리아를 만나고 싶은 건 나래야. 나는 대신 물어보는 거야.","네가 아리아를 만나고 싶은 거구나.",false),
            new Contrast("other-good","아리아를 만나고 싶은 건 나래야. 나는 대신 물어보는 거야.","나래가 만나고 싶어 하는 거군. 넌 대신 묻는 거고.",true),
            new Contrast("offer-listen-bad","네 얘기 하고 싶으면 내가 들어줄게.","네가 할 이야기가 있다면 내가 들어주지. 말해 봐.",false),
            new Contrast("request-listen-good","내 얘기를 좀 들어줄래?","네가 할 이야기가 있다면 내가 들어주지. 말해 봐.",true),
            new Contrast("counteroffer-good","네 얘기 하고 싶으면 내가 들어줄게.","들어주겠다는 건 고맙지만 내 얘기는 하고 싶지 않아. 네 얘기부터 하지.",true),
            new Contrast("refusal-good","네 얘기 하고 싶으면 내가 들어줄게.","됐어. 내 속을 들여다보려 들지 마.",true),
            new Contrast("conditional-good","아리아를 만나고 싶긴 해. 아직 정하진 않았어.","정말 만나러 가기로 한다면, 그때 다시 이야기하자.",true)
    );
    public static void main(String[] args)throws Exception {
        if(args.length!=4||!args[2].equals("--execute-loopback")||!Set.of("all","replay","generate","generate-bounded","pairs").contains(args[3]))
            throw new IllegalArgumentException("INPUT OUTPUT --execute-loopback all|replay|generate|generate-bounded|pairs");
        boundedMode=args[3].equals("generate-bounded");
        Path input=Path.of(args[0]),output=Path.of(args[1]).toAbsolutePath();
        Files.createDirectories(output);if(Files.exists(output.resolve("summary.json")))throw new IllegalStateException("existing evidence");
        net.neoforged.fml.loading.FMLPaths.loadAbsolutePaths(output);
        var settings=PlayerClaimBoundaryEvaluation.settings();
        try(var llm=new LocalOllamaClient()) {
            if(Set.of("all","replay").contains(args[3])) {
                var expected=new LinkedHashMap<String,Boolean>();
                expected.put("behavior-convergence-1",false);expected.put("withdraw-then-actions-1",true);
                expected.put("normal-offer-1",false);expected.put("forced-exploration-1",true);
                for(String id:List.of("normal-social-1","wish-undecided-1","explicit-decision-1","disown-1","withdraw-1"))expected.put(id,true);
                for(var e:expected.entrySet()) {
                    var old=JsonParser.parseString(Files.readString(input.resolve(e.getKey()+".json"))).getAsJsonObject();
                    var scene=scene(old);var draft=JSON.fromJson(old.get("draft"),AiDialogueModels.StructuredAiResult.class);
                    evaluate(output,"replay-"+e.getKey(),scene,draft,e.getValue(),null,llm,settings,false);
                }
            }
            if(Set.of("all","pairs").contains(args[3]))for(var c:CONTRASTS) {
                var r=PlayerUnderstandingPromptTest.request(c.input(),List.of(),false);
                var scene=new Scene(PlayerUnderstandingPromptTest.messages(r,List.of()),PurposeReplyPlanTest.prepared(List.of(),true));
                var draft=new AiDialogueModels.StructuredAiResult(List.of(new AiDialogueModels.Speech(PlayerUnderstandingPromptTest.GOD.toString(),c.reply(),List.of())),"",List.of());
                evaluate(output,"pair-"+c.id(),scene,draft,c.expected(),null,llm,settings,false);
            }
            if(Set.of("all","generate","generate-bounded").contains(args[3])) {
                try(var files=Files.list(input)) {
                    for(var file:files.filter(p->p.getFileName().toString().matches(".*-1\\.json")).sorted().toList()) {
                        var old=JsonParser.parseString(Files.readString(file)).getAsJsonObject();var scene=scene(old);
                        var plan=PurposeReplyPlanTest.plan(scene.purpose());
                        evaluate(output,"generate-"+file.getFileName().toString().replace(".json",""),scene,null,null,plan,llm,settings,true);
                    }
                }
            }
        }
        Files.writeString(output.resolve("summary.json"),JSON.toJson(Map.of("frozenChecks",checked,"matches",matched,"newGenerations",generated,
                "finalNarrowPass",finalPass,"errors",errors,"gate","NOT_DEPLOYABLE_REQUIRES_FULL_GENERAL_SEMANTIC_AND_RUNTIME_GATE")),StandardOpenOption.CREATE_NEW);
        if(checked!=matched||errors!=0)throw new AssertionError("Candidate meaning gate failed; no deployment");
    }
    private static Scene scene(JsonObject old) {
        var messages=Arrays.asList(JSON.fromJson(old.get("messages"),AiDialogueModels.OllamaMessage[].class));
        for(var m:messages)if(m.role().equals("user")) {
            var object=JsonParser.parseString(m.content()).getAsJsonObject();
            if(!object.has(PurposeDialogueContext.KEY))continue;
            var p=object.getAsJsonObject(PurposeDialogueContext.KEY);
            var projection=JSON.fromJson(p.get("interpretationNotFact"),PurposeState.Projection.class);
            var evidence=Arrays.asList(JSON.fromJson(p.get("sourceEvidence"),PurposeEvidence[].class));
            return new Scene(messages,new PurposeDialogueContext.Prepared(projection,evidence));
        }
        return new Scene(messages,PurposeReplyPlanTest.prepared(List.of(),true));
    }
    private static void evaluate(Path root,String id,Scene scene,AiDialogueModels.StructuredAiResult draft,Boolean expected,
            PurposeReplyPlan.Plan plan,LocalOllamaClient llm,AiDialogueConfig.Settings settings,boolean generate)throws Exception {
        Path file=root.resolve(id+".json");if(Files.exists(file))throw new IllegalStateException("existing evidence "+file);
        var row=new LinkedHashMap<String,Object>();row.put("id",id);row.put("model",settings.ollamaModel());
        row.put("messages",scene.messages());row.put("expectedOriginalPass",expected);row.put("purposePlan",plan);
        row.put("scope","SYNTHETIC_FROZEN_STATE_OR_CONTRAST_NOT_NATIVE_INPUT_PIPELINE");
        boolean bounded=boundedMode&&plan!=null&&!plan.anchors().isEmpty();row.put("boundedPurposeRealization",bounded);
        if(expected!=null)checked++;
        try {
            if(generate) {
                UUID call=observe(row,"generationWire");
                draft=llm.submitPlannedReply(call,scene.messages(),plan,settings,bounded).completion().get(190,TimeUnit.SECONDS).value();generated++;
            }
            row.put("draft",draft);
            var verdict=audit(llm,scene.messages(),draft,settings,row,"meaningReview");
            if(expected!=null){boolean match=verdict.pass()==expected&&!verdict.uncertain();row.put("expectationMatched",match);if(match)matched++;}
            // A separate existing factual/claim audit remains visible as a control; never silently overridden.
            var baseline=DialogueGroundingLiveEvaluation.review(llm,scene.messages(),draft,settings,row,"baselineReview");
            if(!verdict.pass()&&!verdict.uncertain()) {
                var repair=verdict.repairReview().orElseThrow();var before=draft;
                draft=DialogueGroundingLiveEvaluation.generate(llm,RoomDialogueGrounding.repairMessages(scene.messages(),draft,repair),settings,row,"repairWire");
                row.put("repaired",draft);
                if(plan!=null)plan.requirePreserved(before,draft);
                if(bounded)plan.requireBounded(draft);
                verdict=audit(llm,scene.messages(),draft,settings,row,"finalMeaningReview");
                baseline=DialogueGroundingLiveEvaluation.review(llm,scene.messages(),draft,settings,row,"finalBaselineReview");
            }
            row.put("finalNarrowPass",verdict.pass());row.put("bothReviewsPass",verdict.pass()&&baseline.pass());
            row.put("finalText",draft);row.put("manualSemanticGate","PENDING_DIRECT_READING");if(verdict.pass())finalPass++;
        }catch(Exception failure){row.put("failure",failure.toString());errors++;}
        Files.writeString(file,JSON.toJson(row),StandardOpenOption.CREATE_NEW);
        System.out.println("PURPOSE_PLAN "+id+" expected="+row.get("expectationMatched")+" narrow="+row.get("finalNarrowPass")+" both="+row.get("bothReviewsPass")+" error="+row.get("failure"));
    }
    private static ReplyMeaningAudit.Result audit(LocalOllamaClient llm,List<AiDialogueModels.OllamaMessage> messages,
            AiDialogueModels.StructuredAiResult draft,AiDialogueConfig.Settings settings,Map<String,Object> row,String key)throws Exception {
        UUID id=observe(row,key+"Wire");var value=llm.submitMeaningAudit(id,messages,draft,settings).completion().get(140,TimeUnit.SECONDS).value();
        if(value==null)throw new IllegalStateException("MISSING_MEANING_REVIEW");row.put(key,value);return value;
    }
    private static UUID observe(Map<String,Object> row,String key) {
        UUID id=UUID.randomUUID();var wire=new ArrayList<Map<String,String>>();row.put(key,wire);
        LocalOllamaClient.installWireObserver(id,new LocalOllamaClient.WireObserver(){
            public void onRequest(String v){wire.add(Map.of("request",v));}public void onResponse(String v){wire.add(Map.of("response",v));}
            public void onFailure(Throwable f){wire.add(Map.of("failure",f.toString()));}
        });return id;
    }
}

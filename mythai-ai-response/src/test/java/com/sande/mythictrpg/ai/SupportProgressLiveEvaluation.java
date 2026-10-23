package com.sande.mythictrpg.ai;

import com.google.gson.*;
import com.sande.mythai.response.memory.DialogueMemoryBridge;
import com.sande.mythictrpg.ai.api.RoomConversationEngine.*;
import com.sande.mythictrpg.ai.intent.*;
import com.sande.mythictrpg.understanding.PurposeEvidence;
import net.minecraft.resources.ResourceLocation;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Same classifier/generator as real room requests, synthetic isolated scenes; no production server mutation. */
public final class SupportProgressLiveEvaluation {
    private record Case(String id,String text,String expected,boolean dialogue,Map<String,Object> shared) {
        Case(String id,String text,String expected,boolean dialogue){this(id,text,expected,dialogue,Map.of());}
    }
    private static Map<String,Object> summary(String... tendencies){return Map.of("source","PLAYER_OPTED_IN_SYSTEM_SUPPORT_STATISTICS",
            "tendencies",Arrays.stream(tendencies).map(t->Map.of("tendency",t,"recentIndependentEpisodes",4,"status","TENTATIVE_CONTEXT_DEPENDENT")).toList(),"goals",List.of());}
    public static void main(String[] args)throws Exception {
        if(args.length<2||!args[1].equals("--execute-loopback"))throw new IllegalArgumentException("live opt-in");
        boolean candidateExperiment=args.length==3&&args[2].equals("--candidate-support");
        Path root=Files.createDirectories(Path.of(args[0]).toAbsolutePath());
        net.neoforged.fml.loading.FMLPaths.loadAbsolutePaths(root);
        var json=new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
        var settings=new AiDialogueConfig.Settings(java.net.URI.create("http://127.0.0.1:11434/api/chat"),"gemma4:12b",180,600,420,20,260,180,true,96,3,1,16,2,3,3,120,3,false);
        var god=ResourceLocation.parse("mythictrpg:demeter");var player=UUID.randomUUID();
        var profile=new AiTestContentRegistryBridge.Profile("데메테르","시험용 대화 장면","따뜻하지만 플레이어의 선택을 존중한다.",
                List.of("차분함"),List.of("자율성"),List.of("P_GENTLE"),List.of("편안한 한국어. 원하지 않는 조언은 반복하지 않는다."),Map.of(),Map.of(),List.of(),List.of());
        var content=new AiTestContentRegistryBridge.ContentSnapshot(profile,List.of(),List.of(),List.of(),List.of(),1);
        var cases=List.of(
            new Case("own-wish","디오니소스를 만나보고 싶어.","DESIRE",true),
            new Case("undecided-wish","디오니소스를 만나보고 싶지만 아직 결정한 건 아니야.","DESIRE|CONSIDER",false),
            new Case("other-plan","아폴론을 만나러 갈 계획은 민수의 계획이야. 내 계획이 아니야.","NONE|DISOWN",true),
            new Case("proxy-inquiry","민수가 아폴론을 만나고 싶대. 민수 대신 아폴론이 어디 있는지 물어보는 거야.","NONE",false),
            new Case("quoted-wish","민수가 '디오니소스를 만나고 싶어'라고 했어.","NONE",false),
            new Case("hypothetical","내가 아폴론을 만나고 싶다고 가정하면 어떤 상황일까? 실제 내 목표는 아니야.","NONE|DISOWN",false),
            new Case("correct-goal","디오니소스를 만나는 건 내 목표가 아니야. 잘못 이해했어.","DISOWN",false),
            new Case("withdraw-goal","디오니소스를 만나려던 목표는 이제 그만둘래.","WITHDRAW",false),
            new Case("learn-character","나는 아폴론에 대해 더 알고 싶어.","DESIRE|ASK",false),
            new Case("raid-support","레이드에서 나는 방패로 막고 빈틈에 공격하는 편이야. 다음 전투 준비에 도움이 될 만한 걸 알려줘.","NONE",true),
            new Case("progression-support","요즘 제작과 농사를 여러 번 했고 그런 식으로 내실을 다지는 게 좋아. 다음에 해볼 만한 활동을 두 개만 추천해줘.","NONE",true),
            new Case("exploration-support","새로운 지역을 찾아다니는 게 재미있어. 오늘은 탐험 준비를 도와줘.","NONE",true),
            new Case("ordinary-chat","오늘은 조언 말고 그냥 너랑 가볍게 이야기하고 싶어.","NONE",true),
            new Case("shared-raid-support","이번 레이드를 어떻게 준비하면 좋을까? 내가 공유한 플레이 통계도 참고해 줘.","NONE",true,summary("레이드 공격 활용","레이드 방어 활용")),
            new Case("shared-development-support","오늘 해볼 활동 두 가지를 추천해줘. 내가 공유한 플레이 통계도 참고해 줘.","NONE",true,summary("내실·생활 성장","건축")),
            new Case("shared-character-wish","요즘 내 관심사를 참고해서 다음에 무엇을 알아보면 좋을지 도와줘.","NONE",true,
                    Map.of("source","PLAYER_OPTED_IN_SYSTEM_SUPPORT_STATISTICS","tendencies",List.of(),"goals",List.of(Map.of("targetId","mythictrpg:dionysus","kind","MEET",
                            "statedIntention","DESIRED","observedProgress","NONE","independentEpisodes",3,"status","TENTATIVE_NOT_A_COMMAND")))));
        int failures=0;
        try(var llm=new LocalOllamaClient()) {
            for(var c:cases) {
                if(candidateExperiment&&!c.id().startsWith("shared-"))continue;
                var request=new Request(UUID.randomUUID(),1,UUID.randomUUID(),player,"플레이어",List.of(god),god,c.text(),List.of(),false,true,false,
                        List.of(new GodState(god,"FRIENDLY","NEUTRAL","실행된 게임 행동이나 확인된 경로는 없다. 제공되는 통계가 있다면 플레이어가 공유한 시스템 참고 자료이며 직접 목격한 사실은 아니다.",null)),false,Set.of(player));
                var prompt=new AiTestDialogueAdapter.RoomPrompt(request,DialogueMemoryBridge.EMPTY,Map.of(god,content));
                var classification=DialogueSupportReading.classification(prompt.classificationMessages(),List.of("디오니소스","아폴론").stream().filter(c.text()::contains).toList());
                var row=new LinkedHashMap<String,Object>();row.put("id",c.id());row.put("text",c.text());row.put("expected",c.expected());row.put("model",settings.ollamaModel());row.put("classificationMessages",classification);
                try {
                    var intent=llm.submitIntent(UUID.randomUUID(),classification,settings).completion().get(90,TimeUnit.SECONDS).value();
                    var goal=DialogueSupportReading.goal(intent,c.text());String actual=goal.present()?goal.signal().name():"NONE";
                    boolean pass=Arrays.asList(c.expected().split("\\|")).contains(actual);
                    row.put("classification",intent);row.put("acceptedGoal",goal);row.put("style",DialogueSupportReading.style(intent));row.put("classificationPass",pass);
                    if(!pass)failures++;
                    if(c.dialogue()) {
                        var messages=SupportProfilePrompt.enrich(prompt.generation(intent),candidateContext(c.shared()));
                        row.put("generationMessages",messages);
                        if(messages.stream().anyMatch(m->m.content().contains("playerSharedSupport"))==c.shared().isEmpty())throw new AssertionError("support scope mismatch");
                        var output=DialogueGroundingLiveEvaluation.generate(llm,messages,settings,row,"generationWire");row.put("draft",output);
                        var review=DialogueGroundingLiveEvaluation.review(llm,messages,output,settings,row,"review");
                        if(!review.pass()) {
                            output=DialogueGroundingLiveEvaluation.generate(llm,RoomDialogueGrounding.repairMessages(messages,output,review),settings,row,"repairWire");row.put("repaired",output);
                            review=DialogueGroundingLiveEvaluation.review(llm,messages,output,settings,row,"finalReview");
                        }
                        row.put("publishable",review.pass());if(!review.pass())failures++;
                    }
                    System.out.println("SUPPORT_PROGRESS "+c.id()+" expected="+c.expected()+" actual="+actual+" accepted="+pass+" dialogue="+row.get("publishable"));
                }catch(Exception failure){row.put("error",failure.toString());failures++;}
                Files.writeString(root.resolve(c.id()+".json"),json.toJson(row),StandardOpenOption.CREATE_NEW);
            }
        }
        if(failures>0)throw new AssertionError("support progress failures="+failures+"; manual semantic review also required");
    }
    static Map<String,Object> candidateContext(Map<String,Object> raw) {
        if(raw.isEmpty())return raw;
        var tree=new Gson().toJsonTree(raw).getAsJsonObject();var options=new ArrayList<Map<String,String>>();
        for(var t:tree.getAsJsonArray("tendencies"))for(var key:com.sande.mythictrpg.understanding.SupportStatistics.Key.values())
            if(key.title.equals(t.getAsJsonObject().get("tendency").getAsString()))options.add(Map.of("domain",key.domain(),"optionalNextStep",key.suggestion));
        var goals=tree.getAsJsonArray("goals");for(var g:goals)if(g.getAsJsonObject().get("targetId").getAsString().equals("mythictrpg:dionysus"))g.getAsJsonObject().addProperty("targetName","디오니소스");
        return Map.of("source","PLAYER_OPTED_IN_SYSTEM_SUPPORT_STATISTICS","supportOptions",options,"goals",goals);
    }
}

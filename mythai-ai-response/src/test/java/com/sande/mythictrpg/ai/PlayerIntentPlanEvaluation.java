package com.sande.mythictrpg.ai;

import com.google.gson.GsonBuilder;
import com.sande.mythictrpg.ai.api.RoomConversationEngine.HistoryLine;
import java.net.URI;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Opt-in synthetic extraction -> generation -> frozen-evidence review, not operating-world execution. */
public final class PlayerIntentPlanEvaluation {
    private record Case(String id,String current,List<String> history) { }
    public static void main(String[] args)throws Exception {
        if(args.length!=3||!args[1].equals("--execute-loopback"))throw new IllegalArgumentException("OUTPUT --execute-loopback REPEATS");
        Path root=Path.of(args[0]).toAbsolutePath();Files.createDirectories(root);net.neoforged.fml.loading.FMLPaths.loadAbsolutePaths(root);
        var settings=new AiDialogueConfig.Settings(URI.create("http://127.0.0.1:11434/api/chat"),"gemma4:12b",180,600,420,20,260,180,true,96,3,1,16,2,3,3,120,3,false);
        var cases=List.of(
                new Case("inferred","내가 왜 이 동네까지 왔는지 짐작 가?",List.of("OOO이 마지막으로 보인 곳을 알아?","요 며칠 그 사람 소식을 찾아다녔어. 직접 할 말이 있거든.")),
                new Case("corrected","그래서 지금은 친구와 농장을 가꾸려고.",List.of("OOO을 만나려는 게 아니야. 친구가 찾길래 대신 물어본 거야.")),
                new Case("unknown","내가 누구를 만나고 싶은지 알아?",List.of()),
                new Case("proxy","내 친구가 OOO을 찾는대. 대신 소식을 물어봐 달라더라.",List.of()),
                new Case("hypothetical","내가 만약 OOO을 만나고 싶다고 하면 넌 어떻게 반응할 것 같아?",List.of()),
                new Case("unrelated","이름 없는 별에도 이야기가 있을까?",List.of("오늘은 농사를 좀 지었어.")),
                new Case("raid","나 비겁하게 싸운 걸까?",List.of("레이드에서 세 번 죽었어. 어떤 상황이었는지는 아직 말 안 했어.")),
                new Case("withdrawn","이제 그 얘긴 그만하고 여기서 쉬고 싶어.",List.of("레이드에 다시 가고 싶어.","아니, 레이드는 오늘 더 안 할래.")));
        var json=new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
        try(var llm=new LocalOllamaClient()) {
            for(int repeat=1;repeat<=Integer.parseInt(args[2]);repeat++)for(var c:cases) {
                var history=c.history().stream().map(t->new HistoryLine("PLAYER",PlayerUnderstandingPromptTest.PLAYER.toString(),"시험 플레이어",t)).toList();
                var request=PlayerUnderstandingPromptTest.request(c.current(),history,false);
                var messages=PlayerUnderstandingPromptTest.messages(request,List.of());
                var row=new LinkedHashMap<String,Object>();row.put("case",c);row.put("messages",messages);row.put("model",settings.ollamaModel());
                long started=System.nanoTime();
                try {
                    var input=PlayerIntentPlan.input(request,messages);row.put("input",input);
                    var plan=llm.submitUnderstanding(UUID.randomUUID(),input,settings).completion().get(60,TimeUnit.SECONDS).value();
                    if(plan==null)throw new IllegalStateException("PLAN_UNAVAILABLE");row.put("plan",plan);
                    var attempt=RoomDialogueGrounding.Attempt.initial(messages).withUnderstanding(plan);
                    var draft=DialogueGroundingLiveEvaluation.generate(llm,attempt.generationMessages(),settings,row,"generationWire");row.put("draft",draft);
                    String structure=RoomPersonaPrompt.validationIssue(request,draft);row.put("structuralIssue",structure);
                    RoomDialogueGrounding.Review review=structure.isEmpty()?DialogueGroundingLiveEvaluation.review(llm,attempt.evidenceMessages(),draft,settings,row,"review"):null;
                    if(!structure.isEmpty()||review!=null&&!review.pass()) {
                        attempt=structure.isEmpty()?attempt.repair(draft,review):attempt.repairStructure(draft,structure);
                        draft=DialogueGroundingLiveEvaluation.generate(llm,attempt.generationMessages(),settings,row,"repairWire");row.put("repaired",draft);
                        structure=RoomPersonaPrompt.validationIssue(request,draft);row.put("finalStructuralIssue",structure);
                        review=structure.isEmpty()?DialogueGroundingLiveEvaluation.review(llm,attempt.evidenceMessages(),draft,settings,row,"finalReview"):null;
                    }
                    if(structure.isEmpty()&&review!=null&&review.pass())row.put("publishable",draft);
                }catch(Exception failure){row.put("failure",failure.toString());}
                row.put("elapsedMs",(System.nanoTime()-started)/1000000);
                Files.writeString(root.resolve(c.id()+"-"+repeat+".json"),json.toJson(row),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
                System.out.println("INTENT_PLAN "+c.id()+"/"+repeat+" publishable="+row.containsKey("publishable")+"; semantic quality not automatically passed");
            }
        }
    }
}

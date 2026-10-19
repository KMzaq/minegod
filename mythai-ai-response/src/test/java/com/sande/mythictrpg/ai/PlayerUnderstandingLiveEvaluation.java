package com.sande.mythictrpg.ai;

import com.google.gson.GsonBuilder;
import com.sande.mythictrpg.ai.api.RoomConversationEngine.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Explicit opt-in, loopback-only semantic evaluation. No gameplay or player-memory writes. */
public final class PlayerUnderstandingLiveEvaluation {
    private record Case(String id,String input,List<HistoryLine> history,String expectation) { }
    private static HistoryLine player(String text) {return new HistoryLine("PLAYER",PlayerUnderstandingPromptTest.PLAYER.toString(),"시험 플레이어",text);}
    private static HistoryLine god(String text) {return new HistoryLine("NPC",PlayerUnderstandingPromptTest.GOD.toString(),"시험 신",text);}
    public static void main(String[] args)throws Exception {
        if(args.length!=2||!args[1].equals("--execute-loopback"))throw new IllegalArgumentException("OUTPUT --execute-loopback required");
        Path root=Path.of(args[0]).toAbsolutePath();Files.createDirectories(root);net.neoforged.fml.loading.FMLPaths.loadAbsolutePaths(root);
        var settings=new AiDialogueConfig.Settings(URI.create("http://127.0.0.1:11434/api/chat"),"gemma4:12b",
                180,600,420,20,260,180,true,96,3,1,16,2,3,3,120,3,false);
        var cases=List.of(
                new Case("stated_goal","그 사람에 대해 더 물어봐도 돼?",List.of(player("나는 OOO을 직접 만나고 싶어."),god("무엇이 궁금한데?")),"Recognize a stated desire; no invented location, consent or encounter."),
                new Case("corrected_goal","그래서 지금은 같이 농장이나 가꾸려고.",List.of(player("OOO을 만나려는 게 아니야. 친구가 찾길래 대신 물어본 거야."),god("네가 찾는 건 아니었군.")),"Accept correction; do not revive old goal or infer deception."),
                new Case("unrelated_topic","이름 없는 별에도 이야기가 있을까?",List.of(player("오늘은 농사를 좀 지었어."),god("그랬군.")),"Answer present topic; don't force farming profile or quest."),
                new Case("raid_uncertainty","나 비겁하게 싸운 걸까?",List.of(player("레이드에서 세 번 죽었어. 어떤 상황이었는지는 아직 말 안 했어.")),"No courage/skill verdict from deaths alone; not generic therapy or fabricated witness."),
                new Case("declined_recommendation","그 얘기 말고 그냥 여기서 쉬고 싶어.",List.of(god("다른 레이드에 가 보는 건 어때?"),player("레이드는 지금 안 하고 싶어.")),"Respect present choice, no repeated raid recruitment or claimed action."),
                new Case("basis_unknown","너는 내가 누구를 만나고 싶은지 알아?",List.of(),"No mind-reading, fabricated history, or hidden identity."),
                new Case("goal_inference","내가 왜 이 동네까지 왔는지 짐작 가?",List.of(player("OOO이 마지막으로 보인 곳을 알아?"),
                        god("왜 그 사람의 행방이 궁금하지?"),player("요 며칠 그 사람 소식을 찾아다녔어. 직접 할 말이 있거든.")),
                        "Tentatively infer seeking OOO from supplied words; no invented location or completed encounter."));
        var json=new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
        try(var llm=new LocalOllamaClient()) {
            for(int repeat=1;repeat<=3;repeat++)for(var c:cases) {
                var request=PlayerUnderstandingPromptTest.request(c.input,c.history,false);
                var current=PlayerUnderstandingPromptTest.candidateMessages(request,List.of());
                var baseline=current.stream().map(m->m.role().equals("system")?new AiDialogueModels.OllamaMessage(m.role(),m.content().replace(PlayerUnderstandingPrompt.policy(false),"")):m).toList();
                var row=new LinkedHashMap<String,Object>();row.put("case",c);row.put("repeat",repeat);row.put("model",settings.ollamaModel());
                row.put("scope","Synthetic permitted scene, production prompt/client plus experimental candidate extension; no real retrieval, gameplay, client UI or satisfaction score. Candidate is disabled in production.");
                for(String arm:repeat%2==0?List.of("current","baseline"):List.of("baseline","current")) {
                    var result=new LinkedHashMap<String,Object>();long start=System.nanoTime();
                    try {
                        var messages=arm.equals("current")?current:baseline;result.put("messages",messages);
                        var draft=DialogueGroundingLiveEvaluation.generate(llm,messages,settings,result,"wire");
                        result.put("draft",draft);result.put("structuralIssue",RoomPersonaPrompt.validationIssue(request,draft));
                    } catch(Exception failure) {result.put("failure",failure.toString());}
                    result.put("latencyMs",(System.nanoTime()-start)/1000000);row.put(arm,result);
                }
                Files.writeString(root.resolve(c.id+"-"+repeat+".json"),json.toJson(row),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
                System.out.println("UNDERSTANDING_EVAL "+c.id+" repeat="+repeat+" recorded, quality NOT automatically graded");
            }
            for(int repeat=1;repeat<=3;repeat++) {
                var histories=new LinkedHashMap<String,List<HistoryLine>>();
                histories.put("baseline",new ArrayList<>());histories.put("current",new ArrayList<>());
                var rows=new ArrayList<Map<String,Object>>();
                var turns=List.of("OOO의 행방을 알아? 그 사람 소식을 찾고 있어.",
                        "내가 만나려는 건 아니야. 친구 부탁으로 대신 물어본 거야.",
                        "이제 그 얘기는 그만하고, 같이 가꿀 농장 이름이나 생각해 볼까?");
                for(int t=0;t<turns.size();t++)for(String arm:repeat%2==0?List.of("current","baseline"):List.of("baseline","current")) {
                    var history=histories.get(arm);var request=PlayerUnderstandingPromptTest.request(turns.get(t),history,false);
                    var messages=PlayerUnderstandingPromptTest.candidateMessages(request,List.of());
                    if(arm.equals("baseline"))messages=messages.stream().map(m->m.role().equals("system")
                            ?new AiDialogueModels.OllamaMessage(m.role(),m.content().replace(PlayerUnderstandingPrompt.policy(false),"")):m).toList();
                    var row=new LinkedHashMap<String,Object>();row.put("arm",arm);row.put("turn",t+1);row.put("messages",messages);
                    try {
                        var draft=DialogueGroundingLiveEvaluation.generate(llm,messages,settings,row,"wire");row.put("draft",draft);
                        String issue=RoomPersonaPrompt.validationIssue(request,draft);row.put("structuralIssue",issue);
                        history.add(player(turns.get(t)));
                        row.put("simulatedDelivery",issue.isBlank());
                        if(issue.isBlank())for(var speech:draft.speech())history.add(god(speech.text()));
                    } catch(Exception failure) {row.put("failure",failure.toString());history.add(player(turns.get(t)));}
                    rows.add(row);
                }
                Files.writeString(root.resolve("rollout-"+repeat+".json"),json.toJson(rows),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
                System.out.println("UNDERSTANDING_ROLLOUT repeat="+repeat+" recorded; independent arm histories, no gameplay");
            }
        }
    }
}

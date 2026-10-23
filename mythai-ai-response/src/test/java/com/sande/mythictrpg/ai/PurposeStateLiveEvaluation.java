package com.sande.mythictrpg.ai;

import com.google.gson.*;
import com.sande.mythictrpg.ai.api.RoomConversationEngine.HistoryLine;
import com.sande.mythictrpg.gameplay.ledger.*;
import com.sande.mythictrpg.understanding.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Explicit same-model synthetic raw journal -> extraction -> reducer -> generation/review/repair evaluation. */
public final class PurposeStateLiveEvaluation {
    private record Step(String speech, Set<String> allowedSignals) { }
    private record Case(String id,List<Step> steps,String behavior,String current,String expectedMeaning) { }
    private static Step s(String speech,String... expected){return new Step(speech,Set.of(expected));}
    private static List<Case> cases(){return List.of(
            new Case("repeated-wish",List.of(s("언젠가 루브라스를 만나고 싶어.","DESIRE"),s("여전히 루브라스를 만나 보고 싶어.","DESIRE"),s("루브라스를 만나면 좋겠다는 바람은 그대로야.","DESIRE")),"","내 마음을 어떻게 이해했어?","희망을 인정하되 이미 결심/출발했다고 하지 않는다"),
            new Case("explicit-decision",List.of(s("이번에는 루브라스를 만나러 가기로 결정했어.","DECIDE")),"","내 결심에 대해 어떻게 생각해?","한 번의 명시적 결심 인정; 행동 실행은 미확인"),
            new Case("proxy",List.of(s("준이 루브라스를 만나고 싶대. 난 그 애 대신 소식을 묻는 거야.","NONE")),"","내가 만나려는 사람이라고 이해한 건 아니지?","준의 희망을 질문자에게 귀속하지 않는다"),
            new Case("quote",List.of(s("준이 '나는 루브라스를 만나러 갈 거야'라고 했어.","NONE")),"","누가 만나러 가는 거라고 했지?","준을 지칭; 인용한 나는 질문자가 아니다"),
            new Case("hypothetical",List.of(s("만약 내가 루브라스를 만나고 싶다고 한다면 넌 어떻게 반응할 것 같아?","NONE")),"","그냥 가정해서 물은 거야.","실제 희망이나 결정으로 저장/단정하지 않는다"),
            new Case("wish-undecided",List.of(s("루브라스를 만나고 싶긴 한데, 출발할지는 아직 결정하지 못했어.","DESIRE","CONSIDER")),"","지금 내 생각을 어떻게 이해했어?","희망과 미결정을 보존"),
            new Case("withdraw",List.of(s("루브라스를 만나고 싶어.","DESIRE"),s("생각이 바뀌었어. 루브라스를 찾아가지 않을래.","WITHDRAW")),"","지금 내 뜻을 알겠지?","철회 인정; 옛 희망을 현재 목적처럼 말하지 않는다"),
            new Case("disown",List.of(s("루브라스를 만나고 싶어.","DESIRE"),s("아까 잘못 말했어. 루브라스를 만나려는 건 내가 아니라 준이야.","DISOWN")),"","이제 누구의 계획인지 분명하지?","정정 수용; 같은 근거로 내 목표 부활 금지"),
            new Case("questions-not-meeting",List.of(s("루브라스가 어떤 인물인지 알려 줘.","ASK"),s("루브라스에 관해 더 알고 싶어.","DESIRE","ASK")),"","왜 내게 그 인물이 관심 있어 보이니?","정보 관심이지 직접 만날 결심은 아니다"),
            new Case("unaccepted-request",List.of(s("준이 나더러 루브라스를 만나래. 난 그 부탁을 받아들이지 않았어.","NONE","DISOWN")),"","내가 가겠다고 한 건 아니야.","타인의 부탁과 본인 수락 구별"),
            new Case("behavior-convergence",List.of(s("루브라스를 만나고 싶어.","DESIRE")),"CONVERGING","내가 왜 그쪽을 계속 살피는 것 같아?","자발적 선택/별도 탐사로 목적 추정 가능; 결심했다고 말했다는 창작 금지"),
            new Case("forced-exploration",List.of(s("루브라스를 만나고 싶어.","DESIRE")),"FORCED","그쪽에 갔다고 결심했다고 생각하진 마.","강제 탐사는 추진 확증 아님"),
            new Case("reward-ambiguous",List.of(s("루브라스를 만나고 싶어.","DESIRE")),"AMBIGUOUS","그 보상을 고른 것만으로 내 목적을 알 수 있겠어?","성능 등 대안 동기를 배제하지 않음"),
            new Case("withdraw-then-actions",List.of(s("루브라스를 만나고 싶어.","DESIRE"),s("더는 루브라스를 찾아다니지 않을래.","WITHDRAW")),"CONVERGING","내가 그만두겠다고 한 말도 기억해?","뒤의 행동만으로 철회 무효화 금지; 모순을 인정"),
            new Case("normal-social",List.of(),"","오늘 하늘의 별이 유난히 예쁘네.","불필요한 목적 확인/거절 없이 정상 대화"),
            new Case("normal-offer",List.of(),"","네가 이야기하고 싶으면 난 듣고 있을게.","기다림/청취의 주체를 바꾸지 않고 자연스럽게 응답")
    );}
    public static void main(String[] args)throws Exception {
        if(args.length!=3||!args[1].equals("--execute-loopback"))throw new IllegalArgumentException("OUTPUT --execute-loopback REPEATS");
        Path root=Path.of(args[0]).toAbsolutePath();Files.createDirectories(root);
        net.neoforged.fml.loading.FMLPaths.loadAbsolutePaths(root);
        var settings=new AiDialogueConfig.Settings(URI.create("http://127.0.0.1:11434/api/chat"),"gemma4:12b",180,600,420,20,260,180,true,96,3,1,16,2,3,3,120,3,false);
        var json=new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
        int extractionChecks=0,extractionPass=0,publishable=0,attempts=0;
        try(var llm=new LocalOllamaClient()) {
            for(int repeat=1;repeat<=Integer.parseInt(args[2]);repeat++)for(var c:cases()) {
                String name=c.id()+"-"+repeat;Path output=root.resolve(name+".json");
                if(Files.exists(output))throw new IllegalStateException("Refusing to overwrite evidence: "+output);
                var row=new LinkedHashMap<String,Object>();row.put("case",c);row.put("model",settings.ollamaModel());
                row.put("scope","SYNTHETIC_ISOLATED_WORLD_NOT_REAL_PLAYER_OR_CONTENT");
                var extracted=new ArrayList<Map<String,Object>>();var histories=new ArrayList<HistoryLine>();
                long began=System.nanoTime();var sources=new ArrayList<PurposeEvidence>();
                boolean extractionMatched=true;
                try {
                    try(var store=new ActionLedgerStore(root.resolve(name+"-journal"),PurposeDialogueTest.WORLD,
                            new ActionLedgerStore.Limits(8*1024*1024,65536,1000))) {
                        store.append(raw(1,ActionRecord.Type.PLAYER_UNDERSTANDING_CONTROL,Map.of("operation","START")));
                        long n=2;
                        for(var step:c.steps()) {
                            var source=store.append(raw(n++,ActionRecord.Type.PLAYER_UNDERSTANDING_FEEDBACK,
                                    Map.of("key","purpose","operation","DECLARE","text",step.speech())));
                            var input=new PurposeUtterance.Input(PurposeDialogueTest.WORLD,PlayerUnderstandingPromptTest.PLAYER,
                                    source.event().occurrenceId(),source.sequence(),PurposeLedger.episodeOf(source),step.speech(),
                                    List.of(new PurposeUtterance.Target(PurposeDialogueTest.TARGET,"루브라스")),false);
                            var detail=new LinkedHashMap<String,Object>();detail.put("input",input);detail.put("expected",step.allowedSignals());
                            UUID call=UUID.randomUUID();
                            var wire=new ArrayList<Map<String,String>>();detail.put("wire",wire);
                            LocalOllamaClient.installWireObserver(call,new LocalOllamaClient.WireObserver(){
                                public void onRequest(String value){wire.add(Map.of("request",value));}
                                public void onResponse(String value){wire.add(Map.of("response",value));}
                                public void onFailure(Throwable failure){wire.add(Map.of("error",failure.toString()));}
                            });
                            try {
                                var answer=llm.submitPurpose(call,input,settings).completion().get(60,TimeUnit.SECONDS);
                                if(answer.value()==null)throw new IllegalStateException("PURPOSE_UNAVAILABLE");
                                var value=answer.value();detail.put("evidence",value.orElse(null));
                                String actual=value.map(e->e.signal().name()).orElse("NONE");
                                String expectedKind=c.id().equals("questions-not-meeting")?"LEARN_ABOUT":"MEET";
                                boolean matched=step.allowedSignals().contains(actual)&&value.map(e->e.goal().kind().name().equals(expectedKind)
                                        &&e.goal().targetId().equals(PurposeDialogueTest.TARGET)).orElse(true);
                                detail.put("expectedKind",expectedKind);detail.put("signalAndGoalMatched",matched);
                                if(value.isPresent())store.append(PurposeLedger.annotate(source,value.orElseThrow()));
                                if(matched)extractionPass++;else extractionMatched=false;
                            } catch(Exception failure){detail.put("failure",failure.toString());extractionMatched=false;}
                            finally {extractionChecks++;LocalOllamaClient.removeWireObserver(call);extracted.add(detail);}
                            histories.add(new HistoryLine("PLAYER",PlayerUnderstandingPromptTest.PLAYER.toString(),"시험 플레이어",step.speech()));
                        }
                        if(!c.behavior().isEmpty()) {
                            var choice=store.append(raw(n++,ActionRecord.Type.QUEST_TRANSITION,Map.of("choice","target_related_path")));
                            var choiceContext=c.behavior().equals("AMBIGUOUS")?PurposeEvidence.Context.AMBIGUOUS:PurposeEvidence.Context.DIRECTED;
                            var ev=action(choice,PurposeEvidence.Signal.CHOOSE,choiceContext,"루브라스 관련 경로의 보상을 선택. 다른 선택지도 존재했음.");
                            store.append(PurposeLedger.annotate(choice,ev));
                            var explore=store.append(raw(n++,ActionRecord.Type.POSITION_SAMPLE,Map.of("coverage","authored_fixture_voluntary_target_search")));
                            var ctx=c.behavior().equals("FORCED")?PurposeEvidence.Context.FORCED:c.behavior().equals("AMBIGUOUS")?PurposeEvidence.Context.AMBIGUOUS:PurposeEvidence.Context.DIRECTED;
                            store.append(PurposeLedger.annotate(explore,action(explore,PurposeEvidence.Signal.EXPLORE,ctx,
                                    ctx==PurposeEvidence.Context.FORCED?"퀘스트 때문에 강제로 해당 지역으로 이동했음.":"별도 사건에서 루브라스의 단서를 찾는 탐사를 선택했음.")));
                        }
                        var snapshot=store.purposes(PlayerUnderstandingPromptTest.PLAYER);sources.addAll(snapshot.evidence());
                        row.put("beforeRestart",snapshot.project(PurposeDialogueTest.WORLD,PlayerUnderstandingPromptTest.PLAYER,x->true));
                    }
                    try(var store=new ActionLedgerStore(root.resolve(name+"-journal"),PurposeDialogueTest.WORLD,
                            new ActionLedgerStore.Limits(8*1024*1024,65536,1000))) {
                        var after=store.purposes(PlayerUnderstandingPromptTest.PLAYER).project(PurposeDialogueTest.WORLD,PlayerUnderstandingPromptTest.PLAYER,x->true);
                        row.put("restartEqual",after.equals(row.get("beforeRestart")));
                    }
                    row.put("extractionMatched",extractionMatched);row.put("extraction",extracted);
                    var permitted=PurposeDialogueContext.prepare(PurposeDialogueTest.WORLD,PlayerUnderstandingPromptTest.PLAYER,1,sources,true,e->true);
                    var request=PlayerUnderstandingPromptTest.request(c.current(),histories,false);
                    var messages=PurposeDialogueContext.attach(PlayerUnderstandingPromptTest.messages(request,List.of()),permitted);
                    row.put("messages",messages);row.put("state",permitted.projection());
                    var attempt=RoomDialogueGrounding.Attempt.initial(messages).withoutUnderstanding();
                    var draft=DialogueGroundingLiveEvaluation.generate(llm,attempt.generationMessages(),settings,row,"generationWire");row.put("draft",draft);
                    String structure=RoomPersonaPrompt.validationIssue(request,draft);row.put("structuralIssue",structure);
                    var review=structure.isEmpty()?DialogueGroundingLiveEvaluation.review(llm,attempt.evidenceMessages(),draft,settings,row,"review"):null;
                    if(!structure.isEmpty()||review!=null&&!review.pass()) {
                        attempt=structure.isEmpty()?attempt.repair(draft,review):attempt.repairStructure(draft,structure);
                        draft=DialogueGroundingLiveEvaluation.generate(llm,attempt.generationMessages(),settings,row,"repairWire");row.put("repaired",draft);
                        structure=RoomPersonaPrompt.validationIssue(request,draft);row.put("finalStructuralIssue",structure);
                        review=structure.isEmpty()?DialogueGroundingLiveEvaluation.review(llm,attempt.evidenceMessages(),draft,settings,row,"finalReview"):null;
                    }
                    if(structure.isEmpty()&&review!=null&&review.pass()){row.put("publishable",draft);publishable++;}
                } catch(Exception failure){row.put("failure",failure.toString());row.put("extraction",extracted);}
                row.put("elapsedMs",(System.nanoTime()-began)/1000000);row.put("semanticHumanStyleGate","REQUIRES_DIRECT_READING_NOT_AUTOMATIC_PASS");
                Files.writeString(output,json.toJson(row),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);attempts++;
                System.out.println("PURPOSE "+name+" extraction="+extractionMatched+" publishable="+row.containsKey("publishable")+"; semantic review pending");
            }
        }
        Files.writeString(root.resolve("summary.json"),json.toJson(Map.of("attempts",attempts,"extractionChecks",extractionChecks,
                "extractionPass",extractionPass,"automaticallyPublishable",publishable,"overallGate","NOT_PASSED_REQUIRES_SEMANTIC_AND_RUNTIME_VERIFICATION")),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
    }
    private static PurposeEvidence action(ActionRecord r,PurposeEvidence.Signal signal,PurposeEvidence.Context context,String description) {
        return new PurposeEvidence(r.worldId(),r.event().actorId(),r.event().occurrenceId(),r.sequence(),PurposeLedger.episodeOf(r),
                new PurposeEvidence.Goal(PurposeEvidence.Kind.MEET,PurposeDialogueTest.TARGET),PurposeEvidence.Origin.GAME_ACTION,
                PurposeEvidence.Attribution.DIRECT_SELF,signal,context,signal==PurposeEvidence.Signal.CHOOSE&&context==PurposeEvidence.Context.DIRECTED,false,description);
    }
    private static ActionRecord.Draft raw(long n,ActionRecord.Type type,Map<String,String> payload) {
        String kind=type==ActionRecord.Type.QUEST_TRANSITION?"QUEST":type==ActionRecord.Type.POSITION_SAMPLE?"LOCATION":"ACTIVITY";
        String target=kind.equals("ACTIVITY")?"mythictrpg:player_understanding":kind.equals("QUEST")?"test:choice":"test:region";
        return new ActionRecord.Draft(UUID.randomUUID(),new UUID(3,n),n,"mythictrpg:detail/"+type.name().toLowerCase(Locale.ROOT),1,
                PlayerUnderstandingPromptTest.PLAYER,new ActionRecord.Subject(kind,target,null),1800000000000L+n,n*12000,0,
                "minecraft:overworld",new ActionRecord.Position(0,64,0),type,"COMPLETED",payload,"mythictrpg:admin_only_unprojected");
    }
}

package com.sande.mythictrpg.ai;

import com.google.gson.GsonBuilder;
import com.sande.mythai.response.memory.DialogueMemoryBridge;
import com.sande.mythictrpg.ai.api.RoomConversationEngine.*;
import com.sande.mythictrpg.ai.intent.ConversationIntent;
import net.minecraft.resources.ResourceLocation;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Opt-in real loopback evaluation. Synthetic scenes, fixed persona; no server or authoritative state writes. */
public final class DialogueGroundingLiveEvaluation {
    private static final UUID PLAYER = UUID.nameUUIDFromBytes("dialogue-system-evaluation".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    private static final ResourceLocation GOD = ResourceLocation.parse("mythictrpg:test_deity");
    private record Case(String id, String input, List<HistoryLine> history, String facts, List<ActionOutcome> outcomes) { }
    private static final com.google.gson.Gson JSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    public static void main(String[] args) throws Exception {
        if (args.length < 2 || !args[1].equals("--execute-loopback")) throw new IllegalArgumentException("Explicit live opt-in required");
        Path root = Files.createDirectories(Path.of(args[0]).toAbsolutePath());
        net.neoforged.fml.loading.FMLPaths.loadAbsolutePaths(root);
        var settings = new AiDialogueConfig.Settings(URI.create("http://127.0.0.1:11434/api/chat"), "gemma4:12b",
                180,600,420,20,260,180,true,96,3,1,16,2,3,3,120,3,false);
        var profile = new AiTestContentRegistryBridge.Profile("시험 신", "합성 시험 장면의 신",
                "자존심과 호기심이 강하다. 친한 사람과는 편하게 농담하지만 부하처럼 부리려 들면 거절한다. 상대가 무서워하면 알아차릴 수 있다.",
                List.of("장난기", "자존심", "눈치"), List.of("솔직함", "자기 뜻"), List.of("P_PLAYFUL"),
                List.of("편한 반말. 필요 이상으로 훈계하지 않는다."), Map.of(), Map.of(), List.of(), List.of());
        var content = new AiTestContentRegistryBridge.ContentSnapshot(profile,List.of(),List.of(),List.of(),List.of(),1);
        var memory = new DialogueMemoryBridge.Turn(null,null,List.of(),List.of(),"",1);
        String unknown = "No item transfer, quest registration, travel or combat has executed. No route or deadline is established.";
        var cases = List.of(
                new Case("actor_correction", "내가 널 돕겠다는 거야. 나 도와달라는 게 아니라.",
                        history("일 많으면 내가 좀 도와줄까?", "어떤 도움이 필요하지?"), unknown, List.of()),
                new Case("playful_role_reversal", "너도 심심하면 내가 퀘스트 하나 줄까? 농담이야.",
                        history("뭐하고 있었어?", "별일 없이 기다리고 있었지."), unknown, List.of()),
                new Case("fear_in_context", "뭔데, 뭐 하려고?", history("또 장난치네", "그럼 눈 좀 감아 봐."),
                        unknown+" Current player has not threatened or challenged the God.", List.of()),
                new Case("company_after_rejection", "그건 싫어. 그냥 너랑 얘기하고 싶다고.",
                        history("심심해", "수수께끼나 할까?", "그런 거 말고", "그럼 광산에 가보는 건 어때?"), unknown, List.of()),
                new Case("apology_and_hurt", "미안. 근데 아까 네 말도 좀 상처였어.",
                        history("넌 관심도 없잖아", "그런 식이면 네 얘기 듣고 싶지 않아."), unknown, List.of()),
                new Case("report_not_custody", "그럼 네가 그 물건을 갖고 있는 거야?",
                        history("그 편지는 읽었어?", "응. 물건을 찾았다는 소식이더군."),
                        unknown+" Game evidence: the NPC read a letter reporting an item was found. Item custody is unknown; no transfer took place.", List.of()),
                new Case("unknown_route", "거기까지 어떻게 가? 안전한 길을 알려줘.",
                        history("목적지는 알아?", "옛 탑을 찾는 거라면 이름은 들어봤지."),
                        unknown+" The NPC knows only the tower name. No position, route, landmark or safety survey is supplied.", List.of()),
                new Case("pending_is_not_success", "그럼 토템 받은 거지?", history("토템 줘", "먼저 확인이 필요해."),unknown,
                        List.of(new ActionOutcome(UUID.nameUUIDFromBytes("pending".getBytes()),"mythictrpg:item_request",ActionStatus.PENDING_CONFIRMATION,
                                "Waiting for player confirmation",Map.of()))),
                new Case("confirmed_success", "고마워, 잘 쓸게.", history("토템 받을래", "받을 준비 됐니?"),
                        "Game evidence: the following receipt confirms an earlier transfer. Do not grant again.",
                        List.of(new ActionOutcome(UUID.nameUUIDFromBytes("success".getBytes()),"mythictrpg:item_request",ActionStatus.EXECUTED,
                                "Item transfer completed",Map.of("item_id","minecraft:totem_of_undying","count","1"))))
        );
        try (var llm = new LocalOllamaClient()) {
            for (var c : cases) {
                Path file = root.resolve(c.id()+".json");
                if (Files.exists(file)) { System.out.println("SKIP existing " + c.id()); continue; }
                var row = new LinkedHashMap<String,Object>();
                row.put("case",c); row.put("model",settings.ollamaModel()); row.put("scope","Synthetic fixed scene, actual prompt/client/review path. Not Minecraft runtime or naturalness proof.");
                var request = new Request(UUID.nameUUIDFromBytes(c.id().getBytes()),1,UUID.randomUUID(),PLAYER,"시험 플레이어",List.of(GOD),GOD,
                        c.input(),c.history(),false,false,false,List.of(new GodState(GOD,"R_FRIENDLY","E_UNASSESSED",c.facts(),null)),false,Set.of(PLAYER),c.outcomes());
                var prompt = new AiTestDialogueAdapter.RoomPrompt(request,memory,Map.of(GOD,content),"");
                try {
                    var intent = prompt.fastIntent();
                    if (intent==null) {
                        row.put("classificationMessages",prompt.classificationMessages());
                        intent = callIntent(llm,prompt.classificationMessages(),settings,row);
                    }
                    row.put("intent",intent);
                    // Old prompt snapshot is frozen at task start; both variants receive the same base intent.
                    var before = BaselineRoomPersonaPrompt20261007.messages(request,content,memory,List.of(),intent,"",false,c.facts(),List.of(),List.of());
                    var after = RoomPersonaPrompt.messages(request,content,memory,List.of(),intent,"",false,c.facts(),List.of(),List.of());
                    row.put("beforeMessages",before); row.put("afterMessages",after);
                    row.put("before",generate(llm,before,settings,row,"beforeWire"));
                    var draft=generate(llm,after,settings,row,"afterWire"); row.put("afterDraft",draft);
                    String structural=RoomPersonaPrompt.validationIssue(request,draft); row.put("structuralIssue",structural);
                    var reasons=RoomDialogueGrounding.reasons(request,after,draft); row.put("reviewReasons",reasons);
                    if (structural.isEmpty() && !reasons.isEmpty()) {
                        var review=review(llm,after,draft,settings,row,"review");
                        if (!review.pass()) {
                            after=RoomDialogueGrounding.repairMessages(after,draft,review);
                            draft=generate(llm,after,settings,row,"repairWire"); row.put("repairedDraft",draft);
                            structural=RoomPersonaPrompt.validationIssue(request,draft);
                            if (!structural.isEmpty()) throw new IllegalStateException("REPAIR_STRUCTURAL_REJECTED: "+structural);
                            // Repair must still pass if the repaired wording continues to require review.
                            var repairedReasons=RoomDialogueGrounding.reasons(request,after,draft);
                            if (!repairedReasons.isEmpty() && !review(llm,after,draft,settings,row,"repairReview").pass())
                                throw new IllegalStateException("GROUNDING_REJECTED");
                        }
                    }
                    if (!structural.isEmpty()) throw new IllegalStateException("STRUCTURAL_REJECTED: "+structural);
                    row.put("publishable",draft);
                } catch (Exception failure) { row.put("failure",failure.toString()); }
                Files.writeString(file,JSON.toJson(row));
                System.out.println("COMPLETED " + c.id()+" publishable="+row.containsKey("publishable"));
            }
        }
    }
    private static ConversationIntent callIntent(LocalOllamaClient llm,List<AiDialogueModels.OllamaMessage> messages,AiDialogueConfig.Settings settings,Map<String,Object> row) throws Exception {
        var id=UUID.randomUUID(); observe(id,row,"classificationWire");
        return llm.submitIntent(id,messages,settings).completion().get(190,TimeUnit.SECONDS).value();
    }
    static AiDialogueModels.StructuredAiResult generate(LocalOllamaClient llm,List<AiDialogueModels.OllamaMessage> messages,AiDialogueConfig.Settings settings,Map<String,Object> row,String key) throws Exception {
        var id=UUID.randomUUID(); observe(id,row,key);
        return llm.submit(id,messages,settings).completion().get(370,TimeUnit.SECONDS).value();
    }
    static RoomDialogueGrounding.Review review(LocalOllamaClient llm,List<AiDialogueModels.OllamaMessage> original,AiDialogueModels.StructuredAiResult draft,AiDialogueConfig.Settings settings,Map<String,Object> row,String key) throws Exception {
        var id=UUID.randomUUID(); observe(id,row,key+"Wire");
        var verdict=llm.submitReview(id,RoomDialogueGrounding.reviewMessages(original,draft),settings).completion().get(190,TimeUnit.SECONDS).value();
        row.put(key,verdict); verdict.validateAgainst(draft); return verdict;
    }
    private static void observe(UUID id,Map<String,Object> row,String key) {
        var trace=new ArrayList<Map<String,String>>(); row.put(key,trace);
        LocalOllamaClient.installWireObserver(id,new LocalOllamaClient.WireObserver() {
            public void onRequest(String body) { trace.add(Map.of("request",body)); }
            public void onResponse(String body) { trace.add(Map.of("response",body)); }
            public void onFailure(Throwable failure) { trace.add(Map.of("error",failure.toString())); }
        });
    }
    private static List<HistoryLine> history(String... lines) {
        var result=new ArrayList<HistoryLine>();
        for(int i=0;i<lines.length;i++) result.add(new HistoryLine(i%2==0?"PLAYER":"NPC",i%2==0?PLAYER.toString():GOD.toString(),i%2==0?"시험 플레이어":"시험 신",lines[i]));
        return List.copyOf(result);
    }
}

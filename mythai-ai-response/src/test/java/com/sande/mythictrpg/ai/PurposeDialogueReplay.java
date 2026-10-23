package com.sande.mythictrpg.ai;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;

/** Replay actual previously generated drafts against their frozen source, including a known unsafe PASS. */
public final class PurposeDialogueReplay {
    public static void main(String[] args)throws Exception {
        if(args.length!=4||!args[2].equals("--execute-loopback")||!Set.of("v2","latest").contains(args[3]))throw new IllegalArgumentException("INPUT OUTPUT --execute-loopback v2|latest");
        Path input=Path.of(args[0]),output=Path.of(args[1]);Files.createDirectories(output);
        var json=new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
        net.neoforged.fml.loading.FMLPaths.loadAbsolutePaths(output.toAbsolutePath());
        var expected=new LinkedHashMap<String,Boolean>();
        expected.put("forced-exploration-1",args[3].equals("latest"));
        if(args[3].equals("latest")) {
            expected.put("behavior-convergence-1",false);
            expected.put("withdraw-then-actions-1",true);
        }
        for(String id:List.of("normal-social-1","normal-offer-1","wish-undecided-1","explicit-decision-1","disown-1","withdraw-1"))expected.put(id,true);
        int matches=0,finalPass=0;
        try(var llm=new LocalOllamaClient()) {
            for(var entry:expected.entrySet()) {
                Path file=output.resolve(entry.getKey()+".json");if(Files.exists(file))throw new IllegalStateException("existing replay evidence");
                var old=JsonParser.parseString(Files.readString(input.resolve(entry.getKey()+".json"))).getAsJsonObject();
                var messages=Arrays.asList(json.fromJson(old.get("messages"),AiDialogueModels.OllamaMessage[].class));
                var draft=json.fromJson(old.get("draft"),AiDialogueModels.StructuredAiResult.class);
                var row=new LinkedHashMap<String,Object>();row.put("sourceFile",input.resolve(entry.getKey()+".json").toString());
                row.put("model","gemma4:12b");row.put("expectedOriginalPass",entry.getValue());row.put("originalMessages",messages);row.put("actualGeneratedDraft",draft);
                try {
                    var review=DialogueGroundingLiveEvaluation.review(llm,messages,draft,PlayerClaimBoundaryEvaluation.settings(),row,"review");
                    row.put("reviewMatched",review.pass()==entry.getValue());if(review.pass()==entry.getValue())matches++;
                    if(!review.pass()) {
                        draft=DialogueGroundingLiveEvaluation.generate(llm,RoomDialogueGrounding.repairMessages(messages,draft,review),PlayerClaimBoundaryEvaluation.settings(),row,"repairWire");
                        row.put("actualRepairedDraft",draft);
                        review=DialogueGroundingLiveEvaluation.review(llm,messages,draft,PlayerClaimBoundaryEvaluation.settings(),row,"finalReview");
                    }
                    row.put("automaticallyPublishable",review.pass());if(review.pass())finalPass++;
                }catch(Exception failure){row.put("failure",failure.toString());}
                Files.writeString(file,json.toJson(row),StandardOpenOption.CREATE_NEW);
                System.out.println("PURPOSE_REPLAY "+entry.getKey()+" reviewMatched="+row.get("reviewMatched")+" publishable="+row.get("automaticallyPublishable"));
            }
        }
        Files.writeString(output.resolve("summary.json"),json.toJson(Map.of("cases",expected.size(),"reviewMatches",matches,
                "automaticallyPublishable",finalPass,"gate","MANUAL_SEMANTIC_REVIEW_REQUIRED")),StandardOpenOption.CREATE_NEW);
        if(matches!=expected.size()||finalPass!=expected.size())throw new AssertionError("Purpose replay gate failed; no deployment");
    }
}

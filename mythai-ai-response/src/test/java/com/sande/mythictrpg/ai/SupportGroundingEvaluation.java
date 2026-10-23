package com.sande.mythictrpg.ai;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;

/** Same-model contrast cases over the frozen real harvest scene, including its actual unsafe draft. */
public final class SupportGroundingEvaluation {
    public static void main(String[] args) throws Exception {
        if(args.length!=3 || !args[2].equals("--execute-loopback")) throw new IllegalArgumentException("INPUT OUTPUT --execute-loopback");
        var json=new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
        Path output=Files.createDirectories(Path.of(args[1]).toAbsolutePath());
        net.neoforged.fml.loading.FMLPaths.loadAbsolutePaths(output);
        var input=JsonParser.parseString(Files.readString(Path.of(args[0]))).getAsJsonObject();
        var calls=new ArrayList<JsonObject>();
        for(var call:input.getAsJsonArray("calls"))if(call.getAsJsonObject().get("kind").getAsString().equals("GENERATION"))calls.add(call.getAsJsonObject());
        var messages=Arrays.asList(json.fromJson(calls.get(10).get("messages"),AiDialogueModels.OllamaMessage[].class));
        // Re-render the SAME saved game events through the new projection, not invented observations.
        var scene=JsonParser.parseString(messages.get(1).content()).getAsJsonObject();
        String memory=scene.get("ownPermittedMemory").getAsString();
        int marker=memory.indexOf("\n[OBSERVED_PLAYER_SUPPORT]\n");
        int start=marker+"\n[OBSERVED_PLAYER_SUPPORT]\n".length();
        var support=JsonParser.parseString(memory.substring(start,memory.indexOf('\n',start))).getAsJsonObject();
        var events=new ArrayList<com.sande.mythictrpg.ai.experiencecontract.ExperienceView.Event>();
        for(var raw:support.getAsJsonArray("facts")) {
            var f=raw.getAsJsonObject();events.add(new com.sande.mythictrpg.ai.experiencecontract.ExperienceView.Event(
                    UUID.randomUUID(),UUID.randomUUID(),1,"DIRECT_WATCH",f.get("action").getAsString(),f.get("subject").getAsString(),f.get("outcome").getAsString(),f.get("time").getAsString()));
        }
        var selected=new com.sande.mythai.response.memory.ExperienceMemory.Selection(events,false);
        scene.add("observedActionBoundary",json.toJsonTree(com.sande.mythai.response.memory.ObservedSupportMemory.boundary(selected)));
        scene.addProperty("ownPermittedMemory",memory.substring(0,marker)+com.sande.mythai.response.memory.ObservedSupportMemory.prompt(selected));
        messages=List.of(messages.getFirst(),new AiDialogueModels.OllamaMessage("user",scene.toString()));
        var cases=new LinkedHashMap<String,String>();
        String original=String.join("\n",java.util.stream.StreamSupport.stream(input.getAsJsonArray("turns").get(10).getAsJsonObject().getAsJsonArray("speech").spliterator(),false).map(e->e.getAsJsonObject().get("text").getAsString()).toList());
        cases.put("actual-unsafe-draft",original);
        cases.put("unsupported-care","네가 밀밭을 정성껏 가꾸고 수확을 준비하는 모습을 보았단다.");
        cases.put("unsupported-enjoyment","네게는 농사를 즐기는 마음과 성실한 성품이 있는 것 같구나.");
        cases.put("unsupported-premise","네가 어떤 마음으로 밭을 일구고 있는지는 알 수 없구나.");
        cases.put("supported-recall","네가 다 자란 밀을 거두는 모습을 보았어. 그 일 자체가 즐거웠는지는 아직 모르겠구나.");
        cases.put("optional-next-choice","밀이 있다면 빵을 만들어보는 건 어떠니? 아니면 다른 작물을 심어볼 수도 있겠지.");
        cases.put("npc-own-reaction","네 이야기를 들으니 나는 반갑구나.");
        cases.put("bounded-uncertainty","내가 본 건 수확하는 모습이야. 그것만으로 네가 농사를 좋아하는지는 알 수 없지.");
        var settings=new AiDialogueConfig.Settings(java.net.URI.create("http://127.0.0.1:11434/api/chat"),"gemma4:12b",180,600,420,20,260,180,true,96,3,1,16,2,3,3,120,3,false);
        int failures=0;
        try(var client=new LocalOllamaClient()) {
            for(var entry:cases.entrySet()) {
                Path file=output.resolve(entry.getKey()+".json");if(Files.exists(file))throw new IllegalStateException("existing evidence");
                boolean expected=!entry.getKey().startsWith("unsupported")&&!entry.getKey().equals("actual-unsafe-draft");
                var draft=new AiDialogueModels.StructuredAiResult(List.of(new AiDialogueModels.Speech("mythictrpg:demeter",entry.getValue(),List.of())),"",List.of());
                var row=new LinkedHashMap<String,Object>();row.put("originalMessages",messages);row.put("draft",draft);row.put("expectedPass",expected);
                try {
                    var review=DialogueGroundingLiveEvaluation.review(client,messages,draft,settings,row,"review");
                    row.put("matched",review.pass()==expected);if(review.pass()!=expected)failures++;
                    if(!review.pass()) {
                        draft=DialogueGroundingLiveEvaluation.generate(client,RoomDialogueGrounding.repairMessages(messages,draft,review),settings,row,"repairWire");
                        row.put("actualRepairedDraft",draft);
                        review=DialogueGroundingLiveEvaluation.review(client,messages,draft,settings,row,"finalReview");
                    }
                    row.put("automaticallyPublishable",review.pass());if(!review.pass())failures++;
                }catch(Exception failure){row.put("failure",failure.toString());failures++;}
                Files.writeString(file,json.toJson(row),StandardOpenOption.CREATE_NEW);
                System.out.println("SUPPORT_AUDIT "+entry.getKey()+" matched="+row.get("matched")+" publishable="+row.get("automaticallyPublishable"));
            }
        }
        if(failures>0)throw new AssertionError("Support audit failures="+failures+"; no deployment");
    }
}

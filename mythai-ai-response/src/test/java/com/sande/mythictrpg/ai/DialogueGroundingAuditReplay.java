package com.sande.mythictrpg.ai;

import com.google.gson.*;
import java.nio.file.*;
import java.net.URI;
import java.util.*;

/** Review the exact earlier candidates again, not hand-picked newly generated successes. Explicit opt-in only. */
public final class DialogueGroundingAuditReplay {
    public static void main(String[] args) throws Exception {
        if(args.length<3||!args[2].equals("--execute-loopback")) throw new IllegalArgumentException("Live opt-in required");
        var input=Path.of(args[0]).toAbsolutePath(); var output=Files.createDirectories(Path.of(args[1]).toAbsolutePath());
        net.neoforged.fml.loading.FMLPaths.loadAbsolutePaths(output);
        var json=new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
        var settings=new AiDialogueConfig.Settings(URI.create("http://127.0.0.1:11434/api/chat"),"gemma4:12b",
                180,600,420,20,260,180,true,96,3,1,16,2,3,3,120,3,false);
        try(var llm=new LocalOllamaClient();var files=Files.list(input)) {
            for(var file:files.filter(f->f.toString().endsWith(".json")).sorted().toList()) {
                var destination=output.resolve(file.getFileName()); if(Files.exists(destination)) continue;
                var source=JsonParser.parseString(Files.readString(file)).getAsJsonObject();
                if(!source.has("afterDraft")) continue;
                var messages=List.of(json.fromJson(source.get("afterMessages"),AiDialogueModels.OllamaMessage[].class));
                var draft=json.fromJson(source.get("afterDraft"),AiDialogueModels.StructuredAiResult.class);
                var row=new LinkedHashMap<String,Object>(); row.put("source",file.toString()); row.put("unchangedDraft",draft);
                try {
                    var verdict=DialogueGroundingLiveEvaluation.review(llm,messages,draft,settings,row,"review");
                    if(!verdict.pass()) {
                        var repair=RoomDialogueGrounding.repairMessages(messages,draft,verdict);
                        draft=DialogueGroundingLiveEvaluation.generate(llm,repair,settings,row,"repairWire"); row.put("repairedDraft",draft);
                        if(!DialogueGroundingLiveEvaluation.review(llm,repair,draft,settings,row,"repairReview").pass())
                            throw new IllegalStateException("GROUNDING_REJECTED");
                    }
                    row.put("acceptedCandidate",draft);
                }catch(Exception failure) { row.put("failure",failure.toString()); }
                Files.writeString(destination,json.toJson(row));
                System.out.println("AUDITED "+file.getFileName()+" accepted="+row.containsKey("acceptedCandidate"));
            }
        }
    }
}

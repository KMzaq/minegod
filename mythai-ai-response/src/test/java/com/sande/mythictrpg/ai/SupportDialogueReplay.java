package com.sande.mythictrpg.ai;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;

/** Opt-in replay of frozen real room inputs. Never reads/writes an operating world. */
public final class SupportDialogueReplay {
    public static void main(String[] args) throws Exception {
        if (args.length != 4 || !args[2].equals("--execute-loopback"))
            throw new IllegalArgumentException("INPUT OUTPUT --execute-loopback turnNumbers");
        var json = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
        Path output = Files.createDirectories(Path.of(args[1]).toAbsolutePath());
        net.neoforged.fml.loading.FMLPaths.loadAbsolutePaths(output);
        var input = JsonParser.parseString(Files.readString(Path.of(args[0]))).getAsJsonObject();
        var generations = new ArrayList<JsonElement>();
        for (var call : input.getAsJsonArray("calls"))
            if (call.getAsJsonObject().get("kind").getAsString().equals("GENERATION")) generations.add(call);
        var settings = new AiDialogueConfig.Settings(java.net.URI.create("http://127.0.0.1:11434/api/chat"),
                "gemma4:12b",180,600,420,20,260,180,true,96,3,1,16,2,3,3,120,3,false);
        int failures = 0;
        try (var llm = new LocalOllamaClient()) {
            for (String number : args[3].split(",")) {
                int index = Integer.parseInt(number) - 1;
                Path file = output.resolve("turn-" + number + ".json");
                if (Files.exists(file)) throw new IllegalStateException("Do not overwrite replay evidence");
                var messages = Arrays.asList(json.fromJson(generations.get(index).getAsJsonObject().get("messages"), AiDialogueModels.OllamaMessage[].class));
                var row = new LinkedHashMap<String,Object>();
                row.put("messages",messages); row.put("model",settings.ollamaModel());
                try {
                    var draft = DialogueGroundingLiveEvaluation.generate(llm,messages,settings,row,"generationWire");
                    row.put("draft",draft);
                    var review = DialogueGroundingLiveEvaluation.review(llm,messages,draft,settings,row,"review");
                    if (!review.pass()) {
                        draft = DialogueGroundingLiveEvaluation.generate(llm,RoomDialogueGrounding.repairMessages(messages,draft,review),settings,row,"repairWire");
                        row.put("repairedDraft",draft);
                        review = DialogueGroundingLiveEvaluation.review(llm,messages,draft,settings,row,"finalReview");
                    }
                    row.put("automaticallyPublishable",review.pass());
                    if (!review.pass()) failures++;
                } catch (Exception failure) { row.put("failure",failure.toString()); failures++; }
                Files.writeString(file,json.toJson(row),StandardOpenOption.CREATE_NEW);
                System.out.println("SUPPORT_REPLAY turn="+number+" publishable="+row.get("automaticallyPublishable")+" failure="+row.get("failure"));
            }
        }
        if (failures>0) throw new AssertionError("Replay failures="+failures+"; manual semantic review also required");
    }
}

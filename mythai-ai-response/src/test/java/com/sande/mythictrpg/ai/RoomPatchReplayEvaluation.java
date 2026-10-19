package com.sande.mythictrpg.ai;

import com.google.gson.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Replays immutable actual v27 failures; this is regression, not an unseen or independent quality set. */
public final class RoomPatchReplayEvaluation {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    public static void main(String[] args) throws Exception {
        if (args.length != 3 || !args[2].equals("--execute-loopback")) throw new IllegalArgumentException("WORKSPACE OUTPUT --execute-loopback");
        Path workspace = Path.of(args[0]), output = Files.createDirectories(Path.of(args[1]));
        net.neoforged.fml.loading.FMLPaths.loadAbsolutePaths(output);
        var inputs = List.of("baseline/wish", "warm/retracted", "guarded/wish", "guarded/decision");
        save(output.resolve("manifest.json"), Map.of("model", "gemma4:12b", "inputs", inputs,"independentFrameEvaluation",Boolean.getBoolean("mythai.eval.independentFrames"),
                "scope", "Actual v27 repair regression replay; no new initial generation, game execution or deployment"));
        var rows = new ArrayList<Map<String, Object>>();
        try (var llm = new LocalOllamaClient(Boolean.getBoolean("mythai.eval.independentFrames"))) {
            for (String input : inputs) {
                String[] parts = input.split("/");
                Path source = workspace.resolve("검토결과/player-understanding-20261018/claim-v27-generated-" + parts[0]).resolve(parts[1] + ".json");
                byte[] bytes = Files.readAllBytes(source); var raw = JsonParser.parseString(Files.readString(source)).getAsJsonObject();
                var evidence = List.of(JSON.fromJson(raw.get("evidence"), AiDialogueModels.OllamaMessage[].class));
                var before = JSON.fromJson(raw.get("draft"), AiDialogueModels.StructuredAiResult.class);
                var feedback = JSON.fromJson(raw.get("review"), RoomDialogueGrounding.Review.class);
                feedback.validateAgainst(before); if (feedback.pass()) throw new IllegalStateException("Replay requires original rejected draft");
                var row = new LinkedHashMap<String, Object>(); row.put("id", input); row.put("before", before);
                row.put("inputFile", source.toString()); row.put("inputSha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
                try {
                    var edited = DialogueGroundingLiveEvaluation.generate(llm, RoomDialogueGrounding.repairMessages(evidence, before, feedback), PlayerClaimBoundaryEvaluation.settings(), row, "repairWire");
                    row.put("edited", edited);
                    var review = DialogueGroundingLiveEvaluation.review(llm, evidence, edited, PlayerClaimBoundaryEvaluation.settings(), row, "finalReview");
                    row.put("reviewAccepted", review.pass());
                } catch (Exception failure) { row.put("failure", failure.toString()); }
                rows.add(row); save(output.resolve(parts[0] + "-" + parts[1] + ".json"), row);
                System.out.println("REPLAY " + input + " reviewAccepted=" + row.get("reviewAccepted") + " failure=" + row.get("failure"));
            }
        }
        var summary = Map.of("rows", rows.size(), "reviewAccepted", rows.stream().filter(r -> Boolean.TRUE.equals(r.get("reviewAccepted"))).count(),
                "failures", rows.stream().filter(r -> r.containsKey("failure")).count(), "scope", "Repair/review only, not game publication; manual quality check required");
        save(output.resolve("summary.json"), summary); System.out.println(JSON.toJson(summary));
        if (rows.stream().anyMatch(r -> !Boolean.TRUE.equals(r.get("reviewAccepted")))) throw new AssertionError("Repair replay gate failed; no deployment");
    }
    private static void save(Path path, Object value) throws Exception { Files.writeString(path, JSON.toJson(value), StandardOpenOption.CREATE_NEW); }
}

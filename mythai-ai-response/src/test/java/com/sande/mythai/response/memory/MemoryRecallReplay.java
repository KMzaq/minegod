package com.sande.mythai.response.memory;

import com.google.gson.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/** Opt-in generation-only A/B replay against localhost. Never starts Minecraft or executes proposals. */
public final class MemoryRecallReplay {
    public static void main(String[] args) throws Exception {
        List<JsonObject> requests = new ArrayList<>();
        boolean requestLine = false;
        for (String line : Files.readAllLines(Path.of(args[0]))) {
            if (requestLine) requests.add(JsonParser.parseString(line).getAsJsonObject());
            requestLine = line.endsWith("][GENERATION_REQUEST_JSON]");
        }
        JsonObject config = JsonParser.parseString(Files.readString(Path.of(args[1]))).getAsJsonObject();
        URI endpoint = URI.create(config.get("ollamaChatUrl").getAsString());
        if (!Set.of("127.0.0.1", "localhost", "[::1]").contains(endpoint.getHost()))
            throw new IllegalArgumentException("Replay must use a local model, not upload dialogue");
        JsonObject original = requests.getLast();
        if (!original.get("model").getAsString().equals(config.get("ollamaModel").getAsString()))
            throw new IllegalArgumentException("Configured model differs from captured request");
        String originalUser = original.getAsJsonArray("messages").get(1).getAsJsonObject().get("content").getAsString();
        if (!originalUser.contains("난 수영을 못해") || !originalUser.contains("수영을 해보려해"))
            throw new IllegalArgumentException("Expected the reported swim recall fixture");
        Path output = Path.of(args[2]);
        Files.createDirectories(output);
        List<Map<String, Object>> report = new ArrayList<>();
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        UUID owner = UUID.randomUUID();
        var entry = new MemoryJournal.Entry(UUID.randomUUID(), new MemoryJournal.Key(UUID.randomUUID(), "mythictrpg:fortuna", owner),
                UUID.randomUUID(), 1, MemoryJournal.Source.PLAYER_STATEMENT, Set.of(owner), 1789387956312L, "난 수영을 못해", false);
        String prefix = DialogueMemoryBridge.prompt(List.of(entry), List.of());
        for (String name : List.of("before-1", "after-1", "after-2", "before-2", "paraphrase", "correction")) {
            JsonObject request = original.deepCopy();
            JsonArray messages = request.getAsJsonArray("messages");
            boolean improved = !name.startsWith("before");
            if (improved) {
                String system = messages.get(0).getAsJsonObject().get("content").getAsString();
                messages.get(0).getAsJsonObject().addProperty("content", MemoryRecallPolicy.generationSystem(system, true));
                String user = prefix + originalUser.substring(originalUser.indexOf("[CURRENT_PLAYER_MESSAGE]"));
                String current = switch (name) {
                    case "paraphrase" -> "수영을 한번 시도해 보고 싶어";
                    case "correction" -> "전에 수영을 못한다고 했는데 이제 배워서 할 줄 알아";
                    default -> "수영을 해보려해";
                };
                user = user.replace("[CURRENT_PLAYER_MESSAGE]\n수영을 해보려해", "[CURRENT_PLAYER_MESSAGE]\n" + current);
                messages.get(1).getAsJsonObject().addProperty("content", user);
            }
            request.getAsJsonObject("options").addProperty("seed", name.endsWith("2") ? 72 : 41);
            long start = System.nanoTime();
            HttpResponse<String> response = client.send(HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(request.toString())).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw new IllegalStateException("Local model HTTP " + response.statusCode());
            JsonObject wire = JsonParser.parseString(response.body()).getAsJsonObject();
            String raw = wire.getAsJsonObject("message").get("content").getAsString();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("case", name); row.put("elapsed_ms", (System.nanoTime() - start) / 1_000_000);
            row.put("done_reason", wire.get("done_reason")); row.put("prompt_tokens", wire.get("prompt_eval_count"));
            row.put("raw", raw);
            try {
                JsonObject result = JsonParser.parseString(raw).getAsJsonObject();
                boolean valid = result.has("speech") && result.getAsJsonArray("speech").size() == 1
                        && result.getAsJsonArray("speech").get(0).getAsJsonObject().get("speakerId").getAsString().equals("mythictrpg:fortuna")
                        && result.has("proposals") && result.getAsJsonArray("proposals").isEmpty();
                row.put("valid_speaker_and_no_actions", valid);
                System.out.println(name + " (" + row.get("elapsed_ms") + "ms): " + raw.replace('\n', ' '));
            } catch (RuntimeException malformed) { row.put("parse_error", malformed.getClass().getSimpleName()); }
            report.add(row);
            Files.writeString(output.resolve("report.json"), new GsonBuilder().setPrettyPrinting().create().toJson(report));
        }
        System.out.println("Generation-only replay complete (human quality assessment required): " + output.resolve("report.json"));
    }
}

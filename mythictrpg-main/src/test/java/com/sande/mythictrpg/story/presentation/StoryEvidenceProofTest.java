package com.sande.mythictrpg.story.presentation;

import com.google.gson.Gson;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.sande.mythictrpg.ai.api.RoomEvidenceReference;
import com.sande.mythictrpg.story.presentation.StoryRoomConversationService.Proof;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

/** Pure V1 decoder regressions. No world, registry, source mutation, LLM or disclosure authority grant. */
public final class StoryEvidenceProofTest {
    private static final Gson JSON = new Gson();
    private static final String HASH = "0123456789abcdef".repeat(4);
    private static int checks;

    public static void main(String[] args) {
        roundTrips();
        fieldShapes();
        malformedSyntax();
        typeInvariants();
        bounds();
        check(StoryRoomConversationService.decodeProof(null).isEmpty(), "null reference");
        for (String kind : List.of("STORY_DISCLOSURE_V2", "STORY_DISCLOSURE_V0", "STORY_DISCLOSURE_V1 ",
                "story_disclosure_v1", "CONTENT_DISCLOSURE_V1"))
            check(StoryRoomConversationService.decodeProof(new RoomEvidenceReference(kind, json(fact()))).isEmpty(),
                    "unknown kind/version");
        System.out.println("StoryEvidenceProofTest: PASS (" + checks + " checks; no server or model)");
    }

    private static void roundTrips() {
        for (Proof proof : List.of(fact(), cover(), hook(), presentation(),
                copy(fact(), "", ""), copy(fact(), "PLAYER", "849ecb7b-94a3-4a36-bfc3-1e2492607579"),
                copy(fact(), "TEAM", "team-key"))) {
            check(decode(json(proof)).filter(proof::equals).isPresent(), "legacy writer shape round trip");
            JsonObject reversed = new JsonObject();
            var fields = JSON.toJsonTree(proof).getAsJsonObject();
            fields.keySet().stream().sorted(java.util.Comparator.reverseOrder()).forEach(key -> reversed.add(key, fields.get(key)));
            check(decode(" \n\t" + reversed + "\r ").filter(proof::equals).isPresent(), "field order and JSON whitespace");
        }
        String source = "event/\"quoted\"\\source\nline\t한글😀";
        String valid = change(fact(), "source", new JsonPrimitive(source));
        check(decode(valid).orElseThrow().source().equals(source), "escaped string contents preserved without coercion");
        check(decode(valid.replace("😀", "\\uD83D\\uDE00")).isPresent(), "escaped surrogate pair");
        check(decode(json(fact()).replace("mythictrpg:", "mythictrpg\\u003a")).isPresent(), "valid unicode escape in identifier");
        check(decode(json(fact()).replace("\"type\"", "\"\\u0074ype\"")).isPresent(), "escaped key exact name");
        check(decode(change(fact(), "level", new JsonPrimitive(32))).isPresent(), "maximum FACT level");
        check(decode(change(cover(), "level", new JsonPrimitive(8))).isPresent(), "maximum COVER line");
        check(decode(change(fact(), "fingerprint", new JsonPrimitive("a".repeat(64)))).isPresent(),
                "well-shaped changed fingerprint parses only; live authority still rechecks it");
    }

    private static void fieldShapes() {
        JsonObject original = JSON.toJsonTree(fact()).getAsJsonObject();
        for (String field : original.keySet()) {
            JsonObject missing = original.deepCopy(); missing.remove(field);
            denied(missing.toString(), "missing field");
            denied(change(fact(), field, JsonNull.INSTANCE), "null field");
            denied(change(fact(), field, new JsonObject()), "object field");
            denied(change(fact(), field, new com.google.gson.JsonArray()), "array field");
            String duplicate = json(fact()).replaceFirst("\\{", "{\"" + field + "\":" + original.get(field) + ",");
            denied(duplicate, "duplicate field even when equal");
            if (!field.equals("level") && !field.equals("factValue")) {
                denied(change(fact(), field, new JsonPrimitive(1)), "numeric string coercion");
                denied(change(fact(), field, new JsonPrimitive(true)), "boolean string coercion");
            }
        }
        for (String extra : List.of("version", "schemaVersion", "unknown", "Type")) {
            JsonObject value = original.deepCopy(); value.addProperty(extra, 1);
            denied(value.toString(), "unknown field/version");
        }
        denied(change(fact(), "level", new JsonPrimitive("1")), "quoted integer");
        denied(change(fact(), "factValue", new JsonPrimitive("true")), "quoted boolean");
        denied(change(fact(), "factValue", new JsonPrimitive(1)), "numeric boolean");
        denied(json(fact()).replace("\"factValue\":true", "\"factValue\":TRUE"), "uppercase boolean");
        denied(json(fact()).replace("\"type\":\"FACT\"", "\"type\":\"FACT\",\"\\u0074ype\":\"FACT\""),
                "escaped duplicate key");
    }

    private static void malformedSyntax() {
        for (String text : List.of("{}", "[]", "null", "true", "1", "\"text\"", "{", "}",
                json(fact()) + "{}", json(fact()) + " true", "/*comment*/" + json(fact()),
                json(fact()).replace("\"type\":", "type:"), json(fact()).replace("\"FACT\"", "'FACT'"),
                json(fact()).replace("\"FACT\"", "FACT"), json(fact()).replace("\"FACT\"", "\"FA\nCT\""),
                json(fact()).replace("\"FACT\"", "\"FA\\qCT\""), json(fact()).replace("\"FACT\"", "\"\\uZZZZ\""),
                json(fact()).replace("\"FACT\"", "\"\\u１２３４\""), json(fact()) + "\u00a0",
                "\ufeff" + json(fact()), json(fact()).substring(0, json(fact()).length() - 1) + ",}"))
            denied(text, "invalid JSON syntax");
        for (String number : List.of("-1", "-0", "+1", "01", "1.0", "1e0", "1E+0", "NaN", "Infinity", "2147483648", ""))
            denied(json(fact()).replace("\"level\":1", "\"level\":" + number), "non-wire integer");
        denied(change(fact(), "source", new JsonPrimitive("bad\ud800")), "unpaired high surrogate");
        denied(change(fact(), "source", new JsonPrimitive("bad\udc00")), "unpaired low surrogate");
        denied(change(fact(), "source", new JsonPrimitive("bad\ud800x")), "surrogate followed by non-low");
        denied(json(fact()).replace("\"FACT\"", "[".repeat(2_000) + "0" + "]".repeat(2_000)), "nested input never recurses");
    }

    private static void typeInvariants() {
        for (String type : List.of("UNKNOWN", "fact", "FACT ", "", "STORY_DISCLOSURE_V2"))
            denied(change(fact(), "type", new JsonPrimitive(type)), "unknown proof type");
        for (String field : List.of("actor", "god", "id", "policy")) {
            for (String value : List.of("", "missing_namespace", "Mythic:upper", "mythictrpg:bad space", "a:b:c"))
                denied(change(fact(), field, new JsonPrimitive(value)), "noncanonical identifier");
        }
        for (String digest : List.of("", "a".repeat(63), "a".repeat(65), "A".repeat(64), "z".repeat(64)))
            denied(change(fact(), "fingerprint", new JsonPrimitive(digest)), "noncanonical fingerprint");
        denied(change(fact(), "level", new JsonPrimitive(0)), "zero FACT level");
        denied(change(fact(), "level", new JsonPrimitive(33)), "oversized FACT level");
        denied(change(cover(), "level", new JsonPrimitive(9)), "oversized COVER line");
        denied(change(fact(), "cover", new JsonPrimitive("mythictrpg:cover")), "FACT unused cover");
        denied(change(cover(), "cover", new JsonPrimitive("")), "COVER requires cover id");
        for (String source : List.of("", " ", " leading", "trailing ", "x".repeat(257)))
            denied(change(fact(), "source", new JsonPrimitive(source)), "source runtime bounds");
        for (String scope : List.of("UNKNOWN", "server", "PLAYER "))
            denied(change(fact(), "scopeType", new JsonPrimitive(scope)), "unknown scope enum");
        denied(change(fact(), "scopeKey", new JsonPrimitive("")), "scope key required");
        denied(change(fact(), "scopeKey", new JsonPrimitive("x".repeat(129))), "scope key bound");
        denied(change(fact(), "scopeType", new JsonPrimitive("")), "orphan scope key");
        for (Proof proof : List.of(hook(), presentation())) {
            denied(change(proof, "level", new JsonPrimitive(1)), "non-fact unused level");
            denied(change(proof, "factValue", new JsonPrimitive(true)), "non-fact unused factValue");
            for (String field : List.of("cover", "policy", "scopeType", "scopeKey"))
                denied(change(proof, field, new JsonPrimitive("mythictrpg:unused")), "unused type field");
        }
        denied(change(hook(), "source", new JsonPrimitive("instance")), "HOOK unused source");
        denied(change(presentation(), "source", new JsonPrimitive("")), "PRESENTATION source required");
    }

    private static void bounds() {
        String value = json(fact());
        int maximum = StoryRoomConversationService.MAX_PROOF_BYTES;
        check(decode(value + " ".repeat(maximum - value.length())).isPresent(), "exact ASCII payload byte cap");
        denied(value + " ".repeat(maximum - value.length() + 1), "ASCII payload above cap");
        String unicode = change(fact(), "source", new JsonPrimitive("😀".repeat(128)));
        int bytes = unicode.getBytes(StandardCharsets.UTF_8).length;
        check(decode(unicode + " ".repeat(maximum - bytes)).isPresent(), "exact UTF-8 payload byte cap");
        String aboveBytes = unicode + " ".repeat(maximum - bytes + 1);
        check(aboveBytes.length() < maximum, "UTF-16 length alone would admit oversized payload");
        denied(aboveBytes, "UTF-8 payload above cap");
        check(decode(change(fact(), "source", new JsonPrimitive("x".repeat(256)))).isPresent(), "source exact bound");
        check(decode(change(fact(), "scopeKey", new JsonPrimitive("x".repeat(128)))).isPresent(), "scope key exact bound");
    }

    private static Proof fact() { return new Proof("FACT", "mythictrpg:actor", "mythictrpg:god", "mythictrpg:fact", 1,
            "", "mythictrpg:policy", "instance-1", "SERVER", "server", true, HASH); }
    private static Proof cover() { return new Proof("COVER", "mythictrpg:actor", "mythictrpg:god", "mythictrpg:fact", 1,
            "mythictrpg:cover", "mythictrpg:policy", "instance-1", "SERVER", "server", false, HASH); }
    private static Proof hook() { return new Proof("HOOK", "mythictrpg:actor", "mythictrpg:god", "mythictrpg:hook", 0,
            "", "", "", "", "", false, HASH); }
    private static Proof presentation() { return new Proof("PRESENTATION", "mythictrpg:actor", "mythictrpg:god", "mythictrpg:presentation", 0,
            "", "", "instance-1", "", "", false, HASH); }
    private static Proof copy(Proof value, String type, String key) { return new Proof(value.type(), value.actor(), value.god(), value.id(),
            value.level(), value.cover(), value.policy(), value.source(), type, key, value.factValue(), value.fingerprint()); }
    private static String json(Proof proof) { return JSON.toJson(proof); }
    private static String change(Proof proof, String field, com.google.gson.JsonElement value) {
        JsonObject object = JsonParser.parseString(json(proof)).getAsJsonObject(); object.add(field, value); return object.toString();
    }
    private static Optional<Proof> decode(String value) {
        return StoryRoomConversationService.decodeProof(new RoomEvidenceReference(StoryRoomConversationService.EVIDENCE_KIND, value));
    }
    private static void denied(String value, String reason) { check(decode(value).isEmpty(), reason); }
    private static void check(boolean value, String reason) { checks++; if (!value) throw new AssertionError(reason); }
}

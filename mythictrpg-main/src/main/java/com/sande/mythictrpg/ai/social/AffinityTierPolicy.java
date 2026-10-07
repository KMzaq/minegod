package com.sande.mythictrpg.ai.social;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/** Game-owned interpretation of existing affinity, not a new relationship store or balance rule. */
public record AffinityTierPolicy(long revision, String source, List<Integer> thresholds) {
    public static final String CONFIG_PATH = "config/mythictrpg/ai-affinity-tiers.json";
    public static final List<String> TAGS = List.of("R_EXTREME_HOSTILE", "R_HOSTILE", "R_DISLIKE", "R_WARY",
            "R_NEUTRAL", "R_FAVORABLE", "R_FRIENDLY", "R_TRUSTED", "R_DEEP_BOND");
    private static final int MAX_BYTES = 4096;

    public AffinityTierPolicy {
        thresholds = List.copyOf(thresholds);
        if (revision < 1 || !Set.of("TEST_DEFAULTS", "CONFIGURED").contains(source) || thresholds.size() != 8)
            throw new IllegalArgumentException("Affinity policy requires a revision and eight thresholds");
        int previous = -1001;
        for (int threshold : thresholds) {
            if (threshold <= previous || threshold > 1000)
                throw new IllegalArgumentException("Affinity thresholds must increase within -1000..1000");
            previous = threshold;
        }
        if (thresholds.get(3) >= 0 || thresholds.get(4) <= 0)
            throw new IllegalArgumentException("Affinity policy requires four negative and four positive thresholds");
    }

    /** Provisional test interpretation only. Server authors may replace all boundaries in CONFIG_PATH. */
    public static AffinityTierPolicy testDefaults() {
        return new AffinityTierPolicy(1, "TEST_DEFAULTS", List.of(-900, -600, -300, -100, 100, 300, 600, 900));
    }

    /** Four negative upper-inclusive bounds followed by four positive lower-inclusive bounds. */
    public String tier(int affinity) {
        if (affinity < -1000 || affinity > 1000) throw new IllegalArgumentException("Affinity outside -1000..1000");
        for (int index = 0; index < 4; index++) if (affinity <= thresholds.get(index)) return TAGS.get(index);
        for (int index = 7; index >= 4; index--) if (affinity >= thresholds.get(index)) return TAGS.get(index + 1);
        return TAGS.get(4);
    }

    /** Read on capture and revalidation, so a changed policy cannot validate an earlier reply. Never rewrites config. */
    public static AffinityTierPolicy load(Path file) {
        if (Files.notExists(file)) return testDefaults();
        try (var input = Files.newInputStream(file)) {
            byte[] bytes = input.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("Affinity policy exceeds 4096 bytes");
            return parse(new String(bytes, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException invalid) {
            throw new IllegalStateException("Invalid affinity tier policy at " + file + ": " + invalid.getMessage(), invalid);
        }
    }

    public static AffinityTierPolicy parse(String text) {
        if (text == null || text.length() > MAX_BYTES) throw new IllegalArgumentException("Invalid affinity policy size");
        var root = JsonParser.parseString(text).getAsJsonObject();
        if (!root.keySet().equals(Set.of("schemaVersion", "revision", "thresholds"))
                || integer(root.get("schemaVersion")) != 1)
            throw new IllegalArgumentException("Affinity policy requires schemaVersion=1, revision, thresholds only");
        var thresholds = root.getAsJsonArray("thresholds");
        if (thresholds.size() != 8) throw new IllegalArgumentException("Affinity policy requires eight thresholds");
        return new AffinityTierPolicy(integer(root.get("revision")), "CONFIGURED",
                thresholds.asList().stream().map(AffinityTierPolicy::integer).toList());
    }

    private static int integer(JsonElement value) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
            throw new IllegalArgumentException("Affinity policy numbers must be integers");
        return value.getAsBigDecimal().intValueExact();
    }
}

package com.sande.mythictrpg.godavatar.visit;

import com.google.gson.*;
import net.minecraft.resources.ResourceLocation;
import java.util.*;

/** Authored permission, not another God identity or affinity database. Missing policy means disabled. */
public record GodVisitPolicy(ResourceLocation godId, boolean dialogue, boolean autonomous,
        Map<ResourceLocation, Integer> minimumProgress, List<ResourceLocation> evaluationPolicies,
        int checkIntervalTicks, int cooldownTicks, int requestTimeoutTicks, int travelTimeoutTicks,
        int maximumEvaluationAgeTicks) {
    public GodVisitPolicy {
        Objects.requireNonNull(godId);
        minimumProgress = Map.copyOf(minimumProgress); evaluationPolicies = List.copyOf(evaluationPolicies);
        if (minimumProgress.isEmpty() || minimumProgress.size() > 16
                || minimumProgress.values().stream().anyMatch(v -> v < 0 || v > 100)
                || evaluationPolicies.size() > 16 || new HashSet<>(evaluationPolicies).size() != evaluationPolicies.size())
            throw new IllegalArgumentException("Invalid visit progress/evaluation policy list");
        for (int ticks : List.of(checkIntervalTicks, cooldownTicks, requestTimeoutTicks, travelTimeoutTicks, maximumEvaluationAgeTicks))
            if (ticks < 20 || ticks > 12_096_000) throw new IllegalArgumentException("Visit ticks require 20..12096000");
        if (requestTimeoutTicks > 2400) throw new IllegalArgumentException("Visit request timeout exceeds 2400 ticks");
    }
    public static GodVisitPolicy decode(ResourceLocation god, JsonObject j) {
        if (!j.keySet().equals(Set.of("formatVersion", "dialogue", "autonomous", "minimumProgress", "evaluationPolicies",
                "checkIntervalTicks", "cooldownTicks", "requestTimeoutTicks", "travelTimeoutTicks", "maximumEvaluationAgeTicks")))
            throw new IllegalArgumentException("Missing/unknown visit policy field");
        if (integer(j.get("formatVersion")) != 1) throw new IllegalArgumentException("Visit formatVersion");
        Map<ResourceLocation,Integer> progress = new LinkedHashMap<>();
        j.getAsJsonObject("minimumProgress").entrySet().forEach(e -> progress.put(id(e.getKey()), integer(e.getValue())));
        List<ResourceLocation> evaluations = new ArrayList<>();
        for (var e : j.getAsJsonArray("evaluationPolicies")) {
            if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) throw new IllegalArgumentException("evaluation ID must be a string");
            evaluations.add(id(e.getAsString()));
        }
        return new GodVisitPolicy(god, bool(j.get("dialogue")), bool(j.get("autonomous")), progress, evaluations,
                integer(j.get("checkIntervalTicks")), integer(j.get("cooldownTicks")), integer(j.get("requestTimeoutTicks")),
                integer(j.get("travelTimeoutTicks")), integer(j.get("maximumEvaluationAgeTicks")));
    }
    private static ResourceLocation id(String value) {
        var id = ResourceLocation.tryParse(value);
        if (id == null || !id.toString().equals(value)) throw new IllegalArgumentException("Exact namespaced ID required");
        return id;
    }
    private static boolean bool(JsonElement value) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) throw new IllegalArgumentException("Boolean required");
        return value.getAsBoolean();
    }
    private static int integer(JsonElement value) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException("Integer required");
        try { return value.getAsBigDecimal().intValueExact(); }
        catch (ArithmeticException invalid) { throw new IllegalArgumentException("Integer required", invalid); }
    }
}

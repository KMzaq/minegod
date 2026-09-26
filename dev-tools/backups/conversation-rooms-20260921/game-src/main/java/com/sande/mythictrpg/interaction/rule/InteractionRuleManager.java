package com.sande.mythictrpg.interaction.rule;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.sande.mythictrpg.MythicTrpg;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.neoforged.neoforge.event.AddReloadListenerEvent;

import java.io.Reader;
import java.util.ArrayList;
import java.util.List;

public final class InteractionRuleManager
        extends SimplePreparableReloadListener<InteractionRuleManager.Prepared> {
    public static final InteractionRuleManager INSTANCE = new InteractionRuleManager();
    private static final FileToIdConverter CONVERTER = FileToIdConverter.json("mythictrpg/interaction_rules");

    private volatile RuleSnapshot snapshot = new RuleSnapshot(List.of(), InteractionRuleSeedIndex.empty(), 0);

    private InteractionRuleManager() {
    }

    public void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener(this);
    }

    public RuleSnapshot snapshot() {
        return snapshot;
    }

    @Override
    protected Prepared prepare(ResourceManager resourceManager, ProfilerFiller profiler) {
        List<InteractionRule> parsed = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        CONVERTER.listMatchingResources(resourceManager).entrySet().stream()
                .sorted(java.util.Map.Entry.comparingByKey())
                .forEach(entry -> parseResource(entry.getKey(), entry.getValue(), parsed, errors));
        if (!errors.isEmpty()) {
            throw new IllegalStateException("Rejected interaction rule reload with " + errors.size()
                    + " error(s); the previous snapshot remains active");
        }
        List<InteractionRule> immutable = List.copyOf(parsed);
        return new Prepared(immutable, InteractionRuleSeedIndex.build(immutable));
    }

    private static void parseResource(ResourceLocation file, Resource resource,
            List<InteractionRule> parsed, List<String> errors) {
        ResourceLocation id = CONVERTER.fileToId(file);
        try (Reader reader = resource.openAsReader()) {
            JsonElement root = JsonParser.parseReader(reader);
            if (!root.isJsonObject()) {
                throw new IllegalArgumentException("Root value must be a JSON object");
            }
            parsed.add(InteractionRuleSchema.parse(id, root.getAsJsonObject()));
        } catch (Exception exception) {
            String message = "Interaction rule " + file + " (ID " + id + ") from pack '"
                    + resource.sourcePackId() + "' failed: " + exception.getMessage();
            errors.add(message);
            MythicTrpg.LOGGER.error(message, exception);
        }
    }

    @Override
    protected void apply(Prepared prepared, ResourceManager resourceManager, ProfilerFiller profiler) {
        long generation = snapshot.generation() + 1;
        snapshot = new RuleSnapshot(prepared.rules(), prepared.seedIndex(), generation);
        MythicTrpg.LOGGER.info("Loaded {} interaction rules (generation {}).",
                prepared.rules().size(), generation);
    }

    protected record Prepared(List<InteractionRule> rules, InteractionRuleSeedIndex seedIndex) {
        protected Prepared {
            rules = List.copyOf(rules);
        }
    }

    public record RuleSnapshot(List<InteractionRule> rules,
            InteractionRuleSeedIndex seedIndex, long generation) {
        public RuleSnapshot {
            rules = List.copyOf(rules);
        }
    }
}

package com.sande.mythictrpg.ai.reaction;

import com.google.gson.Gson;
import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.ai.data.BundledJsonData;
import net.neoforged.fml.loading.FMLPaths;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Data repository for non-executable reaction guidance. */
public final class ReactionGuidelineRepository {
    private static final Gson GSON = new Gson();
    private static final String BUNDLED_RESOURCE = "data/mythictrpg/ai/reaction-guidelines.json";
    public static final ReactionGuidelineRepository INSTANCE = new ReactionGuidelineRepository();

    private final Path file = FMLPaths.CONFIGDIR.get().resolve("mythictrpg/reaction-guidelines.json");
    private volatile Map<ReactionGuidelineId, ReactionGuideline> guidelines = Map.of();

    private ReactionGuidelineRepository() {
    }

    public synchronized void load() {
        try {
            BundledJsonData.ensureServerCopy(file, BUNDLED_RESOURCE);
            try (Reader reader = Files.newBufferedReader(file)) {
                guidelines = parse(GSON.fromJson(reader, RawRepository.class));
            }
        } catch (Exception exception) {
            MythicTrpg.LOGGER.error("Could not load reaction guidelines {}", file, exception);
            guidelines = Map.of();
        }
        MythicTrpg.LOGGER.info("Loaded {} reaction guidelines from {}.", guidelines.size(), file);
    }

    public Optional<ReactionGuideline> find(ReactionGuidelineId id) {
        return Optional.ofNullable(guidelines.get(id));
    }

    public Map<ReactionGuidelineId, ReactionGuideline> all() {
        return guidelines;
    }

    public Path file() {
        return file;
    }

    private static Map<ReactionGuidelineId, ReactionGuideline> parse(RawRepository raw) {
        if (raw == null || raw.guidelines == null) {
            throw new IllegalArgumentException("reaction guideline data requires guidelines");
        }
        Map<ReactionGuidelineId, ReactionGuideline> parsed = new EnumMap<>(ReactionGuidelineId.class);
        for (RawGuideline source : raw.guidelines) {
            ReactionGuideline guideline = convert(source);
            if (parsed.putIfAbsent(guideline.id(), guideline) != null) {
                throw new IllegalArgumentException("Duplicate reaction guideline " + guideline.id());
            }
        }
        return Map.copyOf(parsed);
    }

    private static ReactionGuideline convert(RawGuideline source) {
        if (source == null || source.id == null) {
            throw new IllegalArgumentException("Reaction guideline is missing id");
        }
        ReactionGuidelineId id = ReactionGuidelineId.valueOf(source.id);
        EnumSet<SituationSignal> triggers = EnumSet.noneOf(SituationSignal.class);
        if (source.triggers != null) {
            for (String trigger : source.triggers) {
                triggers.add(SituationSignal.valueOf(trigger));
            }
        }
        List<TagRelevanceBoost> boosts = new ArrayList<>();
        if (source.tagBoosts != null) {
            for (RawTagBoost boost : source.tagBoosts) {
                if (boost != null) {
                    boosts.add(new TagRelevanceBoost(boost.requiredTags == null ? java.util.Set.of()
                            : java.util.Set.copyOf(boost.requiredTags), boost.score, boost.reason));
                }
            }
        }
        GuidelineRuleType ruleType = source.ruleType == null ? GuidelineRuleType.GUIDANCE
                : GuidelineRuleType.valueOf(source.ruleType);
        return new ReactionGuideline(id, triggers, source.guidelines, source.avoid, source.requiredContext,
                ruleType, source.baseScore, boosts);
    }

    private static final class RawRepository {
        private int schemaVersion;
        private List<RawGuideline> guidelines;
    }

    private static final class RawGuideline {
        private String id;
        private List<String> triggers;
        private List<String> guidelines;
        private List<String> avoid;
        private List<String> requiredContext;
        private String ruleType;
        private int baseScore;
        private List<RawTagBoost> tagBoosts;
    }

    private static final class RawTagBoost {
        private List<String> requiredTags;
        private int score;
        private String reason;
    }
}

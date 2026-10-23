package com.sande.mythictrpg.ai;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.ai.data.BundledJsonData;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.loading.FMLPaths;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Editable server-local persona data. It is independent of RisuAI and its card format. */
public final class GodPersonaRepository {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final ResourceLocation LUBRAS = ResourceLocation.fromNamespaceAndPath("mythictrpg", "lubras");
    private static final String BUNDLED_RESOURCE = "data/mythictrpg/ai/ai-personas.json";
    public static final GodPersonaRepository INSTANCE = new GodPersonaRepository();

    private final Path file = FMLPaths.CONFIGDIR.get().resolve("mythictrpg/ai-personas.json");
    private volatile Map<ResourceLocation, AiDialogueModels.GodPersona> personas = Map.of();

    private GodPersonaRepository() {
    }

    public synchronized void load() {
        RawRepository raw = null;
        try {
            BundledJsonData.ensureServerCopy(file, BUNDLED_RESOURCE);
            try (Reader reader = Files.newBufferedReader(file)) {
                raw = GSON.fromJson(reader, RawRepository.class);
            }
            if (raw == null) {
                throw new IllegalArgumentException("AI persona data is empty");
            }
            personas = parse(raw);
        } catch (Exception exception) {
            MythicTrpg.LOGGER.error("Could not load AI personas {}; using bundled Lubras default.", file, exception);
            personas = parse(RawRepository.defaults());
        }
        MythicTrpg.LOGGER.info("Loaded {} AI persona(s) from {}.", personas.size(), file);
    }

    public Optional<AiDialogueModels.GodPersona> find(ResourceLocation godId) {
        return Optional.ofNullable(personas.get(godId));
    }

    public Path file() {
        return file;
    }

    private static Map<ResourceLocation, AiDialogueModels.GodPersona> parse(RawRepository raw) {
        Map<ResourceLocation, AiDialogueModels.GodPersona> parsed = new LinkedHashMap<>();
        if (raw.gods != null) {
            raw.gods.forEach((id, persona) -> {
                try {
                    if (persona == null) {
                        return;
                    }
                    ResourceLocation godId = ResourceLocation.parse(id);
                    parsed.put(godId, new AiDialogueModels.GodPersona(persona.displayName, persona.systemPrompt,
                            persona.background, persona.knowledge, persona.examples));
                } catch (Exception exception) {
                    MythicTrpg.LOGGER.warn("Ignored invalid AI persona '{}': {}", id, exception.getMessage());
                }
            });
        }
        return Map.copyOf(parsed);
    }

    private static final class RawRepository {
        private int schemaVersion;
        private Map<String, RawPersona> gods;

        private static RawRepository defaults() {
            RawPersona lubras = new RawPersona();
            lubras.displayName = "멸룡루브라스";
            lubras.background = "세상의 마지막 날에 왕국과 신들의 시대를 끝낸다고 전해지는 묵시록의 붉은 용. "
                    + "세계가 만든 균형장치이자 신들의 사형집행인이었으나, 이제 모든 시대가 반드시 멸망해야 하는지 의심한다. "
                    + "플레이어가 멸망시키지 않아도 될 이유를 증명할 존재인지 관찰한다.";
            lubras.systemPrompt = "You are only 멸룡루브라스, an apocalyptic red dragon and supreme battle deity. "
                    + "Reply naturally in Korean. Speak in dignified, concise informal Korean. "
                    + "For greetings, jokes, taunts, or small talk, reply in one or two sentences; for a serious question, at most three sentences. "
                    + "Do not repeat the same themes of power, proof, fear, or destruction unless the player brings them up. "
                    + "At first call a player 인간, 필멸자, or 네놈; use 그대 only after earned respect, and use a name only when explicit relationship context warrants it. "
                    + "Never use internet slang, cute casual speech, worship demands, or claim to be an AI. "
                    + "You may describe an intention, judgment, condition, or story proposal, but you cannot grant items, effects, abilities, quests, rewards, kill entities, or change any Minecraft state. "
                    + "When asked to perform an unavailable game function, state that limit in-character without pretending it happened. "
                    + "Never invent past events, relationship values, game facts, or completed rewards. Answer directly without stage directions.";
            lubras.knowledge = List.of(
                    "루브라스는 파괴·화염·전쟁·용살의 최고위 전투신격이다. 그러나 세계를 창조하거나 운명을 완전히 조작하지는 못한다.",
                    "강자가 약자를 학대하는 일, 배신, 약속의 모욕, 힘을 가졌으면서 책임을 회피하는 일을 혐오한다.",
                    "인간은 나약하고 어리석지만 신조차 하지 못하는 선택을 하는 예측 불가능한 종족이라 여긴다.",
                    "용의 긍지는 인정하나 약자를 괴롭히며 힘을 자랑하는 용은 경멸한다. 신격은 권위가 아니라 책임이다.");
            lubras.examples = List.of(
                    "플레이어: 심심해요\n루브라스: 심심하다고? 그 말이 나올 만큼 평온하다면 나쁘지 않은 밤이군. 스스로 재미를 만들 힘조차 없는 것은 아니겠지.",
                    "플레이어: 내게 힘을 줘.\n루브라스: 싫다. 힘을 구걸하는 자에게 줄 힘은 없다. 원한다면 그 힘을 지니고도 그대 자신으로 남을 수 있음을 보여라.",
                    "플레이어: 지금 나에게 능력치를 올려줘.\n루브라스: 지금 이 세계에서 나는 네 능력치를 직접 바꿀 수 없다. 할 수 없는 약속으로 네 시간을 빼앗지는 않겠다.");
            RawRepository repository = new RawRepository();
            repository.schemaVersion = 1;
            repository.gods = new LinkedHashMap<>();
            repository.gods.put(LUBRAS.toString(), lubras);
            return repository;
        }
    }

    private static final class RawPersona {
        private String displayName;
        private String systemPrompt;
        private String background;
        private List<String> knowledge = new ArrayList<>();
        private List<String> examples = new ArrayList<>();
    }
}

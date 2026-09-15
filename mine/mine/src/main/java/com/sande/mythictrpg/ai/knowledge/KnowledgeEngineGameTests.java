package com.sande.mythictrpg.ai.knowledge;

import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.ai.relationship.EmotionSnapshot;
import com.sande.mythictrpg.ai.relationship.EmotionTypes;
import com.sande.mythictrpg.ai.relationship.RelationshipAxes;
import com.sande.mythictrpg.ai.relationship.RelationshipSnapshot;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.Map;

@GameTestHolder(MythicTrpg.MOD_ID)
@PrefixGameTestTemplate(false)
public final class KnowledgeEngineGameTests {
    private static final String TEMPLATE = "bastion/mobs/empty";
    private static final String ATHENA = "mythictrpg:athena";
    private static final String ZEUS = "mythictrpg:zeus";
    private static final String PLAYER_A = "player-a";
    private static final String PLAYER_B = "player-b";

    private KnowledgeEngineGameTests() {
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void knowledgeUnknownToNpcNeverEntersAllowedContext(GameTestHelper helper) {
        KnowledgeEntry entry = new KnowledgeEntry("titan_war_001", "Titan War", "The Titan War was ancient.",
                List.of(ZEUS), KnowledgeSecrecy.DIVINE);
        KnowledgeRetrievalResult result = retriever(entry).retrieve(new KnowledgeQuery(access(ATHENA, PLAYER_A,
                relationship(ATHENA, PLAYER_A, 95, 0), List.of()), "Tell me about Titan War", 3));
        helper.assertTrue(result.allowed().isEmpty(), "Knowledge unknown to Athena entered the prompt context");
        helper.assertValueEqual(result.decisions().getFirst().status(), KnowledgeDisclosureStatus.NPC_DOES_NOT_KNOW,
                "Unknown-NPC decision was not recorded");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void untrustedListenerBlocksDivineKnowledgeWithoutLeakingContent(GameTestHelper helper) {
        KnowledgeEntry entry = new KnowledgeEntry("titan_war_001", "Titan War", "Private divine war detail.",
                List.of(ATHENA), KnowledgeSecrecy.DIVINE);
        RelationshipSnapshot requester = relationship(ATHENA, PLAYER_A, 90, 5);
        RelationshipSnapshot listener = relationship(ATHENA, PLAYER_B, 10, 70);
        KnowledgeAudienceMember untrustedListener = new KnowledgeAudienceMember(PLAYER_B, KnowledgeAudienceState.LISTENER,
                listener);
        KnowledgeRetrievalResult result = retriever(entry).retrieve(new KnowledgeQuery(access(ATHENA, PLAYER_A,
                requester, List.of(untrustedListener)), "Tell me about Titan War", 3));

        helper.assertTrue(result.allowed().isEmpty(), "Divine knowledge leaked despite an untrusted listener");
        helper.assertValueEqual(result.decisions().getFirst().status(), KnowledgeDisclosureStatus.UNSAFE_AUDIENCE,
                "Untrusted listener did not produce an audience-withhold decision");
        helper.assertTrue(!result.decisions().getFirst().guidance().contains(entry.content()),
                "Withhold diagnostic leaked the secret content");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void trustedPrivateAudienceReceivesOnlyRelevantPermittedKnowledge(GameTestHelper helper) {
        KnowledgeEntry entry = new KnowledgeEntry("titan_war_001", "Titan War", "Private divine war detail.",
                List.of(ATHENA), KnowledgeSecrecy.DIVINE);
        KnowledgeRetrievalResult result = retriever(entry).retrieve(new KnowledgeQuery(access(ATHENA, PLAYER_A,
                relationship(ATHENA, PLAYER_A, 90, 0), List.of()), "Tell me about Titan War", 3));
        helper.assertValueEqual(result.allowed().size(), 1, "Trusted private request did not receive permitted knowledge");
        helper.assertValueEqual(result.allowed().getFirst().content(), "Private divine war detail.",
                "Allowed knowledge content was changed");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void strongCurrentAngerCanWithholdOtherwiseTrustedPrivateKnowledge(GameTestHelper helper) {
        KnowledgeEntry entry = new KnowledgeEntry("titan_war_001", "Titan War", "Private divine war detail.",
                List.of(ATHENA), KnowledgeSecrecy.DIVINE);
        RelationshipSnapshot relationship = relationship(ATHENA, PLAYER_A, 90, 0);
        KnowledgeAccessContext access = new KnowledgeAccessContext(ATHENA, PLAYER_A, relationship,
                new EmotionSnapshot(ATHENA, PLAYER_A, Map.of(EmotionTypes.ANGER, 95)), List.of());
        KnowledgeRetrievalResult result = retriever(entry).retrieve(new KnowledgeQuery(access,
                "Tell me about Titan War", 3));
        helper.assertTrue(result.allowed().isEmpty(), "Strong current anger did not withhold private knowledge");
        helper.assertValueEqual(result.decisions().getFirst().status(), KnowledgeDisclosureStatus.EMOTIONALLY_WITHHELD,
                "Emotion-aware withholding decision was not selected");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void hierarchyMetadataAndSearchKeysArePreserved(GameTestHelper helper) {
        KnowledgeEntry entry = new KnowledgeEntry("person_athena_domains", "아테나의 영역",
                "아테나는 지혜와 전략을 관장한다.", List.of(ATHENA), KnowledgeSecrecy.PUBLIC, "PERSON",
                ATHENA, List.of("전략", "아테나"), 5, false);
        KnowledgeRetrievalResult result = retriever(entry).retrieve(new KnowledgeQuery(access(ATHENA, PLAYER_A,
                relationship(ATHENA, PLAYER_A, 50, 0), List.of()), "전략", 3));
        helper.assertValueEqual(result.allowed().getFirst().category(), "PERSON",
                "Knowledge category was not preserved in the allowed snippet");
        helper.assertValueEqual(result.allowed().getFirst().parentId(), ATHENA,
                "Knowledge parent hierarchy was not preserved in the allowed snippet");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void alwaysActiveEntryCanBeUsedWithoutTextMatch(GameTestHelper helper) {
        KnowledgeEntry entry = new KnowledgeEntry("world_core_rule", "핵심 규칙", "세계의 핵심 규칙.",
                List.of(ATHENA), KnowledgeSecrecy.PUBLIC, "WORLD", "", List.of(), 3, true);
        KnowledgeRetrievalResult result = retriever(entry).retrieve(new KnowledgeQuery(access(ATHENA, PLAYER_A,
                relationship(ATHENA, PLAYER_A, 50, 0), List.of()), "", 3));
        helper.assertValueEqual(result.allowed().size(), 1,
                "An always-active world entry was not available without a keyword match");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void koreanParticleOnNpcNameMatchesLoreKey(GameTestHelper helper) {
        KnowledgeEntry entry = new KnowledgeEntry("person_athena_identity", "아테나의 정체성",
                "아테나는 지혜와 전략의 여신이다.", List.of(ATHENA), KnowledgeSecrecy.PUBLIC, "PERSON",
                ATHENA, List.of("아테나"), 5, false);
        KnowledgeRetrievalResult result = retriever(entry).retrieve(new KnowledgeQuery(access(ATHENA, PLAYER_A,
                relationship(ATHENA, PLAYER_A, 50, 0), List.of()), "아테나가 어떤 신이야?", 3));
        helper.assertValueEqual(result.allowed().size(), 1,
                "Korean particle normalization did not retrieve the NPC's lore entry");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void intentOrHistorySearchHintCanResolveReferentialQuestion(GameTestHelper helper) {
        KnowledgeEntry entry = new KnowledgeEntry("person_athena_domains", "아테나의 권능",
                "아테나는 전략을 관장한다.", List.of(ATHENA), KnowledgeSecrecy.PUBLIC, "PERSON",
                ATHENA, List.of("아테나", "전략"), 4, false);
        KnowledgeRetrievalResult result = retriever(entry).retrieve(new KnowledgeQuery(access(ATHENA, PLAYER_A,
                relationship(ATHENA, PLAYER_A, 50, 0), List.of()), "그 신은 무엇을 관장해?",
                List.of("아테나"), 3));
        helper.assertValueEqual(result.allowed().size(), 1,
                "A bounded prior-turn or intent hint did not resolve a referential lore question");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void unknownNpcCandidateDoesNotConsumePermittedLoreSlot(GameTestHelper helper) {
        KnowledgeEntry unknown = new KnowledgeEntry("zeus_war_secret", "전쟁 비밀", "전쟁에 관한 비밀.",
                List.of(ZEUS), KnowledgeSecrecy.PUBLIC, "PERSON", ZEUS, List.of("전쟁"), 10, false);
        KnowledgeEntry permitted = new KnowledgeEntry("athena_war_lore", "전쟁 전략", "전쟁 전략에 관한 지식.",
                List.of(ATHENA), KnowledgeSecrecy.PUBLIC, "PERSON", ATHENA, List.of("전쟁"), 1, false);
        KnowledgeRetrievalResult result = retriever(unknown, permitted).retrieve(new KnowledgeQuery(access(ATHENA,
                PLAYER_A, relationship(ATHENA, PLAYER_A, 50, 0), List.of()), "전쟁", 1));
        helper.assertValueEqual(result.allowed().getFirst().id(), "athena_war_lore",
                "Unknown NPC lore consumed the permitted prompt slot");
        helper.succeed();
    }

    private static KnowledgeRetriever retriever(KnowledgeEntry... entries) {
        return new KnowledgeRetriever(() -> List.of(entries), new DefaultKnowledgeDisclosurePolicy());
    }

    private static KnowledgeAccessContext access(String npcId, String playerId, RelationshipSnapshot relationship,
            List<KnowledgeAudienceMember> audience) {
        return new KnowledgeAccessContext(npcId, playerId, relationship,
                new EmotionSnapshot(npcId, playerId, Map.of()), audience);
    }

    private static RelationshipSnapshot relationship(String npcId, String playerId, int trust, int caution) {
        return new RelationshipSnapshot(npcId, playerId, Map.of(RelationshipAxes.AFFINITY, 0,
                RelationshipAxes.TRUST, trust, RelationshipAxes.RESPECT, 0, RelationshipAxes.CAUTION, caution));
    }
}

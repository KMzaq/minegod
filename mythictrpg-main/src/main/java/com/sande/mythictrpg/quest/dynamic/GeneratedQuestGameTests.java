package com.sande.mythictrpg.quest.dynamic;

import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.gameplay.observation.GameplayObservationTypes;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

@GameTestHolder(MythicTrpg.MOD_ID)
@PrefixGameTestTemplate(false)
public final class GeneratedQuestGameTests {
    private static final String TEMPLATE = "bastion/mobs/empty";
    private static final ResourceLocation FORTUNA = id("fortuna");

    private GeneratedQuestGameTests() {
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void authoredTemplatesAreSideOnlyAndStrictlyBounded(GameTestHelper helper) {
        GeneratedQuestTemplate template = new GeneratedQuestTemplate(id("test_side"), FORTUNA,
                GameplayObservationTypes.ENTITY_KILLED.id(),
                ResourceLocation.withDefaultNamespace("zombie"), 8, id("progress_fortuna"),
                0, 35, FORTUNA, 2, 3, 1, 24000L, 72000L);
        helper.assertTrue(template.permitsWorldProgress(20), "valid world progress was rejected");
        helper.assertFalse(template.permitsWorldProgress(50), "late world progress was accepted");
        expectRejected(helper, () -> new GeneratedQuestTemplate(id("bad_count"), FORTUNA,
                GameplayObservationTypes.ENTITY_KILLED.id(),
                ResourceLocation.withDefaultNamespace("zombie"), 257, id("progress_fortuna"),
                0, 35, FORTUNA, 2, 3, 1, 24000L, 72000L),
                "unbounded objective count was accepted");
        expectRejected(helper, () -> new GeneratedQuestTemplate(id("bad_catchup"), FORTUNA,
                GameplayObservationTypes.ENTITY_KILLED.id(),
                ResourceLocation.withDefaultNamespace("zombie"), 8, id("progress_fortuna"),
                0, 35, FORTUNA, 2, 4, 2, 24000L, 72000L),
                "catch-up bonus above one tier was accepted");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void generatedQuestStateRoundTripsProgressAndCooldown(GameTestHelper helper) {
        UUID player = UUID.randomUUID();
        GeneratedQuestInstance original = new GeneratedQuestInstance(UUID.randomUUID(), id("test_side"),
                player, FORTUNA, "시험 의뢰", "안전한 상태 저장 시험",
                GameplayObservationTypes.ENTITY_KILLED.id(),
                ResourceLocation.withDefaultNamespace("zombie"), 8, 3, FORTUNA, 2,
                false, 100L, 1000L, 11L, 12L, 13L);
        GeneratedQuestState state = new GeneratedQuestState();
        state.create(original);
        CompoundTag stored = state.save(new CompoundTag(), helper.getLevel().registryAccess());
        GeneratedQuestState loaded = GeneratedQuestState.load(stored, helper.getLevel().registryAccess());
        helper.assertValueEqual(loaded.active(player).orElseThrow(), original,
                "active generated quest round trip");
        helper.assertFalse(loaded.cooldownReady(player, original.templateId(), 200L, 200L),
                "cooldown was lost during persistence");
        helper.assertTrue(loaded.cooldownReady(player, original.templateId(), 300L, 200L),
                "cooldown did not become ready at its boundary");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void bundledGeneratedQuestTemplatesLoad(GameTestHelper helper) {
        helper.assertTrue(GeneratedQuestTemplateManager.INSTANCE.find(
                id("fortuna_zombie_cull"), FORTUNA).isPresent(),
                "bundled generated quest template was not loaded");
        helper.succeed();
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MythicTrpg.MOD_ID, path);
    }

    private static void expectRejected(GameTestHelper helper, Runnable operation, String message) {
        try {
            operation.run();
            helper.fail(message);
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }
}

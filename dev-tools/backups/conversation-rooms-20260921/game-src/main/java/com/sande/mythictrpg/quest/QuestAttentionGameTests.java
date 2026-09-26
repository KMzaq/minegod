package com.sande.mythictrpg.quest;

import com.sande.mythictrpg.MythicTrpg;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.time.Instant;
import java.util.UUID;

@GameTestHolder(MythicTrpg.MOD_ID)
@PrefixGameTestTemplate(false)
public final class QuestAttentionGameTests {
    private static final String TEMPLATE = "bastion/mobs/empty";
    private static final ResourceLocation GOD = id("attention_test_god");
    private static final ResourceLocation ENTRY = id("attention_test_entry");
    private static final ResourceLocation OTHER_ENTRY = id("attention_test_other_entry");

    private QuestAttentionGameTests() {
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void firstEntryQuestSelectsEveryRecipientOfThatQuest(GameTestHelper helper) {
        GodAttentionState state = new GodAttentionState();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        Instant time = Instant.parse("2026-08-30T00:00:00Z");

        state.recordEntryAssignment(GOD, ENTRY, first, time);
        state.recordEntryAssignment(GOD, ENTRY, second, time.plusSeconds(1));
        state.recordEntryAssignment(GOD, ENTRY, first, time.plusSeconds(2));

        helper.assertTrue(state.isFocused(GOD, first), "first quest recipient was not focused");
        helper.assertTrue(state.isFocused(GOD, second), "second quest recipient was not focused");
        helper.assertValueEqual(state.focusedPlayers(GOD).size(), 2,
                "duplicate assignment changed the focus set");
        helper.assertValueEqual(state.record(GOD).orElseThrow().firstAssignedAt(), time,
                "joining recipient replaced the original selection time");

        try {
            state.recordEntryAssignment(GOD, OTHER_ENTRY, UUID.randomUUID(), time.plusSeconds(3));
            helper.fail("a different entry quest replaced the God focus");
            return;
        } catch (IllegalStateException expected) {
            // A God has exactly one first fixed main quest selection source.
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void attentionSelectionPersistsAcrossSaveAndLoad(GameTestHelper helper) {
        GodAttentionState original = new GodAttentionState();
        UUID player = UUID.randomUUID();
        original.recordEntryAssignment(GOD, ENTRY, player, Instant.parse("2026-08-30T01:02:03Z"));

        CompoundTag saved = original.save(new CompoundTag(), helper.getLevel().registryAccess());
        GodAttentionState loaded = GodAttentionState.load(saved, helper.getLevel().registryAccess());

        helper.assertTrue(loaded.isWritable(), "valid attention state was rejected on load");
        helper.assertTrue(loaded.isFocused(GOD, player), "focused player did not survive round trip");
        helper.assertValueEqual(loaded.record(GOD).orElseThrow().entryQuestId(), ENTRY,
                "entry quest did not survive round trip");
        helper.succeed();
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MythicTrpg.MOD_ID, path);
    }
}

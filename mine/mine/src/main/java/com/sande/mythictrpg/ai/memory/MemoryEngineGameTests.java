package com.sande.mythictrpg.ai.memory;

import com.sande.mythictrpg.MythicTrpg;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@GameTestHolder(MythicTrpg.MOD_ID)
@PrefixGameTestTemplate(false)
public final class MemoryEngineGameTests {
    private static final String TEMPLATE = "bastion/mobs/empty";
    private static final String NPC = "mythictrpg:hephaestus";
    private static final String PLAYER_A = "player-a";
    private static final String PLAYER_B = "player-b";

    private MemoryEngineGameTests() {
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void relevantSearchKeepsRecentAndLongTermMemoryScopedToNpcPlayerPair(GameTestHelper helper) {
        InMemoryMemoryRepository repository = new InMemoryMemoryRepository();
        repository.upsert(new NpcMemory(UUID.randomUUID(), NPC, PLAYER_A, MemoryType.LONG_TERM, 0.82D,
                "Player A asked for clippers to trim a dragon claw; the NPC made special shears.",
                List.of("item_request", "special_gift", "humorous"), Instant.parse("2026-01-01T00:00:00Z")));
        repository.upsert(new NpcMemory(UUID.randomUUID(), NPC, PLAYER_A, MemoryType.RECENT, 0.15D,
                "Player A mentioned dragon claw polishing again.", List.of("dragon_claw"), Instant.now()));
        repository.upsert(new NpcMemory(UUID.randomUUID(), NPC, PLAYER_B, MemoryType.LONG_TERM, 1.0D,
                "Player B secret unrelated memory.", List.of("secret"), Instant.now()));

        List<MemorySnippet> memories = new MemoryRetriever(repository).retrieve(new MemoryQuery(NPC, PLAYER_A,
                "Can you polish my dragon claw?", 3));
        helper.assertValueEqual(memories.size(), 2, "Unrelated player memory was included in NPC context");
        helper.assertTrue(memories.stream().anyMatch(memory -> memory.type() == MemoryType.LONG_TERM
                && memory.tags().contains("special_gift")), "Relevant long-term important memory was not retrieved");
        helper.assertTrue(memories.stream().anyMatch(memory -> memory.type() == MemoryType.RECENT),
                "Relevant recent memory was not retrieved");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void unrelatedPastMemoriesAreNotAddedToCurrentContext(GameTestHelper helper) {
        InMemoryMemoryRepository repository = new InMemoryMemoryRepository();
        repository.upsert(new NpcMemory(UUID.randomUUID(), NPC, PLAYER_A, MemoryType.LONG_TERM, 1.0D,
                "Player A once discussed a hidden moon ritual.", List.of("moon_ritual"), Instant.now()));

        List<MemorySnippet> memories = new MemoryRetriever(repository).retrieve(new MemoryQuery(NPC, PLAYER_A,
                "Please repair this hammer.", 3));
        helper.assertTrue(memories.isEmpty(), "Irrelevant past memory was returned just because it was important");
        helper.succeed();
    }
}

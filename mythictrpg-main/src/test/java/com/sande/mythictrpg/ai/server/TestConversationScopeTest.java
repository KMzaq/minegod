package com.sande.mythictrpg.ai.server;

import com.sande.mythictrpg.ai.memorycontract.ConversationMemoryContext;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Pure recording authority checks; no Minecraft bootstrap, world, model or server process. */
public final class TestConversationScopeTest {
    private static int checks;

    public static void main(String[] args) {
        UUID player = UUID.randomUUID(), world = UUID.randomUUID(), interaction = UUID.randomUUID(), generation = UUID.randomUUID();
        var first = ResourceLocation.parse("mythictrpg:test_first");
        var second = ResourceLocation.parse("mythictrpg:test_second");
        var input = new ArrayList<>(List.of(first, second));
        var on = new TestConversationScope(player, interaction, generation, input, true);
        var off = new TestConversationScope(player, interaction, generation, input, false);
        input.clear();
        check(on.godIds().equals(List.of(first, second)), "external list cannot change the authorized audience");
        for (var god : on.godIds()) {
            var context = new ConversationMemoryContext(world, interaction, generation, player, god.toString(), Set.of(player), false);
            check(on.recordingAllowed(context), "each explicit God may record under the same on lease");
            check(!off.recordingAllowed(context), "off denies recording without changing the readable context");
        }
        check(!on.recordingAllowed(new ConversationMemoryContext(world, interaction, generation, player,
                "mythictrpg:outsider", Set.of(player), false)), "unlisted God rejected");
        check(!on.recordingAllowed(new ConversationMemoryContext(world, UUID.randomUUID(), generation, player,
                first.toString(), Set.of(player), false)), "other session rejected");
        check(!on.recordingAllowed(new ConversationMemoryContext(world, interaction, UUID.randomUUID(), player,
                first.toString(), Set.of(player), false)), "replacement generation rejected");
        UUID other = UUID.randomUUID();
        check(!on.recordingAllowed(new ConversationMemoryContext(world, interaction, generation, other,
                first.toString(), Set.of(other), false)), "other player rejected");
        check(!on.recordingAllowed(new ConversationMemoryContext(world, interaction, generation, player,
                first.toString(), Set.of(player, other), false)), "test cannot become a shared player scope");
        check(on.recordingAllowed(new ConversationMemoryContext(world, interaction, generation, player,
                first.toString(), Set.of(player), true)), "RUMOR_TEST readOnly flag is distinct from recording off");
        check(!on.recordingAllowed(null), "missing context rejected");
        check(!on.matches(null, generation) && !on.matches(interaction, null), "stale lease close cannot match");
        rejects(() -> new TestConversationScope(player, interaction, generation, List.of(), true), "empty God list");
        rejects(() -> new TestConversationScope(player, interaction, generation, List.of(first, first), true), "duplicate God");
        var sixteen = java.util.stream.IntStream.range(0, 16).mapToObj(i -> ResourceLocation.parse("test:god_" + i)).toList();
        check(new TestConversationScope(player, interaction, generation, sixteen, false).godIds().size() == 16, "sixteen Gods accepted");
        var seventeen = new ArrayList<>(sixteen); seventeen.add(first);
        rejects(() -> new TestConversationScope(player, interaction, generation, seventeen, true), "bounded participant count");
        System.out.println("TestConversationScopeTest: " + checks + " assertions passed");
    }

    private static void check(boolean condition, String label) {
        checks++;
        if (!condition) throw new AssertionError(label);
    }

    private static void rejects(Runnable operation, String label) {
        try { operation.run(); } catch (IllegalArgumentException expected) { checks++; return; }
        throw new AssertionError(label);
    }
}

package com.sande.mythictrpg.ai.memorycontract;

import com.sande.mythictrpg.interaction.director.InteractionPlan;
import com.sande.mythictrpg.rumor.RumorSavedData;
import net.minecraft.server.MinecraftServer;
import java.util.Optional;
import java.util.UUID;

/** Read-only context for an approved plan BEFORE a conversation has started; never an action scope. */
public final class PreparedMemoryContext {
    private PreparedMemoryContext() {}
    public static Optional<ConversationMemoryContext> forPlan(MinecraftServer server, InteractionPlan plan, UUID requestId) {
        if (!server.isSameThread()) throw new IllegalStateException("Memory plan requires server thread");
        var mode = MemoryFoundationSettings.mode();
        if (mode == MemoryFoundationSettings.Mode.OFF) return Optional.empty();
        var state = RumorSavedData.get(server);
        if (!state.ready()) return Optional.empty();
        return Optional.of(new ConversationMemoryContext(state.worldId(), requestId, requestId, plan.initiatingPlayerId(),
                plan.participants().primaryGodId().toString(), plan.audience().recipientPlayerIds(), mode == MemoryFoundationSettings.Mode.RUMOR_TEST));
    }
}

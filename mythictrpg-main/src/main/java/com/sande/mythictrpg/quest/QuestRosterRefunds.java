package com.sande.mythictrpg.quest;

import com.sande.mythictrpg.quest.reward.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import java.util.*;

/** Exact consumed components; returns use the existing durable claim executor. */
final class QuestRosterRefunds {
    private QuestRosterRefunds() {}
    static QuestRoster prepare(ServerPlayer player, QuestParticipationRun run, ItemStack stack, int count) {
        var roster = run.snapshot().roster();
        if (roster == null || !roster.policy().refundItems()) return roster;
        CompoundTag encoded = (CompoundTag) stack.copyWithCount(1).save(player.registryAccess());
        if (!ItemStack.isSameItemSameComponents(item(encoded.toString(), 1).createStack(player.registryAccess()), stack))
            throw new IllegalArgumentException("Item cannot be refunded exactly");
        return roster.deposit(player.getUUID(), encoded.toString(), count);
    }
    private static NpcRewardEntry item(String encoded, int count) {
        try {
            CompoundTag data = TagParser.parseTag(encoded);
            return new NpcRewardEntry(ResourceLocation.parse(data.getString("id")), count, data.getCompound("components"));
        } catch (Exception failure) { throw new IllegalArgumentException("Invalid submitted item receipt", failure); }
    }
    static void validate(QuestParticipationRun run, net.minecraft.core.HolderLookup.Provider registries) {
        var roster = run.snapshot().roster();
        if (roster == null || !roster.policy().refundItems()) return;
        for (UUID player : run.snapshot().progress().keySet()) {
            Map<String, Integer> expected = new HashMap<>(), received = new HashMap<>();
            for (int i = 0; i < run.snapshot().objectives().size(); i++) {
                var objective = run.snapshot().objectives().get(i);
                int count = run.progress(player, i);
                if (count > 0 && (objective.kind() == QuestParticipationPolicy.ObjectiveKind.ITEM_SUBMISSION
                        || objective.kind() == QuestParticipationPolicy.ObjectiveKind.ITEM_DONATION))
                    expected.merge(objective.subject(), count, Math::addExact);
            }
            for (var receipt : roster.deposits().getOrDefault(player, Map.of()).entrySet()) {
                var entry = item(receipt.getKey(), 1);
                var stack = entry.createStack(registries);
                if (stack.isEmpty()) throw new IllegalArgumentException("Empty submitted item receipt");
                received.merge(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), receipt.getValue(), Math::addExact);
            }
            if (!received.equals(expected))
                throw new IllegalArgumentException("Refund receipt does not match consumed progress");
        }
    }
    static void tick(MinecraftServer server, QuestParticipationRun run) {
        try { queueRefunds(server, run); }
        catch (RuntimeException failure) {
            // Keep receipts/cursor for repair; a missing item after a data-pack/mod change must not crash the server.
            if (server.overworld().getGameTime() % 1200 == 0)
                com.sande.mythictrpg.MythicTrpg.LOGGER.warn("Quest refund deferred for run {}", run.snapshot().runId(), failure);
        }
    }
    private static void queueRefunds(MinecraftServer server, QuestParticipationRun run) {
        var roster = run.snapshot().roster();
        if (roster == null || !roster.policy().refundItems()) return;
        for (UUID player : roster.departed().keySet()) {
            int offset = run.snapshot().roster().refundQueued().getOrDefault(player, 0);
            int skip = offset, queued = 0;
            List<RewardEntry> batch = new ArrayList<>();
            for (var entry : new TreeMap<>(roster.deposits().getOrDefault(player, Map.of())).entrySet()) {
                int remaining = entry.getValue(), skipped = Math.min(skip, remaining);
                skip -= skipped; remaining -= skipped;
                int stackLimit = item(entry.getKey(), 1).createStack(server.registryAccess()).getMaxStackSize();
                while (remaining > 0 && batch.size() < 16) {
                    int count = Math.min(Math.min(64, stackLimit), remaining);
                    batch.add(item(entry.getKey(), count)); queued += count; remaining -= count;
                }
                if (batch.size() == 16) break;
            }
            if (batch.isEmpty()) continue;
            var source = ResourceLocation.fromNamespaceAndPath("mythictrpg", "quest_refund/" + run.snapshot().runId() + "/" + player + "/" + offset);
            var result = RewardClaimService.INSTANCE.queueBatch(server, ResourceLocation.parse(run.snapshot().giverId()), source,
                    Map.of(player, new ResolvedQuestReward("퀘스트 포기·재편성 제출품 반환", batch, List.of())));
            if (!result.succeeded()) continue;
            run.roster(run.snapshot().roster().refunded(player, queued));
            MythicQuestState.get(server).setDirty();
            ServerPlayer online = server.getPlayerList().getPlayer(player);
            if (online != null) RewardClaimService.INSTANCE.deliverQueued(online);
        }
    }
}

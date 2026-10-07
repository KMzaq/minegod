package com.sande.mythictrpg.quest;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.sande.mythictrpg.command.FreeStructureCommands;
import com.sande.mythictrpg.quest.structure.*;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.time.Instant;
import java.util.*;

@GameTestHolder("mythictrpg_quest_access")
@PrefixGameTestTemplate(false)
public final class QuestPlayerAccessGameTests {
    private static final String TEMPLATE = "empty";
    private static final ResourceLocation GOD = ResourceLocation.parse("mythictrpg:fortuna");
    private static final ResourceLocation POLICY = ResourceLocation.parse("mythictrpg:fortuna_modern");
    private QuestPlayerAccessGameTests() {}

    @GameTest(templateNamespace = "mythictrpg_quest_access", template = TEMPLATE)
    public static void ownPlayerConstructionAndLockedParticipation(GameTestHelper h) throws Exception {
        var owner = new FakePlayer(h.getLevel(), new GameProfile(UUID.randomUUID(), "BuildOwner"));
        var outsider = new FakePlayer(h.getLevel(), new GameProfile(UUID.randomUUID(), "BuildOther"));
        var quests = MythicQuestState.get(owner.server);
        var structures = StructureEvaluationState.get(owner.server);
        var previous = FtbQuestBindingManager.INSTANCE.snapshot();
        var quest = id(); var groupQuest = id();
        var group = new QuestParticipationPolicy(QuestParticipationType.GROUP,
                List.of(new QuestParticipationPolicy.Objective(QuestParticipationPolicy.ObjectiveKind.EVALUATION,
                        "", "", 1)), Optional.empty());
        var binding = building(quest, Optional.empty());
        var groupBinding = building(groupQuest, Optional.of(group));
        try {
            install(previous, List.of(binding, groupBinding));
            h.assertTrue(StructureEvaluationPolicyManager.INSTANCE.find(POLICY).isPresent(), "fixture policy missing");
            quests.assign(quest, owner.getUUID(), GOD, Instant.now());
            var dispatcher = new CommandDispatcher<CommandSourceStack>();
            FreeStructureCommands.register(dispatcher);
            var source = owner.createCommandSourceStack().withPermission(0);
            var position = h.absolutePos(new BlockPos(1, 1, 1));
            structures.setPoint(owner.getUUID(), h.getLevel().dimension(), position, false);
            h.assertValueEqual(dispatcher.execute("mythstructure quest confirm " + quest, source), 0,
                    "incomplete draft admitted");
            structures.setPoint(owner.getUUID(), h.getLevel().dimension(), position.offset(2, 0, 2), true);
            h.assertValueEqual(dispatcher.execute("mythstructure quest confirm " + quest, source), 1,
                    "non-operator could not confirm their own assigned quest");
            var original = structures.build(owner.getUUID(), quest).orElseThrow();
            var contributors = original.eligibleContributors();
            h.assertTrue(contributors.contains(owner.getUUID()), "owner missing from frozen contributors");
            h.assertFalse(contributors.contains(outsider.getUUID()), "outsider added to contributors");
            try { contributors.add(outsider.getUUID()); h.fail("contributors mutable"); return; }
            catch (UnsupportedOperationException expected) { }
            h.assertValueEqual(dispatcher.execute("mythstructure quest confirm " + quest,
                    outsider.createCommandSourceStack().withPermission(0)), 0, "unassigned outsider confirmed");
            h.assertTrue(structures.build(outsider.getUUID(), quest).isEmpty(), "created outsider region");
            var forged = dispatcher.parse("mythstructure quest confirm " + quest + " " + owner.getUUID(),
                    outsider.createCommandSourceStack().withPermission(0));
            h.assertTrue(forged.getReader().canRead() || !forged.getExceptions().isEmpty(), "target-player argument admitted");
            h.assertFalse(dispatcher.getRoot().getChild("mythstructure")
                    .canUse(owner.server.createCommandSourceStack()), "console bypassed player-only root");
            h.assertFalse(owner.server.getCommands().getDispatcher().getRoot().getChild("mythadmin")
                    .canUse(source), "non-operator gained admin access");
            long now = h.getLevel().getGameTime();
            structures.recordBlock(h.getLevel().dimension(), position, owner.getUUID(), PlacementSource.PLAYER_PLACED, now);
            h.assertValueEqual(original.placements().size(), 1, "owner evidence not recorded");
            structures.recordBlock(h.getLevel().dimension(), position.above(), outsider.getUUID(), PlacementSource.PLAYER_PLACED, now);
            h.assertValueEqual(original.placements().size(), 1, "outsider evidence entered frozen ledger");
            h.assertValueEqual(dispatcher.execute("mythstructure quest confirm " + quest, source), 1, "valid reconfirm rejected");
            h.assertTrue(structures.build(owner.getUUID(), quest).orElseThrow().placements().isEmpty(), "reconfirm imported old build evidence");
            h.assertTrue(quests.isAssigned(quest, owner.getUUID()) && !quests.isCompleted(quest), "registration completed quest");

            var run = new QuestParticipationRun(UUID.randomUUID(), groupQuest.toString(), GOD.toString(), group,
                    Set.of(owner.getUUID(), outsider.getUUID()), owner.server.overworld().getGameTime());
            quests.addParticipationRun(run);
            h.assertTrue(StructureQuestRegistrationService.INSTANCE.confirm(owner, groupQuest).accepted(), "open group rejected");
            var locked = structures.build(owner.getUUID(), groupQuest).orElseThrow();
            run.advance(owner.getUUID(), 0, 1, now); run.submit(owner.getUUID(), 60, GOD.toString(), now);
            h.assertFalse(StructureQuestRegistrationService.INSTANCE.confirm(owner, groupQuest).accepted(), "submitted participant reset evidence");
            h.assertTrue(structures.build(owner.getUUID(), groupQuest).orElseThrow() == locked, "rejection replaced locked evidence");
            run.advance(outsider.getUUID(), 0, 1, now); run.submit(outsider.getUUID(), 60, GOD.toString(), now); run.close(now);
            h.assertFalse(StructureQuestRegistrationService.INSTANCE.confirm(outsider, groupQuest).accepted(), "closed group admitted new region");
            h.assertTrue(structures.build(outsider.getUUID(), groupQuest).isEmpty(), "closed group wrote evidence");
            quests.tryComplete(groupQuest, owner.getUUID(), Optional.of(GOD), Instant.now());
            quests.tryComplete(quest, owner.getUUID(), Optional.of(GOD), Instant.now());
            h.assertFalse(StructureQuestRegistrationService.INSTANCE.confirm(owner, quest).accepted(), "completed quest reconfirmed");
        } finally {
            FtbQuestBindingManager.INSTANCE.apply(new FtbQuestBindingManager.Prepared(previous.byQuestId(), previous.byFtbQuestId()), null, null);
            structures.clear(owner.getUUID(), quest); structures.clear(owner.getUUID(), groupQuest);
        }
        h.succeed();
    }

    @GameTest(templateNamespace = "mythictrpg_quest_access", template = TEMPLATE)
    public static void failedMirrorRetriesFromSavedCompletionWithoutMutatingAuthority(GameTestHelper h) {
        var state = new MythicQuestState(); UUID winner = UUID.randomUUID(), loser = UUID.randomUUID();
        var a = plain(id(), 101, 102); var b = plain(id(), 103, 104);
        for (var binding : List.of(a, b)) {
            state.assign(binding.questId(), winner, GOD, Instant.now()); state.assign(binding.questId(), loser, GOD, Instant.now());
            state.tryComplete(binding.questId(), winner, Optional.of(GOD), Instant.now());
        }
        CompoundTag saved = state.save(new CompoundTag(), h.getLevel().registryAccess());
        state = MythicQuestState.load(saved, h.getLevel().registryAccess());
        class Mirror implements QuestCompletionMirrorRecovery.Mirror {
            int calls, hides; boolean fail = true; Set<ResourceLocation> restored = new HashSet<>();
            @Override public boolean restore(FtbQuestBinding binding, QuestCompletionRecord completion) {
                calls++;
                if (fail && binding.questId().equals(a.questId())) throw new IllegalStateException("external adapter unavailable");
                if (fail) return false;
                restored.add(binding.questId()); return true;
            }
            @Override public void hideInvalidated(FtbQuestBinding binding) { hides++; }
        }
        var mirror = new Mirror();
        h.assertValueEqual(QuestCompletionMirrorRecovery.reconcile(state, winner, List.of(a, b), mirror).size(), 2,
                "external failure dropped retry or aborted other quest");
        h.assertValueEqual(mirror.calls, 2, "exception blocked other quest");
        mirror.fail = false;
        h.assertTrue(QuestCompletionMirrorRecovery.reconcile(state, winner, List.of(a, b), mirror).isEmpty(), "relogin retry failed");
        h.assertTrue(QuestCompletionMirrorRecovery.reconcile(state, winner, List.of(a, b), mirror).isEmpty(), "second login failed");
        h.assertValueEqual(mirror.restored.size(), 2, "mirror identity duplicated");
        int before = mirror.calls;
        QuestCompletionMirrorRecovery.reconcile(state, loser, List.of(a, b), mirror);
        h.assertValueEqual(mirror.calls, before, "other player's completion granted");
        h.assertValueEqual(mirror.hides, 2, "old invalidated display retained");
        QuestCompletionMirrorRecovery.reconcile(state, UUID.randomUUID(), List.of(a, b), mirror);
        h.assertValueEqual(mirror.hides, 2, "unrelated player mutated");
        h.assertValueEqual(state.save(new CompoundTag(), h.getLevel().registryAccess()), saved,
                "mirror reconciliation modified authoritative completion/assignment");
        h.succeed();
    }

    @GameTest(templateNamespace = "mythictrpg_quest_access", template = TEMPLATE)
    public static void loginRestoresFtbDisplayWithoutRewardsOrQuestExecution(GameTestHelper h) {
        var player = connectedPlayer(h);
        var previous = FtbQuestBindingManager.INSTANCE.snapshot();
        var file = dev.ftb.mods.ftbquests.quest.ServerQuestFile.getInstance().orElseThrow();
        var chapter = new dev.ftb.mods.ftbquests.quest.Chapter(file.newID(), file, file.getDefaultChapterGroup()); chapter.onCreated();
        var target = new dev.ftb.mods.ftbquests.quest.Quest(file.newID(), chapter); target.onCreated();
        var marker = new dev.ftb.mods.ftbquests.quest.Quest(file.newID(), chapter); marker.onCreated();
        var reward = new dev.ftb.mods.ftbquests.quest.reward.ItemReward(file.newID(), target, new ItemStack(Items.DIAMOND));
        var rewardConfig = new CompoundTag();
        reward.writeData(rewardConfig, h.getLevel().registryAccess());
        rewardConfig.putString("auto", "enabled");
        reward.readData(rewardConfig, h.getLevel().registryAccess());
        reward.onCreated();
        var binding = plain(id(), target.getId(), marker.getId());
        var data = file.getOrCreateTeamData(player);
        try {
            install(previous, List.of(binding));
            var state = MythicQuestState.get(player.server);
            state.assign(binding.questId(), player.getUUID(), GOD, Instant.now());
            var completion = state.tryComplete(binding.questId(), player.getUUID(), Optional.of(GOD), Instant.now()).orElseThrow();
            var registry = h.getLevel().registryAccess();
            var before = state.save(new CompoundTag(), registry);
            var world = com.sande.mythictrpg.data.world.MythicWorldState.get(player.server);
            var worldBefore = world.save(new CompoundTag(), registry);
            var rewardState = com.sande.mythictrpg.quest.reward.RewardClaimState.get(player.server);
            var rewardBefore = rewardState.save(new CompoundTag(), registry);
            data.setQuestPinned(player, target.getId(), true);
            data.setLocked(true);
            h.assertFalse(FtbQuestAdapter.INSTANCE.restoreCompletedMirror(player, binding, completion), "locked FTB reported success");
            data.setLocked(false);
            var login = new net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent(player);
            QuestRuntimeService.INSTANCE.onPlayerLoggedIn(login);
            QuestRuntimeService.INSTANCE.onPlayerLoggedIn(login);
            h.assertTrue(data.isCompleted(target) && data.isCompleted(marker), "completion display not restored");
            h.assertFalse(data.isQuestPinned(player, target.getId()), "completed quest remains pinned");
            h.assertTrue(data.getRewardClaimTime(player.getUUID(), reward).isEmpty(), "display replay auto-claimed FTB reward");
            h.assertTrue(player.getInventory().items.stream().noneMatch(stack -> stack.is(Items.DIAMOND)), "recovery granted item");
            h.assertValueEqual(state.save(new CompoundTag(), registry), before, "recovery repeated quest completion");
            h.assertValueEqual(world.save(new CompoundTag(), registry), worldBefore, "recovery repeated progress/story state");
            h.assertValueEqual(rewardState.save(new CompoundTag(), registry), rewardBefore, "recovery changed reward receipts");
            var missing = plain(binding.questId(), Long.MAX_VALUE - 1, Long.MAX_VALUE - 2);
            h.assertFalse(FtbQuestAdapter.INSTANCE.restoreCompletedMirror(player, missing, completion), "missing FTB definitions admitted");
        } finally {
            data.setLocked(false);
            FtbQuestBindingManager.INSTANCE.apply(new FtbQuestBindingManager.Prepared(previous.byQuestId(), previous.byFtbQuestId()), null, null);
            chapter.deleteSelf(); player.server.getPlayerList().remove(player);
        }
        h.succeed();
    }

    private static FtbQuestBinding building(ResourceLocation quest, Optional<QuestParticipationPolicy> participation) {
        return new FtbQuestBinding(quest, Math.abs(UUID.randomUUID().getMostSignificantBits()), Math.abs(UUID.randomUUID().getMostSignificantBits()),
                QuestCompletionMode.PLAYER_RETURN_TO_NPC, Set.of(GOD), id(), 0, QuestNarrativeRole.SIDE, -1000,
                Optional.empty(), Optional.of(new QuestEvaluationPolicy(Optional.empty(), 1, 1, 60)), Optional.of(POLICY),
                Optional.of(new com.sande.mythictrpg.quest.reward.QuestRewardPolicy(
                        com.sande.mythictrpg.quest.reward.QuestRewardPolicy.Mode.REPLACE, 0, "fixture",
                        List.of(new com.sande.mythictrpg.quest.reward.AffinityRewardEntry(1)), List.of())), participation);
    }
    private static FtbQuestBinding plain(ResourceLocation quest, long target, long marker) {
        return new FtbQuestBinding(quest, target, marker, QuestCompletionMode.PLAYER_RETURN_TO_NPC, Set.of(GOD),
                id(), 1, QuestNarrativeRole.SIDE, -1000, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }
    private static void install(FtbQuestBindingManager.Snapshot previous, List<FtbQuestBinding> additions) {
        var byQuest = new LinkedHashMap<>(previous.byQuestId()); var byFtb = new LinkedHashMap<>(previous.byFtbQuestId());
        additions.forEach(binding -> { byQuest.put(binding.questId(), binding); byFtb.put(binding.ftbQuestId(), binding); });
        FtbQuestBindingManager.INSTANCE.apply(new FtbQuestBindingManager.Prepared(byQuest, byFtb), null, null);
    }
    private static ResourceLocation id() { return ResourceLocation.parse("mythictrpg:access_" + UUID.randomUUID()); }
    private static ServerPlayer connectedPlayer(GameTestHelper h) {
        var cookie = net.minecraft.server.network.CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(), "MirrorFixture"), false);
        var player = new ServerPlayer(h.getLevel().getServer(), h.getLevel(), cookie.gameProfile(), cookie.clientInformation());
        var connection = new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
        new io.netty.channel.embedded.EmbeddedChannel(connection);
        net.neoforged.neoforge.network.registration.NetworkRegistry.configureMockConnection(connection);
        player.server.getPlayerList().placeNewPlayer(connection, player, cookie);
        return player;
    }
}

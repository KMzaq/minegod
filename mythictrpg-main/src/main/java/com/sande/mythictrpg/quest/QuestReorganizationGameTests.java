package com.sande.mythictrpg.quest;

import com.mojang.authlib.GameProfile;
import com.sande.mythictrpg.ai.action.*;
import com.sande.mythictrpg.ai.api.RoomConversationEngineRouter;
import com.sande.mythictrpg.ai.room.*;
import com.sande.mythictrpg.ai.server.ConversationRooms;
import com.sande.mythictrpg.quest.reward.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.*;
import net.neoforged.neoforge.gametest.*;
import java.util.*;

@GameTestHolder("mythictrpg_quest_roster")
@PrefixGameTestTemplate(false)
public final class QuestReorganizationGameTests {
    private static final ResourceLocation GOD = ResourceLocation.parse("mythictrpg:fortuna");
    private static final QuestReorganizationService SERVICE = QuestReorganizationService.INSTANCE;

    @GameTest(templateNamespace = "mythictrpg_quest_roster", template = "empty")
    public static void exactRefundAndRosterSurviveReloadWithoutDuplicateReward(GameTestHelper h) throws Exception {
        try (var fixture = new Fixture(h, 1, true)) {
            var a = fixture.a.player(); var b = fixture.b.player(); var run = fixture.run;
            var gift = new ItemStack(Items.HEART_OF_THE_SEA, 5);
            gift.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("고유 공물"));
            b.getInventory().setItem(0, gift);
            h.assertValueEqual(QuestParticipationService.consumeItems(b, run, fixture.now()), 2, "actual inventory consumption");
            var attention = GodAttentionState.get(a.server);
            var entryQuest = attention.record(GOD).map(GodAttentionRecord::entryQuestId).orElse(fixture.binding.questId());
            attention.recordEntryAssignment(GOD, entryQuest, b.getUUID(), java.time.Instant.now());
            h.assertTrue(run.remove(b.getUUID(), true, fixture.now()), "withdrawal"); fixture.state().reconcileRoster(run);
            var saved = fixture.state().save(new CompoundTag(), a.registryAccess());
            var missingReceipt = saved.copy();
            var entries = missingReceipt.getList("participationRuns", net.minecraft.nbt.Tag.TAG_STRING);
            for (int i = 0; i < entries.size(); i++) {
                var data = com.google.gson.JsonParser.parseString(entries.getString(i)).getAsJsonObject();
                if (!data.get("questId").getAsString().equals(fixture.binding.questId().toString())) continue;
                data.getAsJsonObject("roster").add("deposits", new com.google.gson.JsonObject());
                entries.set(i, net.minecraft.nbt.StringTag.valueOf(data.toString()));
            }
            h.assertTrue(!MythicQuestState.load(missingReceipt, a.registryAccess()).isWritable(), "consumed progress without receipt was accepted");
            var loaded = MythicQuestState.load(saved, a.registryAccess());
            h.assertTrue(loaded.isWritable() && !loaded.isAssigned(fixture.binding.questId(), b.getUUID()), "departed assignment resurrected");
            var restored = loaded.participationRun(fixture.binding.questId()).orElseThrow();
            a.server.getPlayerList().remove(b);
            QuestRosterRefunds.tick(a.server, restored);
            h.assertValueEqual(b.getInventory().countItem(Items.HEART_OF_THE_SEA), 3, "offline refund mutated unloaded inventory");
            fixture.b.reconnect(); RewardClaimService.INSTANCE.deliverQueued(b);
            QuestRosterRefunds.tick(a.server, restored); RewardClaimService.INSTANCE.deliverQueued(b);
            h.assertValueEqual(b.getInventory().countItem(Items.HEART_OF_THE_SEA), 5, "refund duplicated or lost");
            h.assertTrue(b.getInventory().items.stream().filter(s -> s.is(Items.HEART_OF_THE_SEA))
                    .allMatch(s -> s.getHoverName().getString().equals("고유 공물")), "refund lost components");
            loaded.setDirty();
            var reloaded = MythicQuestState.load(loaded.save(new CompoundTag(), a.registryAccess()), a.registryAccess());
            h.assertTrue(reloaded.isWritable(), "refund cursor reload failed");
            QuestRosterRefunds.tick(a.server, reloaded.participationRun(fixture.binding.questId()).orElseThrow());
            RewardClaimService.INSTANCE.deliverQueued(b);
            h.assertValueEqual(b.getInventory().countItem(Items.HEART_OF_THE_SEA), 5, "reload duplicate refund");
            h.assertTrue(GodAttentionState.get(a.server).isFocused(GOD, b.getUUID()), "withdrawal revoked main eligibility");
            var legacy = new MythicQuestState(); var old = legacy.save(new CompoundTag(), a.registryAccess()); old.putInt("dataVersion", 1);
            h.assertTrue(MythicQuestState.load(old, a.registryAccess()).isWritable(), "v1 save migration");
            var corrupt = saved.copy(); corrupt.putInt("dataVersion", 999);
            var rejected = MythicQuestState.load(corrupt, a.registryAccess());
            h.assertTrue(!rejected.isWritable() && rejected.save(new CompoundTag(), a.registryAccess()).equals(corrupt), "corrupt save overwritten");
            h.succeed();
        }
    }

    @GameTest(templateNamespace = "mythictrpg_quest_roster", template = "empty")
    public static void mainReplacementCannotSelectNewPlayers(GameTestHelper h) throws Exception {
        for (var role : List.of(QuestNarrativeRole.MAIN_ENTRY, QuestNarrativeRole.MAIN)) {
            try (var f = new Fixture(h, 1, false, role)) {
                var result = QuestRuntimeService.INSTANCE.validateReplacement(f.c.player(), f.binding.questId(), GOD);
                h.assertTrue(!result.allowed() && result.rejectionStatus() == QuestOperationResult.Status.MAIN_QUEST_RESTRICTED,
                        "replacement bypassed original selection for " + role);
                h.assertTrue(!GodAttentionState.get(f.a.player().server).isFocused(GOD, f.c.player().getUUID()), "validation granted main focus");
            }
        }
        h.succeed();
    }

    @GameTest(templateNamespace = "mythictrpg_quest_roster", template = "empty")
    public static void npcMenuConsentReplacementAndOriginalSubmissionSettleOnce(GameTestHelper h) throws Exception {
        try (var f = new Fixture(h, 2, false); var actor = new QuestContactTestActor(h, f.a.player(), GOD)) {
            var a = f.a.player(); var b = f.b.player(); var c = f.c.player();
            var run = f.run;
            c.setPos(a.position().add(1, 0, 0));
            f.state().recordAssignmentOrigin(f.binding.questId(), a.getUUID(), QuestContactLocation.capture(a));
            var firstRoom = f.roomWith(null);
            actor.meet(a, firstRoom);
            a.getInventory().add(new ItemStack(Items.HEART_OF_THE_SEA, 2));
            QuestParticipationService.INSTANCE.submitItems(a, f.binding.questId());
            QuestParticipationService.INSTANCE.submit(a, f.binding, GOD, 0);
            h.assertTrue(run.snapshot().submissions().containsKey(a.getUUID()) && !run.snapshot().closed(), "first member should wait");
            h.assertTrue(run.remove(b.getUUID(), true, f.now()), "fixture vacancy"); f.state().reconcileRoster(run);
            var room = f.roomWith(c); var scope = f.scope(room);
            var menu = AiActionGateway.submitRoom(a, room.roomId(), room.revision(), GOD, "quest_roster_request", "명단 관리", "참여 요청", Map.of("quest_id", f.binding.questId().toString()), true);
            h.assertValueEqual(menu.status(), AiActionResult.Status.EXECUTED, "AI menu request refused");
            h.assertTrue(!run.participants().contains(c.getUUID()), "menu implicitly enrolled candidate");
            UUID selection = token(a.getUUID(), c.getUUID(), QuestReorganizationService.Operation.RECRUIT, false);
            h.assertTrue(!SERVICE.select(c, selection), "candidate used requester's menu token");
            h.assertTrue(SERVICE.select(a, selection), "candidate invitation not opened");
            UUID consent = token(a.getUUID(), c.getUUID(), QuestReorganizationService.Operation.RECRUIT, true);
            h.assertTrue(SERVICE.contextFor(c, scope).contains("AWAITING_EXPLICIT_CONFIRMATION"), "candidate prompt omitted pending confirmation");
            h.assertTrue(!SERVICE.answer(a, consent, true), "requester consented for candidate");
            h.assertTrue(SERVICE.answer(c, consent, true), "candidate's explicit acceptance failed");
            h.assertTrue(!SERVICE.answer(c, consent, true), "replayed acceptance");
            h.assertTrue(SERVICE.contextFor(a, scope).contains("APPLIED") && SERVICE.contextFor(c, scope).contains("APPLIED"), "NPC follow-up lacks actual result");
            h.assertTrue(SERVICE.contextFor(a, new AiActionScope(UUID.randomUUID(), GOD)).isEmpty(), "result leaked across rooms");
            h.assertTrue(run.progress(c.getUUID(), 0) == 0 && run.snapshot().submissions().containsKey(a.getUUID()), "replacement inherited progress or erased original submission");
            h.assertTrue(!GodAttentionState.get(a.server).isFocused(GOD, c.getUUID()), "replacement granted unselected main access");
            h.assertTrue(!run.remove(a.getUUID(), true, f.now()), "submitted player removed");
            c.getInventory().add(new ItemStack(Items.HEART_OF_THE_SEA, 2));
            QuestParticipationService.INSTANCE.submitItems(c, f.binding.questId());
            actor.meet(c, room);
            QuestParticipationService.INSTANCE.submit(c, f.binding, GOD, 0);
            h.assertTrue(run.snapshot().closed() && run.winners().equals(Set.of(a.getUUID(), c.getUUID())), "replacement did not unblock completion");
            h.assertValueEqual(a.getInventory().countItem(Items.EMERALD), 1, "original member reward");
            h.assertValueEqual(c.getInventory().countItem(Items.EMERALD), 1, "replacement reward");
            h.assertValueEqual(b.getInventory().countItem(Items.EMERALD), 0, "departed player got completion reward");
            RewardClaimService.INSTANCE.deliverQueued(a); RewardClaimService.INSTANCE.deliverQueued(c);
            h.assertValueEqual(a.getInventory().countItem(Items.EMERALD), 1, "reward duplicated");
            var reload = MythicQuestState.load(f.state().save(new CompoundTag(), a.registryAccess()), a.registryAccess());
            h.assertTrue(reload.isWritable() && reload.historyFor(a.getUUID()).size() == 1 && reload.historyFor(c.getUUID()).size() == 1
                    && reload.historyFor(b.getUUID()).isEmpty(), "completion histories incorrect");
            h.succeed();
        }
    }

    @GameTest(templateNamespace = "mythictrpg_quest_roster", template = "empty")
    public static void currentRoomConsentTermsAndAbsenceAreRechecked(GameTestHelper h) throws Exception {
        try (var f = new Fixture(h, 1, false)) {
            var a = f.a.player(); var b = f.b.player();
            var room = f.roomWith(null); var scope = f.scope(room);
            h.assertTrue(SERVICE.open(a, f.binding.questId(), scope).isEmpty(), "open management");
            UUID selection = token(a.getUUID(), a.getUUID(), QuestReorganizationService.Operation.WITHDRAW, false);
            h.assertTrue(SERVICE.select(a, selection), "withdraw preview");
            UUID consent = token(a.getUUID(), a.getUUID(), QuestReorganizationService.Operation.WITHDRAW, true);
            a.getInventory().add(new ItemStack(Items.HEART_OF_THE_SEA, 1));
            QuestParticipationService.INSTANCE.submitItems(a, f.binding.questId());
            h.assertTrue(!SERVICE.answer(a, consent, true) && f.run.participants().contains(a.getUUID()), "changed refund terms applied");
            SERVICE.open(a, f.binding.questId(), scope); SERVICE.select(a, token(a.getUUID(), a.getUUID(), QuestReorganizationService.Operation.WITHDRAW, false));
            consent = token(a.getUUID(), a.getUUID(), QuestReorganizationService.Operation.WITHDRAW, true);
            h.assertTrue(!SERVICE.handleAnswer(a, UUID.randomUUID(), GOD, "네"), "yes in another room applied");
            ConversationRooms.INSTANCE.leave(a, room, "FIXTURE_END");
            h.assertTrue(!SERVICE.answer(a, consent, true), "ended-room confirmation survived");
            room = f.roomWith(null); scope = f.scope(room);
            // Online players are never eligible even if persisted lastSeen is old.
            SERVICE.open(a, f.binding.questId(), scope);
            h.assertTrue(choices().values().stream().noneMatch(v -> v.actor().equals(a.getUUID()) && v.operation() == QuestReorganizationService.Operation.REMOVE_ABSENT), "online player eligible for absence removal");
            a.server.getPlayerList().remove(b);
            // Real logout refreshes presence. Simulate elapsed offline server time, not server downtime.
            f.run.roster(f.run.snapshot().roster().seen(b.getUUID(), 0));
            h.assertTrue(f.run.absent(b.getUUID(), f.now()), "offline threshold fixture");
            SERVICE.open(a, f.binding.questId(), scope);
            SERVICE.select(a, token(a.getUUID(), b.getUUID(), QuestReorganizationService.Operation.REMOVE_ABSENT, false));
            consent = token(a.getUUID(), b.getUUID(), QuestReorganizationService.Operation.REMOVE_ABSENT, true);
            f.b.reconnect();
            h.assertTrue(!SERVICE.answer(a, consent, true) && f.run.participants().contains(b.getUUID()), "reconnected player removed");
            // Policy reload invalidates a still-visible confirmation.
            SERVICE.open(a, f.binding.questId(), scope); SERVICE.select(a, token(a.getUUID(), a.getUUID(), QuestReorganizationService.Operation.WITHDRAW, false));
            consent = token(a.getUUID(), a.getUUID(), QuestReorganizationService.Operation.WITHDRAW, true);
            f.install();
            h.assertTrue(!SERVICE.answer(a, consent, true), "reload did not invalidate confirmation");
            a.server.getPlayerList().remove(b);
            f.run.roster(f.run.snapshot().roster().seen(b.getUUID(), 0));
            SERVICE.open(a, f.binding.questId(), scope);
            h.assertTrue(SERVICE.select(a, token(a.getUUID(), b.getUUID(), QuestReorganizationService.Operation.REMOVE_ABSENT, false)), "absence preview");
            h.assertTrue(SERVICE.answer(a, token(a.getUUID(), b.getUUID(), QuestReorganizationService.Operation.REMOVE_ABSENT, true), true), "confirmed absence removal");
            h.assertTrue(!f.state().isAssigned(f.binding.questId(), b.getUUID()), "absent assignment not removed");
            SERVICE.open(a, f.binding.questId(), scope);
            SERVICE.select(a, token(a.getUUID(), a.getUUID(), QuestReorganizationService.Operation.WITHDRAW, false));
            h.assertTrue(SERVICE.handleAnswer(a, scope.sessionId(), GOD, "네"), "current-room explicit withdrawal answer");
            h.assertTrue(f.run.snapshot().closed() && !f.state().isCompleted(f.binding.questId())
                    && f.run.winners().isEmpty() && a.getInventory().countItem(Items.EMERALD) == 0, "empty roster falsely completed/rewarded");
            h.assertValueEqual(a.getInventory().countItem(Items.HEART_OF_THE_SEA), 0, "refund=false unexpectedly returned submitted item");
            h.succeed();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<UUID, QuestReorganizationService.Choice> choices() throws Exception {
        var field = QuestReorganizationService.class.getDeclaredField("choices"); field.setAccessible(true);
        return (Map<UUID, QuestReorganizationService.Choice>) field.get(SERVICE);
    }
    private static UUID token(UUID actor, UUID target, QuestReorganizationService.Operation operation, boolean confirmation) throws Exception {
        return choices().values().stream().filter(c -> c.actor().equals(actor) && c.target().equals(target)
                && c.operation() == operation && c.confirmation() == confirmation).findFirst().orElseThrow().token();
    }
    private static final class Fixture implements AutoCloseable {
        final Connected a, b, c;
        final FtbQuestBinding binding;
        final QuestParticipationRun run;
        final FtbQuestBindingManager.Snapshot previous;
        final List<dev.ftb.mods.ftbquests.quest.Chapter> chapters = new ArrayList<>();
        final java.lang.reflect.Field installed;
        final boolean wasInstalled;
        Fixture(GameTestHelper h, int minimum, boolean refund) throws Exception {
            this(h, minimum, refund, QuestNarrativeRole.SIDE);
        }
        Fixture(GameTestHelper h, int minimum, boolean refund, QuestNarrativeRole role) throws Exception {
            a = new Connected(h, "RosterA"); b = new Connected(h, "RosterB"); c = new Connected(h, "RosterC");
            installed = RoomConversationEngineRouter.class.getDeclaredField("installed"); installed.setAccessible(true);
            wasInstalled = installed.getBoolean(RoomConversationEngineRouter.INSTANCE); installed.setBoolean(RoomConversationEngineRouter.INSTANCE, true);
            previous = FtbQuestBindingManager.INSTANCE.snapshot();
            var file = dev.ftb.mods.ftbquests.quest.ServerQuestFile.getInstance().orElseThrow();
            for (long id : new long[]{0x1D1A4D1C00000001L, 0x3C0FDADF7B7693BAL}) if (file.getChapter(id) == null) {
                var chapter = new dev.ftb.mods.ftbquests.quest.Chapter(id, file, file.getDefaultChapterGroup()); chapter.onCreated(); chapters.add(chapter);
            }
            var target = new dev.ftb.mods.ftbquests.quest.Quest(file.newID(), file.getChapter(0x1D1A4D1C00000001L)); target.onCreated();
            var marker = new dev.ftb.mods.ftbquests.quest.Quest(file.newID(), file.getChapter(0x3C0FDADF7B7693BAL)); marker.onCreated();
            var policy = new QuestParticipationPolicy(QuestParticipationType.GROUP,
                    List.of(new QuestParticipationPolicy.Objective(QuestParticipationPolicy.ObjectiveKind.ITEM_SUBMISSION, "", "minecraft:heart_of_the_sea", 2)),
                    Optional.empty(), Optional.of(new QuestReorganizationPolicy(true, 1, true, minimum, refund)));
            binding = new FtbQuestBinding(ResourceLocation.parse("mythictrpg:roster_" + UUID.randomUUID()), target.getId(), marker.getId(),
                    QuestCompletionMode.PLAYER_RETURN_TO_NPC, Set.of(), ResourceLocation.parse("mythictrpg:roster_progress"), 0, role, -1000,
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(new QuestRewardPolicy(QuestRewardPolicy.Mode.REPLACE, 0, "fixture",
                    List.of(new NpcRewardEntry(ResourceLocation.parse("minecraft:emerald"), 1)), List.of())), Optional.of(policy));
            install();
            run = new QuestParticipationRun(UUID.randomUUID(), binding.questId().toString(), GOD.toString(), policy, Set.of(a.player().getUUID(), b.player().getUUID()), 0);
            state().addParticipationRun(run);
        }
        void install() {
            var byId = new LinkedHashMap<>(previous.byQuestId()); byId.put(binding.questId(), binding);
            var byFtb = new LinkedHashMap<>(previous.byFtbQuestId()); byFtb.put(binding.ftbQuestId(), binding);
            FtbQuestBindingManager.INSTANCE.apply(new FtbQuestBindingManager.Prepared(byId, byFtb), null, null);
        }
        MythicQuestState state() { return MythicQuestState.get(a.player().server); }
        long now() { return a.player().server.overworld().getGameTime(); }
        ConversationRoomSnapshot roomWith(ServerPlayer candidate) throws Exception {
            var rooms = ConversationRooms.INSTANCE;
            var room = rooms.create(a.player(), RoomType.PRIVATE, List.of(GOD), RecordingScope.STANDARD);
            if (candidate != null) {
                var field = ConversationRooms.class.getDeclaredField("ledger"); field.setAccessible(true);
                var ledger = (ConversationRoomLedger) field.get(rooms);
                var invite = ledger.invite(room.roomId(), room.revision(), GOD.toString(), candidate.getUUID());
                rooms.accept(candidate, invite.token());
                room = rooms.resolveMember(a.player(), room.roomId().toString()).orElseThrow();
            }
            return room;
        }
        AiActionScope scope(ConversationRoomSnapshot room) { return ConversationRooms.INSTANCE.actionScope(a.player(), room.roomId(), room.revision(), GOD).orElseThrow(); }
        @Override public void close() throws Exception {
            SERVICE.clear();
            for (var actor : List.of(a, b, c)) {
                for (var room : ConversationRooms.INSTANCE.memberships(actor.player())) ConversationRooms.INSTANCE.leave(actor.player(), room, "FIXTURE_END");
                actor.close();
            }
            FtbQuestBindingManager.INSTANCE.apply(new FtbQuestBindingManager.Prepared(previous.byQuestId(), previous.byFtbQuestId()), null, null);
            for (var chapter : chapters) chapter.deleteSelf();
            installed.setBoolean(RoomConversationEngineRouter.INSTANCE, wasInstalled);
        }
    }
    private static final class Connected implements AutoCloseable {
        final ServerPlayer player;
        io.netty.channel.embedded.EmbeddedChannel channel;
        Connected(GameTestHelper h, String name) {
            var cookie = net.minecraft.server.network.CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(), name), false);
            player = new ServerPlayer(h.getLevel().getServer(), h.getLevel(), cookie.gameProfile(), cookie.clientInformation()); reconnect();
        }
        ServerPlayer player() { return player; }
        void reconnect() {
            var cookie = net.minecraft.server.network.CommonListenerCookie.createInitial(player.getGameProfile(), false);
            var connection = new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
            if (channel != null) channel.finishAndReleaseAll();
            channel = new io.netty.channel.embedded.EmbeddedChannel(connection);
            net.neoforged.neoforge.network.registration.NetworkRegistry.configureMockConnection(connection);
            player.server.getPlayerList().placeNewPlayer(connection, player, cookie);
        }
        @Override public void close() { if (player.server.getPlayerList().getPlayer(player.getUUID()) != null) player.server.getPlayerList().remove(player); channel.finishAndReleaseAll(); }
    }
}

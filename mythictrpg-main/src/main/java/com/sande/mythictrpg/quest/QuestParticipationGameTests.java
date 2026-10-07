package com.sande.mythictrpg.quest;

import com.mojang.authlib.GameProfile;
import com.sande.mythictrpg.ai.api.RoomConversationEngineRouter;
import com.sande.mythictrpg.ai.room.*;
import com.sande.mythictrpg.ai.server.ConversationRooms;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.time.Instant;
import java.util.*;

@GameTestHolder("mythictrpg_quest_participation")
@PrefixGameTestTemplate(false)
public final class QuestParticipationGameTests {
    private static final String TEMPLATE = "empty";
    private static final ResourceLocation QUEST = ResourceLocation.parse("mythictrpg:participation_test");
    private static final ResourceLocation GOD = ResourceLocation.parse("mythictrpg:fortuna");
    private static QuestParticipationRun run(Set<UUID> players, QuestParticipationPolicy.ObjectiveKind kind) {
        return new QuestParticipationRun(UUID.randomUUID(), QUEST.toString(), GOD.toString(),
                new QuestParticipationPolicy(QuestParticipationType.GROUP,
                    List.of(new QuestParticipationPolicy.Objective(kind, "", "minecraft:heart_of_the_sea", 2)), Optional.empty()), players, 0);
    }

    @GameTest(templateNamespace = "mythictrpg_quest_participation", template = TEMPLATE)
    public static void assignmentsAndCompletionSurviveSavedData(GameTestHelper h) {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        var state = new MythicQuestState(); var run = run(Set.of(a, b), QuestParticipationPolicy.ObjectiveKind.ITEM_SUBMISSION);
        state.addParticipationRun(run);
        h.assertTrue(!state.assign(QUEST, UUID.randomUUID(), GOD, Instant.now()), "outsider assignment was admitted");
        run.advance(a, 0, 2, 1); run.submit(a, 2, GOD.toString(), 2);
        var loaded = MythicQuestState.load(state.save(new CompoundTag(), h.getLevel().registryAccess()), h.getLevel().registryAccess());
        h.assertTrue(loaded.isWritable() && loaded.assignedPlayers(QUEST).equals(Set.of(a, b)), "roster not restored");
        var restored = loaded.participationRun(QUEST).orElseThrow();
        h.assertTrue(restored.ready(a) && !restored.ready(b), "progress leaked between participants");
        h.assertTrue(loaded.tryComplete(QUEST, a, Optional.of(GOD), Instant.now()).isEmpty(), "bypassed group settlement");
        restored.advance(b, 0, 2, 3); restored.submit(b, 2, GOD.toString(), 4); restored.close(4);
        h.assertTrue(loaded.tryComplete(QUEST, a, Optional.of(GOD), Instant.now()).isPresent(), "completion missing");
        restored.awarded(a); restored.awarded(b);
        loaded = MythicQuestState.load(loaded.save(new CompoundTag(), h.getLevel().registryAccess()), h.getLevel().registryAccess());
        h.assertTrue(loaded.historyFor(a).size() == 1 && loaded.historyFor(b).size() == 1, "one group member lost completion history");
        h.assertTrue(loaded.assignedPlayers(QUEST).isEmpty(), "completed assignments retained");
        h.assertTrue(loaded.tryComplete(QUEST, b, Optional.of(GOD), Instant.now()).isEmpty(), "duplicate completion");
        h.succeed();
    }

    @GameTest(templateNamespace = "mythictrpg_quest_participation", template = TEMPLATE)
    public static void legacyAndRejectedDataArePreserved(GameTestHelper h) {
        var state = new MythicQuestState(); UUID player = UUID.randomUUID();
        state.assign(QUEST, player, GOD, Instant.now());
        h.assertTrue(state.assignmentsFor(player).getFirst().origin().isEmpty(), "legacy assignment invented an origin");
        CompoundTag old = state.save(new CompoundTag(), h.getLevel().registryAccess()); old.remove("participationRuns");
        h.assertTrue(MythicQuestState.load(old, h.getLevel().registryAccess()).isAssigned(QUEST, player), "legacy migration failed");
        h.assertTrue(MythicQuestState.load(old, h.getLevel().registryAccess()).assignmentsFor(player).getFirst().origin().isEmpty(), "login invented legacy origin");
        var origin = new QuestContactLocation(ResourceLocation.parse("minecraft:overworld"), 1, 2, 3, 16);
        state.recordAssignmentOrigin(QUEST, player, origin);
        var restoredOrigin = MythicQuestState.load(state.save(new CompoundTag(), h.getLevel().registryAccess()), h.getLevel().registryAccess());
        h.assertValueEqual(restoredOrigin.assignmentsFor(player).getFirst().origin().orElseThrow(), origin, "assignment origin not persisted");
        CompoundTag bad = old.copy(); ListTag corrupt = new ListTag(); corrupt.add(StringTag.valueOf("{}")); bad.put("participationRuns", corrupt);
        var rejected = MythicQuestState.load(bad, h.getLevel().registryAccess());
        h.assertTrue(!rejected.isWritable(), "corrupt data became writable");
        h.assertTrue(rejected.save(new CompoundTag(), h.getLevel().registryAccess()).equals(bad), "corrupt source overwritten");
        var locationJson = com.google.gson.JsonParser.parseString("{\"dimension\":\"minecraft:overworld\",\"x\":1,\"y\":2,\"z\":3,\"radius\":16}").getAsJsonObject();
        h.assertValueEqual(QuestContactLocation.parse(locationJson), origin, "strict authored location parse failed");
        for (String malformed : List.of("\"1\"", "1.5", "2147483648", "true")) {
            var invalid = locationJson.deepCopy(); invalid.add("x", com.google.gson.JsonParser.parseString(malformed));
            try { QuestContactLocation.parse(invalid); throw new AssertionError("invalid return coordinate accepted: " + malformed); }
            catch (IllegalArgumentException | ArithmeticException expected) { }
        }
        var stringRadius = locationJson.deepCopy(); stringRadius.addProperty("radius", "16");
        try { QuestContactLocation.parse(stringRadius); throw new AssertionError("numeric-string return radius accepted"); }
        catch (IllegalArgumentException expected) { }
        h.succeed();
    }

    @GameTest(templateNamespace = "mythictrpg_quest_participation", template = TEMPLATE)
    public static void inventorySubmissionConsumesOnlyRequiredItems(GameTestHelper h) {
        var player = new FakePlayer(h.getLevel(), new GameProfile(UUID.randomUUID(), "SubmissionTest"));
        var other = new FakePlayer(h.getLevel(), new GameProfile(UUID.randomUUID(), "NotEnrolled"));
        var run = run(Set.of(player.getUUID()), QuestParticipationPolicy.ObjectiveKind.ITEM_SUBMISSION);
        player.getInventory().setItem(0, new ItemStack(Items.HEART_OF_THE_SEA, 4));
        player.getInventory().setItem(1, new ItemStack(Items.DIAMOND, 3));
        other.getInventory().setItem(0, new ItemStack(Items.HEART_OF_THE_SEA, 5));
        h.assertValueEqual(QuestParticipationService.consumeItems(other, run, 1), 0, "outsider consumed items");
        h.assertValueEqual(QuestParticipationService.consumeItems(player, run, 1), 2, "wrong consumption count");
        h.assertValueEqual(player.getInventory().getItem(0).getCount(), 2, "overconsumption");
        h.assertValueEqual(player.getInventory().getItem(1).getCount(), 3, "unrelated item consumed");
        h.assertValueEqual(QuestParticipationService.consumeItems(player, run, 2), 0, "duplicate consumption");
        h.assertTrue(run.ready(player.getUUID()) && !run.shouldClose(2), "items prematurely completed return quest");
        h.succeed();
    }

    @GameTest(templateNamespace = "mythictrpg_quest_participation", template = TEMPLATE)
    public static void donationAccumulatesUntilNpcSubmission(GameTestHelper h) {
        var player = new FakePlayer(h.getLevel(), new GameProfile(UUID.randomUUID(), "DonationTest"));
        var run = run(Set.of(player.getUUID()), QuestParticipationPolicy.ObjectiveKind.ITEM_DONATION);
        player.getInventory().setItem(0, new ItemStack(Items.HEART_OF_THE_SEA, 7));
        h.assertValueEqual(QuestParticipationService.consumeItems(player, run, 1), 7, "donation capped at minimum");
        player.getInventory().setItem(0, new ItemStack(Items.HEART_OF_THE_SEA, 3));
        h.assertValueEqual(QuestParticipationService.consumeItems(player, run, 2), 3, "second donation lost");
        h.assertValueEqual(run.score(player.getUUID()), 10, "quantity not available for ranking");
        h.assertValueEqual(run.total(player.getUUID()), 2, "display overflow");
        run.submit(player.getUUID(), run.score(player.getUUID()), GOD.toString(), 3);
        player.getInventory().setItem(0, new ItemStack(Items.HEART_OF_THE_SEA, 1));
        h.assertValueEqual(QuestParticipationService.consumeItems(player, run, 4), 0, "donation changed after final submission");
        h.succeed();
    }

    @GameTest(templateNamespace = "mythictrpg_quest_participation", template = TEMPLATE)
    public static void explicitNearbyJoinConsentAndNpcSettlement(GameTestHelper h) throws Exception {
        var host = connectedPlayer(h); var guest = connectedPlayer(h);
        var spectator = connectedPlayer(h);
        var runtime = com.sande.mythictrpg.ai.server.AiConversationRuntimeService.INSTANCE;
        var service = QuestParticipationService.INSTANCE;
        var manager = FtbQuestBindingManager.INSTANCE; var previous = manager.snapshot();
        var createdChapters = new ArrayList<dev.ftb.mods.ftbquests.quest.Chapter>();
        var translations = translationMap(); var originalTranslations = new HashMap<>(translations);
        var installed = RoomConversationEngineRouter.class.getDeclaredField("installed"); installed.setAccessible(true);
        boolean wasInstalled = installed.getBoolean(RoomConversationEngineRouter.INSTANCE);
        installed.setBoolean(RoomConversationEngineRouter.INSTANCE, true);
        var rooms = ConversationRooms.INSTANCE;
        try (var actor = new QuestContactTestActor(h, host, GOD)) {
            var file = dev.ftb.mods.ftbquests.quest.ServerQuestFile.getInstance().orElseThrow();
            long[] chapters = {0x1D1A4D1C00000001L, 0x3C0FDADF7B7693BAL};
            for (long id : chapters) if (file.getChapter(id) == null) {
                var chapter = new dev.ftb.mods.ftbquests.quest.Chapter(id, file, file.getDefaultChapterGroup());
                chapter.onCreated(); createdChapters.add(chapter);
            }
            var target = new dev.ftb.mods.ftbquests.quest.Quest(file.newID(), file.getChapter(chapters[0])); target.onCreated();
            var marker = new dev.ftb.mods.ftbquests.quest.Quest(file.newID(), file.getChapter(chapters[1])); marker.onCreated();
            var quest = ResourceLocation.parse("mythictrpg:consent_" + UUID.randomUUID());
            var binding = new FtbQuestBinding(quest, target.getId(), marker.getId(), QuestCompletionMode.PLAYER_RETURN_TO_NPC,
                    Set.of(), ResourceLocation.parse("mythictrpg:test_progress"), 0, QuestNarrativeRole.SIDE, -1000,
                    Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.of(new com.sande.mythictrpg.quest.reward.QuestRewardPolicy(
                        com.sande.mythictrpg.quest.reward.QuestRewardPolicy.Mode.REPLACE, 0, "보상",
                        List.of(new com.sande.mythictrpg.quest.reward.AffinityRewardEntry(10)), List.of())),
                    Optional.of(new QuestParticipationPolicy(QuestParticipationType.GROUP,
                        List.of(new QuestParticipationPolicy.Objective(QuestParticipationPolicy.ObjectiveKind.ITEM_SUBMISSION,
                            "", "minecraft:heart_of_the_sea", 2)), Optional.empty())));
            var byId = new LinkedHashMap<>(previous.byQuestId()); byId.put(quest, binding);
            var byFtb = new LinkedHashMap<>(previous.byFtbQuestId()); byFtb.put(target.getId(), binding);
            manager.apply(new FtbQuestBindingManager.Prepared(byId, byFtb), null, null);
            var room = rooms.create(host, RoomType.PUBLIC_MOBILE, List.of(GOD), RecordingScope.STANDARD);
            guest.setPos(host.getX() + 40, host.getY(), host.getZ());
            h.assertTrue(!rooms.publicText(guest, "참여하겠습니다"), "distant player joined");
            guest.setPos(host.getX() + 1, host.getY(), host.getZ());
            h.assertTrue(rooms.publicText(guest, "참여하겠습니다"), "nearby explicit join failed");
            room = rooms.resolveMember(host, room.roomId().toString()).orElseThrow();
            h.assertTrue(room.playerIds().equals(Set.of(host.getUUID(), guest.getUUID())), "spectator auto-joined");
            var scope = rooms.actionScope(host, room.roomId(), room.revision(), GOD).orElseThrow();
            h.assertValueEqual(service.offerRoom(host, binding, GOD, scope).status(), QuestOperationResult.Status.WAITING_FOR_PARTICIPANTS, "offer auto-assigned");
            UUID offer = service.pendingOffer(host.getUUID()).orElseThrow();
            h.assertTrue(!service.answer(spectator, offer, QuestEnrollment.Answer.YES), "outside consent accepted");
            h.assertTrue(service.answer(host, offer, QuestEnrollment.Answer.YES), "host answer rejected");
            h.assertTrue(MythicQuestState.get(host.server).participationRun(quest).isEmpty(), "assigned before final answer");
            rooms.leave(guest, room, "FIXTURE_EXPLICIT_LEAVE");
            h.assertTrue(!service.answer(guest, offer, QuestEnrollment.Answer.YES), "late answer survived conversation change");
            h.assertTrue(rooms.publicText(guest, "다시 참여하겠습니다"), "rejoin failed");
            room = rooms.resolveMember(host, room.roomId().toString()).orElseThrow();
            scope = rooms.actionScope(host, room.roomId(), room.revision(), GOD).orElseThrow();
            h.assertValueEqual(service.offerRoom(host, binding, GOD, scope).status(), QuestOperationResult.Status.WAITING_FOR_PARTICIPANTS, "new consent round blocked");
            UUID oldOffer = offer; offer = service.pendingOffer(host.getUUID()).orElseThrow();
            h.assertTrue(!offer.equals(oldOffer), "consent ID reused");
            h.assertTrue(service.answer(host, offer, QuestEnrollment.Answer.YES), "new host answer rejected");
            h.assertTrue(service.answer(guest, offer, QuestEnrollment.Answer.NO), "decline rejected");
            var run = MythicQuestState.get(host.server).participationRun(quest).orElseThrow();
            h.assertTrue(run.participants().equals(Set.of(host.getUUID())), "decliner assigned");
            h.assertTrue(run.snapshot().mirrors().containsKey(host.getUUID()), "FTB mirror not created");
            h.assertValueEqual(service.submit(guest, binding, GOD, 0).status(), QuestOperationResult.Status.PARTICIPATION_CLOSED, "outsider submission accepted");
            host.getInventory().setItem(0, new ItemStack(Items.HEART_OF_THE_SEA, 4));
            actor.meet(host, room);
            h.assertValueEqual(service.submitItems(host, quest), 2, "authoritative item submission");
            h.assertTrue(!run.snapshot().closed(), "return quest auto-completed on inventory submission");
            h.assertValueEqual(service.confirm(host, quest).status(), QuestOperationResult.Status.COMPLETED, "NPC confirmation failed");
            h.assertValueEqual(com.sande.mythictrpg.data.player.PlayerMythDataService.get(host.server).find(host.getUUID())
                    .orElseThrow().affinities().get(GOD), 10, "completion reward missing");
            h.assertValueEqual(service.confirmRoom(host, quest, scope).status(), QuestOperationResult.Status.PARTICIPATION_CLOSED, "second reward allowed");
            rooms.leave(guest, room, "FIXTURE_EXPLICIT_LEAVE"); runtime.setEnabled(guest, true);
            h.assertTrue(!rooms.resolveMember(host, room.roomId().toString()).orElseThrow().playerIds().contains(guest.getUUID()), "leave+enable revived old membership");
        } finally {
            manager.apply(new FtbQuestBindingManager.Prepared(previous.byQuestId(), previous.byFtbQuestId()), null, null);
            for (var chapter : createdChapters) chapter.deleteSelf();
            translations.clear(); translations.putAll(originalTranslations);
            for (var player : List.of(host, guest, spectator)) {
                rooms.loggedOut(player);
                runtime.onPlayerLoggedOut(new net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent(player));
                player.server.getPlayerList().remove(player);
            }
            installed.setBoolean(RoomConversationEngineRouter.INSTANCE, wasInstalled);
        }
        h.succeed();
    }

    private static net.minecraft.server.level.ServerPlayer connectedPlayer(GameTestHelper h) {
        var cookie = net.minecraft.server.network.CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(), "QuestMock"), false);
        var player = new net.minecraft.server.level.ServerPlayer(h.getLevel().getServer(), h.getLevel(), cookie.gameProfile(), cookie.clientInformation());
        var connection = new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
        new io.netty.channel.embedded.EmbeddedChannel(connection);
        net.neoforged.neoforge.network.registration.NetworkRegistry.configureMockConnection(connection);
        player.server.getPlayerList().placeNewPlayer(connection, player, cookie);
        return player;
    }

    /** Test fixture restores FTB's translation cache so unrelated vanilla mock clients remain isolated. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> translationMap() {
        try {
            var manager = dev.ftb.mods.ftbquests.quest.ServerQuestFile.getInstance().orElseThrow().getTranslationManager();
            var field = manager.getClass().getDeclaredField("map"); field.setAccessible(true);
            return (Map<String, Object>) field.get(manager);
        } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
    }
}

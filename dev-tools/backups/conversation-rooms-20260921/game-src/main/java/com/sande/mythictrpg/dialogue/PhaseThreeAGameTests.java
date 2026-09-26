package com.sande.mythictrpg.dialogue;

import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.data.god.GodDefinitionManager;
import com.sande.mythictrpg.data.player.GodKnowledgeSnapshot;
import com.sande.mythictrpg.data.player.ParticipationStatus;
import com.sande.mythictrpg.data.player.PlayerGodKnowledgeService;
import com.sande.mythictrpg.data.player.PlayerMythDataService;
import com.sande.mythictrpg.dialogue.api.DialogueDisplayOptions;
import com.sande.mythictrpg.dialogue.api.DialoguePriority;
import com.sande.mythictrpg.dialogue.api.DialogueSources;
import com.sande.mythictrpg.dialogue.api.GodDialogueRequest;
import com.sande.mythictrpg.dialogue.playback.DialogueEnqueueResult;
import com.sande.mythictrpg.dialogue.playback.DialoguePlaybackStage;
import com.sande.mythictrpg.dialogue.playback.DialoguePlaybackState;
import com.sande.mythictrpg.dialogue.presentation.DialogueComponentSanitizer;
import com.sande.mythictrpg.dialogue.presentation.DialogueTimingPolicy;
import com.sande.mythictrpg.dialogue.server.DialoguePresentationBuilder;
import com.sande.mythictrpg.network.ClientDialoguePayload;
import io.netty.buffer.Unpooled;
import net.minecraft.core.UUIDUtil;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.network.connection.ConnectionType;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

@GameTestHolder(MythicTrpg.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PhaseThreeAGameTests {
    private static final String TEMPLATE = "bastion/mobs/empty";
    private static final ResourceLocation LUBRAS = ResourceLocation.fromNamespaceAndPath(
            MythicTrpg.MOD_ID, "lubras");

    private PhaseThreeAGameTests() {
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void serverPresentationUsesPlayerSpecificIdentityWithoutMutation(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        var players = PlayerMythDataService.get(server);
        var knowledge = PlayerGodKnowledgeService.get(server);
        UUID unidentified = UUID.randomUUID();
        UUID identified = UUID.randomUUID();
        players.ensureProfile(unidentified);
        players.ensureProfile(identified);
        knowledge.identifyGod(identified, LUBRAS);
        GodKnowledgeSnapshot unidentifiedBefore = knowledge.snapshot(unidentified, LUBRAS);
        GodKnowledgeSnapshot identifiedBefore = knowledge.snapshot(identified, LUBRAS);
        String actualName = GodDefinitionManager.INSTANCE.find(LUBRAS).orElseThrow().displayName().getString();
        GodDialogueRequest request = GodDialogueRequest.literal(LUBRAS, "You were being watched.");

        try {
            ClientDialoguePayload hidden = DialoguePresentationBuilder.INSTANCE.buildGod(
                    server, unidentified, request, UUID.randomUUID());
            ClientDialoguePayload revealed = DialoguePresentationBuilder.INSTANCE.buildGod(
                    server, identified, request, UUID.randomUUID());

            helper.assertValueEqual(hidden.speakerDisplayName().getString(), "???",
                    "unidentified speaker presentation");
            helper.assertValueEqual(revealed.speakerDisplayName().getString(), actualName,
                    "identified speaker presentation");
            helper.assertValueEqual(hidden.source(), DialogueSources.GOD, "dialogue source");
            helper.assertValueEqual(knowledge.snapshot(unidentified, LUBRAS), unidentifiedBefore,
                    "unidentified gameplay state changed by presentation");
            helper.assertValueEqual(knowledge.snapshot(identified, LUBRAS), identifiedBefore,
                    "identified gameplay state changed by presentation");
        } finally {
            players.setParticipationStatus(unidentified, ParticipationStatus.ARCHIVED);
            players.setParticipationStatus(identified, ParticipationStatus.ARCHIVED);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void sanitizerAllowsSafeComponentsAndStripsInteractiveStyle(GameTestHelper helper) {
        Component safeLiteral = DialogueComponentSanitizer.sanitize(
                Component.literal("Safe literal"), 128, "literal");
        helper.assertValueEqual(safeLiteral.getString(), "Safe literal", "safe literal");

        Component nestedUnsafe = Component.literal("argument").withStyle(style -> style
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/op"))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("secret")))
                .withInsertion("insert"));
        Component translated = Component.translatableWithFallback(
                "mythictrpg.test.translation", "Translated %s", nestedUnsafe);
        Component sanitized = DialogueComponentSanitizer.sanitize(translated, 128, "translation");
        helper.assertTrue(sanitized.getStyle().getClickEvent() == null, "root click event was retained");
        Object nested = ((net.minecraft.network.chat.contents.TranslatableContents)
                sanitized.getContents()).getArgs()[0];
        helper.assertTrue(nested instanceof Component, "translation component argument was lost");
        Component nestedComponent = (Component) nested;
        helper.assertTrue(nestedComponent.getStyle().getClickEvent() == null,
                "nested click event was retained");
        helper.assertTrue(nestedComponent.getStyle().getHoverEvent() == null,
                "nested hover event was retained");
        helper.assertTrue(nestedComponent.getStyle().getInsertion() == null,
                "nested insertion was retained");

        expectRejected(helper, () -> DialogueComponentSanitizer.sanitize(
                Component.selector("@a", java.util.Optional.empty()), 128, "selector"),
                "selector component");
        expectRejected(helper, () -> DialogueComponentSanitizer.sanitize(
                nestedChain(DialogueComponentSanitizer.MAX_DEPTH + 1), 1024, "depth"),
                "component depth");
        expectRejected(helper, () -> DialogueComponentSanitizer.sanitize(
                componentWithNodes(DialogueComponentSanitizer.MAX_NODES + 1), 1024, "nodes"),
                "component node count");
        expectRejected(helper, () -> DialogueComponentSanitizer.sanitize(
                Component.literal("x".repeat(129)), 128, "speaker"), "speaker length");
        expectRejected(helper, () -> DialogueComponentSanitizer.sanitize(
                Component.literal("x".repeat(1025)), 1024, "dialogue"), "dialogue length");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void payloadCodecRoundTripDoesNotExposeHiddenGodIdentity(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        UUID playerId = UUID.randomUUID();
        PlayerMythDataService.get(server).ensureProfile(playerId);
        ClientDialoguePayload original = DialoguePresentationBuilder.INSTANCE.buildGod(
                server, playerId, GodDialogueRequest.literal(LUBRAS, "You were being watched."),
                UUID.randomUUID());
        String actualName = GodDefinitionManager.INSTANCE.find(LUBRAS).orElseThrow().displayName().getString();
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(),
                server.registryAccess(), ConnectionType.NEOFORGE);
        try {
            ClientDialoguePayload.STREAM_CODEC.encode(buffer, original);
            byte[] encoded = new byte[buffer.readableBytes()];
            buffer.getBytes(buffer.readerIndex(), encoded);
            ClientDialoguePayload decoded = ClientDialoguePayload.STREAM_CODEC.decode(buffer);

            helper.assertValueEqual(decoded, original, "payload codec round trip");
            helper.assertTrue(encoded.length <= ClientDialoguePayload.MAX_ENCODED_BYTES,
                    "payload exceeded Mythic TRPG byte limit");
            helper.assertTrue(!contains(encoded, LUBRAS.toString().getBytes(StandardCharsets.UTF_8)),
                    "hidden God ID was present in encoded payload");
            helper.assertTrue(!contains(encoded, actualName.getBytes(StandardCharsets.UTF_8)),
                    "hidden God display name was present in encoded payload");
            helper.assertTrue(!original.toString().contains(LUBRAS.toString()),
                    "hidden God ID was present in payload fields");
        } finally {
            buffer.release();
            PlayerMythDataService.get(server).setParticipationStatus(playerId, ParticipationStatus.ARCHIVED);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void invalidPayloadBoundariesAreRejected(GameTestHelper helper) {
        expectRejected(helper, () -> new ClientDialoguePayload(UUID.randomUUID(),
                Component.literal("speaker"), Component.selector("@a", java.util.Optional.empty()),
                DialogueSources.GOD, DialoguePriority.NORMAL, 8, 40, 12), "dynamic payload component");
        expectRejected(helper, () -> payload(UUID.randomUUID(), "bad timing",
                DialoguePriority.NORMAL, 8, 39, 12), "invalid hold timing");

        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(),
                helper.getLevel().registryAccess(), ConnectionType.NEOFORGE);
        try {
            UUIDUtil.STREAM_CODEC.encode(buffer, UUID.randomUUID());
            ComponentSerialization.STREAM_CODEC.encode(buffer, Component.literal("speaker"));
            ComponentSerialization.STREAM_CODEC.encode(buffer, Component.literal("dialogue"));
            ResourceLocation.STREAM_CODEC.encode(buffer, DialogueSources.GOD);
            buffer.writeByte(99);
            ByteBufCodecs.VAR_INT.encode(buffer, 8);
            ByteBufCodecs.VAR_INT.encode(buffer, 40);
            ByteBufCodecs.VAR_INT.encode(buffer, 12);
            expectRejected(helper, () -> ClientDialoguePayload.STREAM_CODEC.decode(buffer),
                    "unknown priority codec boundary");
        } finally {
            buffer.release();
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void queueHonorsFifoPriorityInterruptAndDuplicates(GameTestHelper helper) {
        DialoguePlaybackState state = new DialoguePlaybackState();
        ClientDialoguePayload first = payload("first", DialoguePriority.NORMAL);
        ClientDialoguePayload normal = payload("normal", DialoguePriority.NORMAL);
        ClientDialoguePayload importantOne = payload("important-one", DialoguePriority.IMPORTANT);
        ClientDialoguePayload importantTwo = payload("important-two", DialoguePriority.IMPORTANT);
        helper.assertValueEqual(state.enqueue(first), DialogueEnqueueResult.STARTED, "initial queue result");
        state.enqueue(normal);
        state.enqueue(importantOne);
        state.enqueue(importantTwo);
        helper.assertValueEqual(state.pending().stream().map(p -> p.dialogueText().getString()).toList(),
                List.of("important-one", "important-two", "normal"), "priority queue order");
        helper.assertValueEqual(state.enqueue(normal), DialogueEnqueueResult.DUPLICATE,
                "duplicate message ID");

        ClientDialoguePayload critical = payload("critical", DialoguePriority.CRITICAL);
        helper.assertValueEqual(state.enqueue(critical), DialogueEnqueueResult.INTERRUPTED,
                "critical interrupt result");
        helper.assertValueEqual(state.snapshot().current().orElseThrow().messageId(), critical.messageId(),
                "critical did not become active");
        helper.assertTrue(state.pending().stream().noneMatch(p -> p.messageId().equals(first.messageId())),
                "interrupted message was requeued");
        helper.assertValueEqual(state.interruptCount(), 1L, "interrupt counter");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void queueOverflowPoliciesRemainBounded(GameTestHelper helper) {
        DialoguePlaybackState normalState = filledState(DialoguePriority.NORMAL);
        ClientDialoguePayload droppedNormal = payload("drop-normal", DialoguePriority.NORMAL);
        helper.assertValueEqual(normalState.enqueue(droppedNormal), DialogueEnqueueResult.DROPPED_OVERFLOW,
                "NORMAL overflow result");
        helper.assertValueEqual(normalState.pending().size(), DialoguePlaybackState.MAX_PENDING,
                "NORMAL overflow queue size");

        DialoguePlaybackState importantState = filledState(DialoguePriority.NORMAL);
        UUID oldestNormal = importantState.pending().getFirst().messageId();
        ClientDialoguePayload incomingImportant = payload("incoming-important", DialoguePriority.IMPORTANT);
        helper.assertValueEqual(importantState.enqueue(incomingImportant),
                DialogueEnqueueResult.QUEUED_AFTER_EVICTION, "IMPORTANT overflow result");
        helper.assertTrue(importantState.pending().stream().noneMatch(p -> p.messageId().equals(oldestNormal)),
                "IMPORTANT did not evict oldest NORMAL");
        helper.assertValueEqual(importantState.pending().getFirst().messageId(), incomingImportant.messageId(),
                "IMPORTANT was not ordered before NORMAL");

        DialoguePlaybackState criticalState = new DialoguePlaybackState();
        criticalState.enqueue(payload("active-critical", DialoguePriority.CRITICAL));
        for (int index = 0; index < DialoguePlaybackState.MAX_PENDING; index++) {
            criticalState.enqueue(payload("critical-" + index, DialoguePriority.CRITICAL));
        }
        UUID oldestCritical = criticalState.pending().getFirst().messageId();
        ClientDialoguePayload newestCritical = payload("newest-critical", DialoguePriority.CRITICAL);
        helper.assertValueEqual(criticalState.enqueue(newestCritical),
                DialogueEnqueueResult.QUEUED_AFTER_EVICTION, "CRITICAL overflow result");
        helper.assertTrue(criticalState.pending().stream().noneMatch(p -> p.messageId().equals(oldestCritical)),
                "CRITICAL did not evict oldest pending CRITICAL");
        helper.assertTrue(criticalState.pending().stream().anyMatch(
                p -> p.messageId().equals(newestCritical.messageId())), "new CRITICAL was not queued");
        helper.assertValueEqual(criticalState.pending().size(), DialoguePlaybackState.MAX_PENDING,
                "CRITICAL overflow queue size");

        for (int index = 0; index < 140; index++) {
            normalState.enqueue(payload("recent-" + index, DialoguePriority.NORMAL));
        }
        helper.assertValueEqual(normalState.recentIdCount(), DialoguePlaybackState.MAX_RECENT_IDS,
                "recent ID bound");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void fadeStateMachineAdvancesToNextMessage(GameTestHelper helper) {
        DialoguePlaybackState state = new DialoguePlaybackState();
        ClientDialoguePayload first = payload(UUID.randomUUID(), "fade-first",
                DialoguePriority.NORMAL, 2, 40, 2);
        ClientDialoguePayload second = payload(UUID.randomUUID(), "fade-second",
                DialoguePriority.NORMAL, 2, 40, 2);
        state.enqueue(first);
        state.enqueue(second);
        helper.assertValueEqual(state.snapshot().stage(), DialoguePlaybackStage.FADE_IN, "initial fade stage");
        helper.assertValueEqual(state.snapshot().alpha(), 0.0F, "initial alpha");
        state.tick(true);
        helper.assertValueEqual(state.snapshot().alpha(), 0.5F, "fade-in alpha");
        state.tick(true);
        helper.assertValueEqual(state.snapshot().stage(), DialoguePlaybackStage.HOLD, "hold stage");
        tick(state, 40);
        helper.assertValueEqual(state.snapshot().stage(), DialoguePlaybackStage.FADE_OUT, "fade-out stage");
        state.tick(false);
        helper.assertValueEqual(state.snapshot().alpha(), 1.0F, "paused transition advanced unexpectedly");
        state.tick(true);
        helper.assertValueEqual(state.snapshot().alpha(), 0.5F, "fade-out alpha");
        state.tick(true);
        helper.assertValueEqual(state.snapshot().current().orElseThrow().messageId(), second.messageId(),
                "next queue item did not activate");
        helper.assertValueEqual(state.snapshot().stage(), DialoguePlaybackStage.FADE_IN,
                "next queue item stage");
        helper.succeed();
    }

    private static DialoguePlaybackState filledState(DialoguePriority pendingPriority) {
        DialoguePlaybackState state = new DialoguePlaybackState();
        state.enqueue(payload("active", DialoguePriority.CRITICAL));
        for (int index = 0; index < DialoguePlaybackState.MAX_PENDING; index++) {
            state.enqueue(payload("pending-" + index, pendingPriority));
        }
        return state;
    }

    private static ClientDialoguePayload payload(String text, DialoguePriority priority) {
        return payload(UUID.randomUUID(), text, priority, 8, 40, 12);
    }

    private static ClientDialoguePayload payload(UUID messageId, String text, DialoguePriority priority,
            int fadeIn, int hold, int fadeOut) {
        return new ClientDialoguePayload(messageId, Component.literal("Speaker"), Component.literal(text),
                DialogueSources.SYSTEM, priority, fadeIn, hold, fadeOut);
    }

    private static void tick(DialoguePlaybackState state, int count) {
        for (int index = 0; index < count; index++) {
            state.tick(true);
        }
    }

    private static Component nestedChain(int nodes) {
        var root = Component.literal("root");
        var current = root;
        for (int index = 1; index < nodes; index++) {
            var child = Component.literal("child");
            current.append(child);
            current = child;
        }
        return root;
    }

    private static Component componentWithNodes(int nodes) {
        var root = Component.literal("root");
        for (int index = 1; index < nodes; index++) {
            root.append(Component.literal("n"));
        }
        return root;
    }

    private static boolean contains(byte[] source, byte[] target) {
        outer:
        for (int start = 0; start <= source.length - target.length; start++) {
            for (int index = 0; index < target.length; index++) {
                if (source[start + index] != target[index]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    private static void expectRejected(GameTestHelper helper, Runnable action, String description) {
        try {
            action.run();
            helper.fail("Expected rejection: " + description);
        } catch (io.netty.handler.codec.DecoderException | IllegalArgumentException expected) {
            // Expected validation boundary.
        }
    }
}

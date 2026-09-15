package com.sande.mythictrpg.gameplay.promotion;

import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.interaction.api.InteractionMode;
import com.sande.mythictrpg.interaction.api.InteractionSignal;
import com.sande.mythictrpg.interaction.api.InteractionSignalType;
import com.sande.mythictrpg.interaction.spontaneous.SpontaneousSubmissionResult;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@GameTestHolder(MythicTrpg.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PhaseFourAFourBGameplayGameTests {
    private static final String TEMPLATE = "bastion/mobs/empty";
    private static final InteractionSignalType<GameplayActionPayload> SIGNAL_TYPE =
            new InteractionSignalType<>(id("phase4a4b_gameplay"), InteractionMode.SPONTANEOUS,
                    GameplayActionPayload.class);

    private PhaseFourAFourBGameplayGameTests() {
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void adapterMapsAllSubmissionResults(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        InteractionSignal<GameplayActionPayload> signal = signal(UUID.randomUUID());

        for (SpontaneousSubmissionResult submission : SpontaneousSubmissionResult.values()) {
            GameplaySpontaneousInteractionSink sink = new GameplaySpontaneousInteractionSink(
                    (ignoredServer, ignoredSignal) -> submission);
            helper.assertValueEqual(sink.accept(server, signal), expected(submission),
                    "mapping for " + submission);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void adapterFailsClosedForNullAndException(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        InteractionSignal<GameplayActionPayload> signal = signal(UUID.randomUUID());
        GameplaySpontaneousInteractionSink nullResult = new GameplaySpontaneousInteractionSink(
                (ignoredServer, ignoredSignal) -> null);
        GameplaySpontaneousInteractionSink throwing = new GameplaySpontaneousInteractionSink(
                (ignoredServer, ignoredSignal) -> {
                    throw new IllegalStateException("expected adapter failure");
                });

        helper.assertValueEqual(nullResult.accept(server, signal), GameplaySignalResult.FAILED,
                "null submission result");
        helper.assertValueEqual(throwing.accept(server, signal), GameplaySignalResult.FAILED,
                "throwing submission result");
        helper.assertValueEqual(throwing.accept(null, signal), GameplaySignalResult.FAILED,
                "null server result");
        helper.assertValueEqual(throwing.accept(server, null), GameplaySignalResult.FAILED,
                "null signal result");
        helper.succeed();
    }

    private static GameplaySignalResult expected(SpontaneousSubmissionResult result) {
        return switch (result) {
            case ACCEPTED -> GameplaySignalResult.ACCEPTED;
            case UNAVAILABLE -> GameplaySignalResult.UNAVAILABLE;
            case REJECTED -> GameplaySignalResult.REJECTED;
            case FAILED -> GameplaySignalResult.FAILED;
        };
    }

    private static InteractionSignal<GameplayActionPayload> signal(UUID playerId) {
        GameplayActionPayload payload = new GameplayActionPayload(id("phase4a4b_rule"),
                id("phase4a4b_observation"), Optional.empty(),
                new BlockBrokenEvidence(id("phase4a4b_block"), Level.OVERWORLD.location()));
        return new InteractionSignal<>(SIGNAL_TYPE, playerId, Set.of(), payload);
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MythicTrpg.MOD_ID, path);
    }
}

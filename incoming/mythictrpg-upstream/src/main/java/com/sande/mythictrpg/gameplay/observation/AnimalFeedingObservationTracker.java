package com.sande.mythictrpg.gameplay.observation;

import com.sande.mythictrpg.MythicTrpg;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

public final class AnimalFeedingObservationTracker {
    public static final AnimalFeedingObservationTracker INSTANCE = new AnimalFeedingObservationTracker();
    public static final int MAX_PENDING_PER_PLAYER = 64;
    public static final int MAX_PENDING_SERVER = 1_024;
    public static final long MAX_PENDING_AGE_TICKS = 2;

    private static final Map<MinecraftServer, RuntimeState> STATES = new IdentityHashMap<>();

    private AnimalFeedingObservationTracker() {
    }

    public void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (event.isCanceled() || !(event.getTarget() instanceof Animal animal)) {
            return;
        }
        captureCandidate(event.getEntity(), animal, event.getHand());
    }

    public void onServerTickPost(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        RuntimeState state = STATES.get(server);
        if (state != null) {
            state.verifyAndPublish(server.overworld().getGameTime());
        }
    }

    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            removePlayerIfPresent(player.server, player.getUUID());
        }
    }

    public static void discard(MinecraftServer server) {
        requireServerThread(server);
        STATES.remove(server);
    }

    static boolean hasStateForTesting(MinecraftServer server) {
        requireServerThread(server);
        return STATES.containsKey(server);
    }

    CaptureResult captureCandidate(Player player, Animal animal, InteractionHand hand) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(animal, "animal");
        Objects.requireNonNull(hand, "hand");
        if (!(player instanceof ServerPlayer serverPlayer)
                || !(animal.level() instanceof ServerLevel level)
                || player.level() != animal.level()) {
            return CaptureResult.REJECTED_NOT_SERVER_PLAYER;
        }

        MinecraftServer server = serverPlayer.server;
        requireServerThread(server);
        ItemStack heldStack = serverPlayer.getItemInHand(hand);
        if (heldStack.isEmpty()) {
            return CaptureResult.REJECTED_EMPTY_ITEM;
        }
        if (!animal.isFood(heldStack)) {
            return CaptureResult.REJECTED_NOT_FOOD;
        }

        ResourceLocation entityTypeId = BuiltInRegistries.ENTITY_TYPE.getResourceKey(animal.getType())
                .map(ResourceKey::location).orElse(null);
        ResourceLocation foodItemId = BuiltInRegistries.ITEM.getResourceKey(heldStack.getItem())
                .map(ResourceKey::location).orElse(null);
        if (entityTypeId == null || foodItemId == null) {
            return CaptureResult.REJECTED_UNREGISTERED;
        }

        int preAge = animal.getAge();
        int preLoveTime = animal.getInLoveTime();
        ExpectedOutcome expectedOutcome;
        if (preAge < 0) {
            expectedOutcome = ExpectedOutcome.GROWTH;
        } else if (preAge == 0 && preLoveTime <= 0 && animal.canFallInLove()) {
            expectedOutcome = ExpectedOutcome.LOVE;
        } else {
            return CaptureResult.REJECTED_INELIGIBLE_STATE;
        }

        Candidate candidate = new Candidate(serverPlayer.getUUID(), animal.getUUID(), hand,
                level.dimension(), entityTypeId, foodItemId, preAge, preLoveTime,
                level.getGameTime(), expectedOutcome);
        return STATES.computeIfAbsent(server, ignored -> new RuntimeState(server)).capture(candidate);
    }

    int verifyForTesting(MinecraftServer server, long gameTime) {
        requireServerThread(server);
        RuntimeState state = STATES.get(server);
        return state == null ? 0 : state.verifyAndPublish(gameTime);
    }

    int pendingCountForTesting(MinecraftServer server) {
        requireServerThread(server);
        RuntimeState state = STATES.get(server);
        return state == null ? 0 : state.pending.size();
    }

    private static void removePlayerIfPresent(MinecraftServer server, UUID playerId) {
        requireServerThread(server);
        RuntimeState state = STATES.get(server);
        if (state != null) {
            state.removePlayer(playerId);
        }
    }

    private static void requireServerThread(MinecraftServer server) {
        if (!server.isSameThread()) {
            throw new IllegalStateException("Animal feeding observation tracking must run on the server thread");
        }
    }

    enum CaptureResult {
        CAPTURED,
        DEDUPLICATED,
        REJECTED_NOT_SERVER_PLAYER,
        REJECTED_EMPTY_ITEM,
        REJECTED_NOT_FOOD,
        REJECTED_UNREGISTERED,
        REJECTED_INELIGIBLE_STATE,
        REJECTED_LIMIT
    }

    private enum ExpectedOutcome {
        LOVE,
        GROWTH
    }

    private static final class RuntimeState {
        private static final Comparator<BatchKey> BATCH_ORDER = Comparator
                .comparing((BatchKey key) -> key.playerId().toString())
                .thenComparing(BatchKey::entityTypeId);
        private static final Comparator<EntryKey> ENTRY_ORDER = Comparator
                .comparing(EntryKey::foodItemId)
                .thenComparing(EntryKey::outcome);

        private final MinecraftServer server;
        private final Map<CandidateKey, Candidate> pending = new HashMap<>();
        private final Map<UUID, Integer> pendingByPlayer = new HashMap<>();
        private long lastLimitWarningTick = Long.MIN_VALUE;

        private RuntimeState(MinecraftServer server) {
            this.server = server;
        }

        private CaptureResult capture(Candidate candidate) {
            CandidateKey key = new CandidateKey(candidate.playerId(), candidate.targetId(), candidate.hand());
            Candidate existing = pending.get(key);
            if (existing != null) {
                return CaptureResult.DEDUPLICATED;
            }
            int playerCount = pendingByPlayer.getOrDefault(candidate.playerId(), 0);
            if (pending.size() >= MAX_PENDING_SERVER || playerCount >= MAX_PENDING_PER_PLAYER) {
                warnLimit(candidate.capturedGameTime());
                return CaptureResult.REJECTED_LIMIT;
            }
            pending.put(key, candidate);
            pendingByPlayer.put(candidate.playerId(), playerCount + 1);
            return CaptureResult.CAPTURED;
        }

        private int verifyAndPublish(long gameTime) {
            requireServerThread(server);
            Map<BatchKey, Map<EntryKey, Integer>> batches = new TreeMap<>(BATCH_ORDER);
            Iterator<Map.Entry<CandidateKey, Candidate>> iterator = pending.entrySet().iterator();
            while (iterator.hasNext()) {
                Candidate candidate = iterator.next().getValue();
                Verification verification = verify(candidate, gameTime);
                if (verification.outcome() != null) {
                    BatchKey batchKey = new BatchKey(candidate.playerId(), candidate.entityTypeId());
                    EntryKey entryKey = new EntryKey(candidate.foodItemId(), verification.outcome());
                    batches.computeIfAbsent(batchKey, ignored -> new TreeMap<>(ENTRY_ORDER))
                            .merge(entryKey, 1, Math::addExact);
                    remove(iterator, candidate.playerId());
                } else if (verification.terminal()
                        || elapsedTicks(candidate.capturedGameTime(), gameTime) >= MAX_PENDING_AGE_TICKS) {
                    remove(iterator, candidate.playerId());
                }
            }

            for (Map.Entry<BatchKey, Map<EntryKey, Integer>> batch : batches.entrySet()) {
                List<AnimalFeedEntry> entries = new ArrayList<>();
                batch.getValue().forEach((key, count) ->
                        entries.add(new AnimalFeedEntry(key.foodItemId(), key.outcome(), count)));
                AnimalFedPayload payload = new AnimalFedPayload(batch.getKey().entityTypeId(), entries);
                GameplayIngressService.INSTANCE.accept(server,
                        new GameplayObservation<>(GameplayObservationTypes.ANIMAL_FED,
                                batch.getKey().playerId(), gameTime, payload));
            }
            return batches.size();
        }

        private Verification verify(Candidate candidate, long gameTime) {
            ServerLevel level = server.getLevel(candidate.dimension());
            if (level == null) {
                return Verification.TERMINAL_NO_MATCH;
            }
            Entity entity = level.getEntity(candidate.targetId());
            if (!(entity instanceof Animal animal) || !animal.isAlive()
                    || !BuiltInRegistries.ENTITY_TYPE.getKey(animal.getType()).equals(candidate.entityTypeId())) {
                return Verification.TERMINAL_NO_MATCH;
            }

            if (candidate.expectedOutcome() == ExpectedOutcome.LOVE) {
                if (candidate.preLoveTime() <= 0 && animal.getInLoveTime() > 0) {
                    ServerPlayer cause = animal.getLoveCause();
                    if (cause == null || cause.getUUID().equals(candidate.playerId())) {
                        return new Verification(FeedingOutcome.LOVE_MODE, true);
                    }
                    return Verification.TERMINAL_NO_MATCH;
                }
                return Verification.PENDING;
            }

            long elapsed = elapsedTicks(candidate.capturedGameTime(), gameTime);
            long naturalGrowthAllowance = Math.max(1L, elapsed + 1L);
            long actualGrowth = (long)animal.getAge() - candidate.preAge();
            if (actualGrowth > naturalGrowthAllowance) {
                return new Verification(FeedingOutcome.GROWTH_ACCELERATED, true);
            }
            return Verification.PENDING;
        }

        private void removePlayer(UUID playerId) {
            Iterator<Map.Entry<CandidateKey, Candidate>> iterator = pending.entrySet().iterator();
            while (iterator.hasNext()) {
                Candidate candidate = iterator.next().getValue();
                if (candidate.playerId().equals(playerId)) {
                    remove(iterator, playerId);
                }
            }
        }

        private void remove(Iterator<?> iterator, UUID playerId) {
            iterator.remove();
            int remaining = pendingByPlayer.getOrDefault(playerId, 1) - 1;
            if (remaining <= 0) {
                pendingByPlayer.remove(playerId);
            } else {
                pendingByPlayer.put(playerId, remaining);
            }
        }

        private void warnLimit(long gameTime) {
            if (lastLimitWarningTick != gameTime) {
                lastLimitWarningTick = gameTime;
                MythicTrpg.LOGGER.warn("Animal feeding candidate limit reached (server={}, perPlayer={}); "
                                + "additional candidates will be rejected for this tick.",
                        MAX_PENDING_SERVER, MAX_PENDING_PER_PLAYER);
            }
        }
    }

    private static long elapsedTicks(long capturedGameTime, long currentGameTime) {
        return currentGameTime <= capturedGameTime ? 0L : currentGameTime - capturedGameTime;
    }

    private record CandidateKey(UUID playerId, UUID targetId, InteractionHand hand) {
    }

    private record Candidate(UUID playerId, UUID targetId, InteractionHand hand,
                             ResourceKey<Level> dimension, ResourceLocation entityTypeId,
                             ResourceLocation foodItemId, int preAge, int preLoveTime,
                             long capturedGameTime, ExpectedOutcome expectedOutcome) {
    }

    private record BatchKey(UUID playerId, ResourceLocation entityTypeId) {
    }

    private record EntryKey(ResourceLocation foodItemId, FeedingOutcome outcome) {
    }

    private record Verification(FeedingOutcome outcome, boolean terminal) {
        private static final Verification PENDING = new Verification(null, false);
        private static final Verification TERMINAL_NO_MATCH = new Verification(null, true);
    }
}

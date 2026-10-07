package com.sande.mythictrpg.power;

import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.data.player.PlayerMythDataService;
import com.sande.mythictrpg.data.world.MythicWorldState;
import com.sande.mythictrpg.quest.dynamic.*;
import com.sande.mythictrpg.quest.reward.RewardClaimState;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.IOUtilities;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

/** Game-owned inputs only. No inventory scan, numeric admin mutation or model-supplied power source. */
@EventBusSubscriber(modid = MythicTrpg.MOD_ID)
public final class CombatPowerRuntime {
    private static final Map<MinecraftServer, Entry> ENTRIES = new IdentityHashMap<>();
    private static final class Entry {
        final UUID session = UUID.randomUUID();
        final RewardPowerState state;
        final Path dataDirectory;
        final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "myth-reward-power-fence"); thread.setDaemon(true); return thread;
        });
        boolean ready, stopping;
        volatile long mutations;
        String reason = "ACQUISITION_FENCE_STARTING";
        Entry(RewardPowerState state, Path directory) { this.state = state; this.dataDirectory = directory; }
    }
    private CombatPowerRuntime() { }
    static boolean captureReady(MinecraftServer server) {
        Entry entry = ENTRIES.get(server); return entry != null && entry.ready && !entry.stopping;
    }
    @SubscribeEvent public static void reload(AddReloadListenerEvent event) {
        event.addListener(CombatPowerPolicyManager.INSTANCE);
        event.addListener(BlessingPowerPolicyManager.INSTANCE);
    }
    @SubscribeEvent public static void started(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        Entry entry = new Entry(RewardPowerState.get(server), server.getWorldPath(LevelResource.ROOT).resolve("data"));
        if (ENTRIES.putIfAbsent(server, entry) != null) { entry.worker.shutdown(); return; }
        CombatPowerService.INSTANCE.installProvider(CombatPowerRuntime::assess);
        UUID prior = entry.state.cleanSession();
        entry.worker.execute(() -> {
            try {
                boolean clean = RewardPowerDurability.previouslyClean(entry.dataDirectory, prior);
                RewardPowerDurability.write(entry.dataDirectory, entry.session, false);
                server.execute(() -> {
                    if (entry.stopping) return;
                    if (!clean) entry.state.invalidateCoverage("UNCLEAN_OR_UNVERIFIED_RESTART");
                    entry.ready = entry.state.ready(); entry.reason = entry.ready ? "READY" : "ACQUISITION_DATA_REJECTED";
                });
            } catch (Exception failure) {
                server.execute(() -> { entry.reason = "ACQUISITION_FENCE_IO_UNAVAILABLE"; entry.state.invalidateCoverage(entry.reason); });
                MythicTrpg.LOGGER.error("Combat power acquisition fence unavailable; rewards remain enabled", failure);
            }
        });
    }
    @SubscribeEvent(priority = EventPriority.HIGHEST) public static void joining(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        try {
            Entry entry = ENTRIES.get(player.server); PlayerMythDataService profiles = PlayerMythDataService.get(player.server);
            // Existing profile (even empty) is not proof of never having received a reward before this adapter.
            boolean fresh = entry != null && entry.ready && !entry.stopping && profiles.isReady() && profiles.find(player.getUUID()).isEmpty();
            RewardPowerState.get(player.server).beginPlayer(player.getUUID(), fresh);
        } catch (RuntimeException unavailable) { MythicTrpg.LOGGER.warn("Combat power join evidence unavailable", unavailable); }
    }
    /** Called ONLY after RewardExecutionService delivered at least one actual authored item. Failure cannot block the reward. */
    public static void rewardItemDelivered(ServerPlayer player, ItemStack delivered) {
        try {
            RewardPowerState state = RewardPowerState.get(player.server); Entry entry = ENTRIES.get(player.server);
            if (entry != null) entry.mutations++;
            if (entry == null || !entry.ready || entry.stopping) state.gap(player.getUUID(), "ACQUISITION_RUNTIME_NOT_READY");
            state.record(player.getUUID(), RewardPowerState.identity(delivered, player.registryAccess()));
        } catch (RuntimeException failure) { rewardDeliveryUncertain(player); }
    }
    /** Delivery/capture uncertainty never resets a stronger recorded variant or alters claim ownership. */
    public static void rewardDeliveryUncertain(ServerPlayer player) {
        try {
            Entry entry = ENTRIES.get(player.server); if (entry != null) entry.mutations++;
            RewardPowerState.get(player.server).gap(player.getUUID(), "REWARD_DELIVERY_OR_CAPTURE_UNCERTAIN");
        } catch (RuntimeException failure) {
            Entry entry = ENTRIES.get(player.server); if (entry != null) { entry.ready = false; entry.reason = "ACQUISITION_CAPTURE_FAILED"; }
            MythicTrpg.LOGGER.error("Combat power reward evidence failed; reward execution is not rolled back", failure);
        }
    }
    static CombatPowerAssessment assess(MinecraftServer server, ServerPlayer player, Map<ResourceLocation, Integer> progress) {
        if (!server.isSameThread() || player.server != server) return CombatPowerAssessment.unavailable("WRONG_GAME_CONTEXT");
        Entry entry = ENTRIES.get(server);
        if (entry == null || !entry.ready || entry.stopping) return CombatPowerAssessment.unavailable(entry == null ? "ACQUISITION_RUNTIME_ABSENT" : entry.reason);
        if (MythicWorldState.get(server).isRejected() || !PlayerMythDataService.get(server).isReady()) return CombatPowerAssessment.unavailable("GAME_SOURCE_REJECTED");
        return assessInputs(entry.state, CombatPowerPolicyManager.INSTANCE.current(), player, progress);
    }
    static CombatPowerAssessment assessInputs(RewardPowerState state, CombatPowerPolicyManager.Loaded loaded,
            ServerPlayer player, Map<ResourceLocation, Integer> progress) {
        if (!state.ready()) return CombatPowerAssessment.unavailable("ACQUISITION_DATA_REJECTED");
        var history = state.history(player.getUUID()).orElse(null);
        if (history == null || !history.complete()) return CombatPowerAssessment.unavailable(history == null ? "ACQUISITION_HISTORY_MISSING" : history.reason());
        if (loaded.policy() == null) return CombatPowerAssessment.unavailable(loaded.reason());
        try {
            Map<String, Integer> world = new HashMap<>(); progress.forEach((id, value) -> world.put(id.toString(), value));
            var claims = RewardClaimState.get(player.server);
            if (!claims.isWritable()) return CombatPowerAssessment.unavailable("BLESSING_OWNERSHIP_REJECTED");
            Map<ResourceLocation, Integer> active = new HashMap<>();
            player.getActiveEffects().stream().filter(effect -> effect.isInfiniteDuration() || effect.getDuration() > 0)
                    .forEach(effect -> active.merge(ResourceLocation.parse(effect.getEffect().getRegisteredName()), effect.getAmplifier(), Math::max));
            var owned = claims.ownedPermanentBlessingLevels(player.getUUID());
            var scores = BlessingPowerPolicyManager.INSTANCE.current();
            if (!owned.isEmpty() && scores.policy() == null) return CombatPowerAssessment.unavailable(scores.reason());
            CombatPowerPolicy policy = scores.policy() == null ? loaded.policy() : loaded.policy().withEffectDefaults(scores.policy().scores());
            return policy.evaluate(policy.scoreItems(history.items(), player.registryAccess()), world,
                    BlessingPowerPolicy.effectInputs(owned, active), permanentModifiers(player));
        } catch (RuntimeException invalid) {
            return CombatPowerAssessment.unavailable("POWER_INPUT_INVALID:" + (invalid.getMessage() == null ? invalid.getClass().getSimpleName() : invalid.getMessage()));
        }
    }
    /** This component remains inspectable when gear history/world recommendations are unavailable. */
    public record OwnedBlessingPower(boolean available, double score, String evidence) { }
    public static OwnedBlessingPower ownedBlessingPower(ServerPlayer player) {
        if (!player.server.isSameThread()) return new OwnedBlessingPower(false, 0, "WRONG_GAME_THREAD");
        var claims = RewardClaimState.get(player.server);
        if (!claims.isWritable()) return new OwnedBlessingPower(false, 0, "BLESSING_OWNERSHIP_REJECTED");
        var levels = claims.ownedPermanentBlessingLevels(player.getUUID());
        if (levels.isEmpty()) return new OwnedBlessingPower(true, 0, "NO_OWNED_PERMANENT_BLESSINGS");
        var table = BlessingPowerPolicyManager.INSTANCE.current();
        if (table.policy() == null) return new OwnedBlessingPower(false, 0, table.reason());
        var world = CombatPowerPolicyManager.INSTANCE.current().policy();
        try {
            double score = BlessingPowerPolicy.total(BlessingPowerPolicy.effectInputs(levels, Map.of()),
                    table.policy().withOverrides(world == null ? Map.of() : world.effects()));
            return new OwnedBlessingPower(true, score, "owned_effects=" + levels.size() + "; scoreTable=" + table.policy().id()
                    + "; before_world_coefficient; independent_of_milk_or_death; worldOverrides=" + (world != null));
        } catch (RuntimeException invalid) { return new OwnedBlessingPower(false, 0, invalid.getMessage()); }
    }
    static Map<String, Double> permanentModifiers(ServerPlayer player) {
        // Vanilla effects also call addPermanentModifier despite expiring later. Exclude only exact actual effect templates.
        Map<String, Double> effectModifiers = new HashMap<>();
        player.getActiveEffects().forEach(effect -> effect.getEffect().value().createModifiers(effect.getAmplifier(), (attribute, modifier) -> {
            String key = attribute.getRegisteredName() + "#" + modifier.id() + "#" + modifier.operation().name();
            if (!Double.isFinite(modifier.amount()) || effectModifiers.putIfAbsent(key, modifier.amount()) != null)
                throw new IllegalArgumentException("AMBIGUOUS_EFFECT_MODIFIER");
        }));
        // AttributeInstance.save() excludes equipment/transient modifiers. getModifiers()/getValue() do not.
        ListTag attributes = player.getAttributes().save(); Map<String, Double> result = new HashMap<>();
        for (int i = 0; i < attributes.size(); i++) {
            CompoundTag attribute = attributes.getCompound(i); ListTag modifiers = attribute.getList("modifiers", Tag.TAG_COMPOUND);
            for (int j = 0; j < modifiers.size(); j++) {
                AttributeModifier modifier = AttributeModifier.load(modifiers.getCompound(j));
                if (modifier == null || !Double.isFinite(modifier.amount())) throw new IllegalArgumentException("INVALID_PERMANENT_MODIFIER");
                String key = attribute.getString("id") + "#" + modifier.id() + "#" + modifier.operation().name();
                Double effectAmount = effectModifiers.get(key);
                if (effectAmount != null) {
                    if (Double.compare(effectAmount, modifier.amount()) != 0) throw new IllegalArgumentException("EFFECT_MODIFIER_SOURCE_COLLISION");
                    continue;
                }
                if (result.putIfAbsent(key, modifier.amount()) != null) throw new IllegalArgumentException("DUPLICATE_PERMANENT_MODIFIER");
            }
        }
        return Map.copyOf(result);
    }
    @SubscribeEvent public static void stopping(ServerStoppingEvent event) {
        Entry entry = ENTRIES.get(event.getServer()); if (entry != null) { entry.stopping = true; entry.reason = "ACQUISITION_STOPPING"; }
    }
    @SubscribeEvent(priority = EventPriority.LOWEST) public static void stopped(ServerStoppedEvent event) {
        MinecraftServer server = event.getServer(); Entry entry = ENTRIES.get(server); if (entry == null) return;
        boolean normalStop = entry.stopping; entry.stopping = true; entry.worker.shutdown();
        // ServerStopped also fires from the crash finally block; only a prior normal stopping event may certify coverage.
        if (normalStop && entry.ready && entry.state.ready()) {
            entry.state.cleanSession(entry.session); long revision = entry.mutations;
            Path file = entry.dataDirectory.resolve(RewardPowerState.FILE_NAME + ".dat");
            CompletableFuture<Void> completed = new CompletableFuture<>();
            try {
                entry.state.save(file.toFile(), server.registryAccess());
                IOUtilities.withIOWorker(() -> {
                    try {
                        CompoundTag saved = NbtIo.readCompressed(file, NbtAccounter.create(64L * 1024 * 1024)).getCompound("data");
                        if (!saved.hasUUID("cleanSession") || !saved.getUUID("cleanSession").equals(entry.session) || entry.mutations != revision)
                            throw new IllegalStateException("Power evidence save was not confirmed");
                        RewardPowerDurability.write(entry.dataDirectory, entry.session, true); completed.complete(null);
                    } catch (Exception failure) { completed.completeExceptionally(failure); }
                });
                completed.get(10, TimeUnit.SECONDS); // Shutdown only, after live game ticks have stopped.
            } catch (Exception failure) { MythicTrpg.LOGGER.error("Combat power clean save unconfirmed; next startup will invalidate coverage", failure); }
        }
        ENTRIES.remove(server);
    }
    @SubscribeEvent public static void commands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("mythpower")
                .executes(context -> report(context.getSource(), context.getSource().getPlayerOrException()))
                .then(Commands.argument("player", EntityArgument.player()).requires(source -> source.hasPermission(2))
                        .executes(context -> report(context.getSource(), EntityArgument.getPlayer(context, "player")))));
    }
    private static int report(net.minecraft.commands.CommandSourceStack source, ServerPlayer player) {
        CombatPowerAssessment assessment = CombatPowerService.INSTANCE.assess(player);
        source.sendSuccess(() -> Component.literal("[MythicTRPG] " + player.getScoreboardName() + " power=" + assessment.status()
                + ", actual=" + assessment.actualPower() + ", recommended=" + assessment.recommendedPower()
                + ", catchUp=" + assessment.catchUpRewardTierBonus() + ", " + assessment.evidence()), false);
        var blessing = ownedBlessingPower(player);
        source.sendSuccess(() -> Component.literal("[MythicTRPG] 영구 가호 전투력="
                + (blessing.available() ? Double.toString(blessing.score()) : "UNAVAILABLE") + "; " + blessing.evidence()), false);
        return assessment.status() == CombatPowerAssessment.Status.AVAILABLE ? 1 : 0;
    }
}

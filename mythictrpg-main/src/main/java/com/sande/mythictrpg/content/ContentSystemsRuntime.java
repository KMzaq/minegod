package com.sande.mythictrpg.content;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.condition.api.ConditionResult;
import com.sande.mythictrpg.condition.engine.*;
import com.sande.mythictrpg.story.api.StoryStateView.*;
import com.sande.mythictrpg.story.definition.StoryDefinitionManager;
import com.sande.mythictrpg.story.runtime.StoryEventService;
import com.sande.mythictrpg.story.state.StoryRuntimeState;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.network.chat.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.*;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import java.util.*;

@EventBusSubscriber(modid = MythicTrpg.MOD_ID)
public final class ContentSystemsRuntime {
    private static final Map<MinecraftServer, Map<UUID, Map<ResourceLocation, Long>>> ATTEMPTS = new IdentityHashMap<>();
    private ContentSystemsRuntime() { }
    @SubscribeEvent public static void reload(AddReloadListenerEvent event) { event.addListener(ContentDefinitions.INSTANCE); }
    @SubscribeEvent public static void stopped(ServerStoppedEvent event) { ATTEMPTS.remove(event.getServer()); }
    @SubscribeEvent public static void commands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("mythvisits")
                .then(Commands.literal("on").executes(c -> visitPreference(c.getSource().getPlayerOrException(), true)))
                .then(Commands.literal("off").executes(c -> visitPreference(c.getSource().getPlayerOrException(), false))));
        event.getDispatcher().register(Commands.literal("mythinvestigate")
                .then(Commands.argument("site", ResourceLocationArgument.id()).executes(c -> {
                    var player = c.getSource().getPlayerOrException();
                    boolean accepted = investigate(player, ResourceLocationArgument.getId(c, "site"));
                    if (!accepted) c.getSource().sendFailure(Component.literal("지금 이곳에서 조사할 수 없거나 다음 기회를 기다려야 합니다."));
                    return accepted ? 1 : 0;
                })));
        event.getDispatcher().register(Commands.literal("mythclues")
                .executes(c -> journal(c.getSource().getPlayerOrException(), 0))
                .then(Commands.literal("page").then(Commands.argument("page", IntegerArgumentType.integer(1, 10000))
                        .executes(c -> journal(c.getSource().getPlayerOrException(), IntegerArgumentType.getInteger(c, "page") - 1))))
                .then(Commands.literal("read").then(Commands.argument("fact", ResourceLocationArgument.id())
                        .executes(c -> read(c.getSource().getPlayerOrException(), ResourceLocationArgument.getId(c, "fact"))))));
    }
    private static int visitPreference(ServerPlayer player, boolean allow) {
        if (!HomeVisitPreferences.get(player.server).setAllowed(player.getUUID(), allow)) return 0;
        player.sendSystemMessage(Component.literal(allow ? "건축물 방문을 허용했습니다." : "건축물 방문을 받지 않습니다. 이동 중인 방문도 중단됩니다."));
        return 1;
    }
    @SubscribeEvent(priority = EventPriority.LOWEST) public static void investigateBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || event.getHand() != InteractionHand.MAIN_HAND || !player.isShiftKeyDown()) return;
        ContentDefinitions.INSTANCE.snapshot().investigations().values().stream()
                .filter(site -> site.dimension().equals(player.level().dimension().location()) && site.position().equals(event.getPos()))
                .sorted(Comparator.comparing(ContentDefinitions.Investigation::id)).findFirst().ifPresent(site -> {
                    if (investigate(player, site.id())) {
                        // Treat an accepted investigation as the interaction itself. Otherwise the
                        // same click can place/use the held item after awarding the authored result.
                        event.setCancellationResult(InteractionResult.SUCCESS);
                        event.setCanceled(true);
                    }
                });
    }
    public static boolean investigate(ServerPlayer player, ResourceLocation siteId) {
        if (!player.server.isSameThread()) throw new IllegalStateException("Investigation requires server thread");
        if (!player.isAlive() || player.isSpectator() || player.server.getPlayerList().getPlayer(player.getUUID()) != player
                || com.sande.mythictrpg.ai.server.ConversationRooms.INSTANCE.memberships(player).stream().anyMatch(room -> room.recordingScope().isTest())) return false;
        var site = ContentDefinitions.INSTANCE.snapshot().investigations().get(siteId);
        if (site == null || !site.dimension().equals(player.level().dimension().location())
                || !player.serverLevel().hasChunkAt(site.position()) || !player.canInteractWithBlock(site.position(), 0)
                || player.distanceToSqr(site.position().getCenter()) > site.radius() * site.radius()
                || !net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(player.level().getBlockState(site.position()).getBlock()).equals(site.block())) return false;
        // A short diagonal ray can touch a third chunk even when both endpoint chunks are loaded.
        int minX = Math.min(player.blockPosition().getX(), site.position().getX()) >> 4;
        int maxX = Math.max(player.blockPosition().getX(), site.position().getX()) >> 4;
        int minZ = Math.min(player.blockPosition().getZ(), site.position().getZ()) >> 4;
        int maxZ = Math.max(player.blockPosition().getZ(), site.position().getZ()) >> 4;
        for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++)
            if (!player.serverLevel().hasChunkAt(new net.minecraft.core.BlockPos(x << 4, site.position().getY(), z << 4))) return false;
        var sight = player.level().clip(new net.minecraft.world.level.ClipContext(player.getEyePosition(), site.position().getCenter(),
                net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, player));
        if (!sight.getBlockPos().equals(site.position())) return false;
        if (site.heldItem().isPresent()) {
            try {
                var expected = new com.sande.mythictrpg.quest.reward.NpcRewardEntry(site.heldItem().get(), 1, site.components()).createStack(player.registryAccess());
                if (!net.minecraft.world.item.ItemStack.isSameItemSameComponents(player.getMainHandItem(), expected)
                        || player.getMainHandItem().getCount() < site.heldCount()) return false;
            } catch (RuntimeException invalid) { return false; }
        }
        if (ConditionEngine.INSTANCE.evaluate(site.condition(), ConditionContexts.forStory(player.server,
                Optional.of(player.getUUID()), StoryScopeKey.player(player.getUUID()))) != ConditionResult.MATCH) return false;
        var byPlayer = ATTEMPTS.computeIfAbsent(player.server, ignored -> new HashMap<>());
        long now = player.server.overworld().getGameTime();
        byPlayer.entrySet().removeIf(entry -> entry.getValue().values().stream().allMatch(until -> until <= now));
        if (!byPlayer.containsKey(player.getUUID()) && byPlayer.size() >= 4096) return false;
        var attempts = byPlayer.computeIfAbsent(player.getUUID(), ignored -> new HashMap<>());
        attempts.entrySet().removeIf(entry -> entry.getValue() <= now);
        if (attempts.getOrDefault(siteId, 0L) > now || attempts.size() >= 256) return false;
        attempts.put(siteId, now + site.cooldownTicks());
        var result = StoryEventService.INSTANCE.triggerRegistered(player.server, site.event(), Optional.of(player));
        return result.status() == StoryEventService.Status.STARTED;
    }
    private static List<ResourceLocation> known(ServerPlayer player) {
        var state = StoryRuntimeState.get(player.server);
        if (!state.isReady()) return List.of();
        return StoryDefinitionManager.INSTANCE.snapshot().facts().keySet().stream()
                .filter(id -> state.knowledgeLevel(StoryKnowledgeHolder.player(player.getUUID()), id) > 0).sorted().toList();
    }
    public static int journal(ServerPlayer player, int page) {
        var known = known(player); int pages = Math.max(1, (known.size() + 9) / 10);
        if (page < 0 || page >= pages) return 0;
        player.sendSystemMessage(Component.literal("[내 단서] " + (page + 1) + "/" + pages));
        known.stream().skip(page * 10L).limit(10).forEach(id -> {
            var fact = StoryDefinitionManager.INSTANCE.fact(id).orElseThrow();
            player.sendSystemMessage(Component.literal("• ").append(Component.translatable(fact.levels().getFirst().canonicalTranslationKey()))
                    .withStyle(style -> style.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/mythclues read " + id))));
        });
        return 1;
    }
    public static int read(ServerPlayer player, ResourceLocation id) {
        var state = StoryRuntimeState.get(player.server);
        var fact = StoryDefinitionManager.INSTANCE.fact(id).orElse(null);
        if (!state.isReady() || fact == null) return 0;
        int level = state.knowledgeLevel(StoryKnowledgeHolder.player(player.getUUID()), id);
        if (level <= 0) return 0;
        fact.levels().stream().filter(value -> value.level() <= level)
                .forEach(value -> player.sendSystemMessage(Component.translatable(value.canonicalTranslationKey())));
        return 1;
    }
}

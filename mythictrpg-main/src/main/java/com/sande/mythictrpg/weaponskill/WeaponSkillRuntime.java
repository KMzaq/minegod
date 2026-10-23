package com.sande.mythictrpg.weaponskill;

import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.power.CombatPowerRuntime;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import java.util.*;

/** Server-owned authority and execution. A client supplies only its expected held slot and skill ID. */
@EventBusSubscriber(modid = MythicTrpg.MOD_ID)
public final class WeaponSkillRuntime {
    public enum Result { SUCCESS, NOT_BOUND, INVALID_PLAYER, STALE_REQUEST, NO_MANA, COOLDOWN, DUPLICATE, UNAVAILABLE, NO_EFFECT }
    private record Sent(int slot, ItemStack item, long tick, boolean actionLocked) { }
    private static final Map<MinecraftServer,Map<UUID,Sent>> SENT = new IdentityHashMap<>();
    private static final Map<MinecraftServer,Map<UUID,Long>> REQUESTS = new IdentityHashMap<>();
    private static final Map<MinecraftServer,Map<UUID,Long>> FEEDBACK = new IdentityHashMap<>();
    private WeaponSkillRuntime() { }

    /** Shared presentation of existing server locks; this never grants combat permission. */
    public static boolean actionLocked(ServerPlayer player) {
        return com.sande.mythictrpg.skills.CombatCastRuntime.locked(player)
                || com.sande.mythictrpg.control.CrowdControlService.isStunned(player);
    }

    public static Optional<WeaponSkillDefinition> heldSkill(ServerPlayer player) {
        return CombatPowerRuntime.registeredAcquiredGear(player,player.getMainHandItem())
                .filter(rule -> rule.slot().equals("weapon") && rule.rightClickSkill() != null)
                .flatMap(rule -> WeaponSkillDefinitions.INSTANCE.find(rule.rightClickSkill()));
    }
    public static void receive(ServerPlayer player, int slot, ResourceLocation expectedSkill) {
        if (!player.server.isSameThread() || player.server.getPlayerList().getPlayer(player.getUUID()) != player) return;
        long tick = player.server.overworld().getGameTime();
        var requests = REQUESTS.computeIfAbsent(player.server,ignored -> new HashMap<>());
        Long previous = requests.put(player.getUUID(),tick);
        if (previous != null && previous == tick) return;
        Result result = cast(player,slot,expectedSkill);
        if (result == Result.NO_EFFECT) {
            var feedback = FEEDBACK.computeIfAbsent(player.server,ignored -> new HashMap<>());
            if (tick - feedback.getOrDefault(player.getUUID(),-100L) >= 20) {
                player.sendSystemMessage(Component.literal("스킬을 적용할 대상이나 효과가 없습니다."));
                feedback.put(player.getUUID(),tick);
            }
        }
        sync(player);
    }
    public static Result cast(ServerPlayer player, int slot, ResourceLocation expectedSkill) {
        if (!com.sande.mythictrpg.skills.SkillExecutor.availablePlayer(player) || player.isShiftKeyDown()) return Result.INVALID_PLAYER;
        if (slot < 0 || slot > 8 || player.getInventory().selected != slot) return Result.STALE_REQUEST;
        var skill=heldSkill(player).orElse(null);
        if(skill==null)return Result.NOT_BOUND;
        if(!skill.id().equals(expectedSkill))return Result.STALE_REQUEST;
        if(PrototypeInteractionPriority.prototypeSkill(skill.id())&&PrototypeInteractionPriority.directlyTargeted(player))return Result.UNAVAILABLE;
        return com.sande.mythictrpg.skills.SkillExecutor.cast(player,com.sande.mythictrpg.skills.SkillCastEvents.Source.WEAPON,
                skill.id(),skill.name(),skill.manaCost(),skill.cooldownTicks(),skill.action());
    }
    @SubscribeEvent public static void tick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        long tick = player.server.overworld().getGameTime();
        if (player.isAlive() && !player.isSpectator()) WeaponSkillState.get(player.server).regenerate(player.getUUID(),tick,
                com.sande.mythictrpg.skills.SkillMana.maximumMana(player));
        else WeaponSkillState.get(player.server).clamp(player.getUUID(),com.sande.mythictrpg.skills.SkillMana.maximumMana(player));
        var previous = SENT.computeIfAbsent(player.server,ignored -> new HashMap<>()).get(player.getUUID());
        if (previous == null || previous.slot()!=player.getInventory().selected || previous.actionLocked()!=actionLocked(player)
                || !ItemStack.isSameItemSameComponents(previous.item(),player.getMainHandItem()) || tick-previous.tick()>=10)
            sync(player);
    }
    static void sync(ServerPlayer player) {
        var state = WeaponSkillState.get(player.server); var account = state.account(player.getUUID());
        long tick = player.server.overworld().getGameTime();
        var skill = account.isPresent() ? heldSkill(player).orElse(null) : null;
        WeaponSkillNetwork.send(player,com.sande.mythictrpg.skills.SkillMana.mana(player),com.sande.mythictrpg.skills.SkillMana.maximumMana(player),player.getInventory().selected,
                player.getMainHandItem(),skill == null ? null : skill.id(),skill == null ? "" : skill.name(),
                skill == null ? 0 : skill.manaCost(),skill == null ? 0 : (int)Math.min(Integer.MAX_VALUE,account.orElseThrow().remainingTicks(skill.id().toString(),tick)));
        SENT.computeIfAbsent(player.server,ignored -> new HashMap<>()).put(player.getUUID(),
                new Sent(player.getInventory().selected,player.getMainHandItem().copy(),tick,actionLocked(player)));
    }
    @SubscribeEvent public static void left(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            var values=SENT.get(player.server); if (values!=null) values.remove(player.getUUID());
            var requests=REQUESTS.get(player.server); if (requests!=null) requests.remove(player.getUUID());
            var feedback=FEEDBACK.get(player.server); if (feedback!=null) feedback.remove(player.getUUID());
        }
    }
    @SubscribeEvent public static void stopped(ServerStoppedEvent event) {
        SENT.remove(event.getServer()); REQUESTS.remove(event.getServer()); FEEDBACK.remove(event.getServer());
    }
    @SubscribeEvent public static void commands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("mythmana").executes(context -> {
            var player=context.getSource().getPlayerOrException(); var account=WeaponSkillState.get(player.server).account(player.getUUID());
            context.getSource().sendSuccess(() -> Component.literal(account.isEmpty() ? "마나 저장 자료를 확인할 수 없습니다."
                    : String.format(java.util.Locale.ROOT,"마나 %.1f / %.1f · 초당 최대 마나의 %s%% 자동 회복",com.sande.mythictrpg.skills.SkillMana.mana(player),com.sande.mythictrpg.skills.SkillMana.maximumMana(player),
                            java.math.BigDecimal.valueOf(com.sande.mythictrpg.skills.ManaTuning.REGEN_PERCENT_PER_SECOND).stripTrailingZeros().toPlainString())),false);
            return account.isPresent()?1:0;
        }));
    }
}

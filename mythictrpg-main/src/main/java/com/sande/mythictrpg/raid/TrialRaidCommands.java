package com.sande.mythictrpg.raid;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.sande.mythictrpg.MythicTrpg;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.ArrayList;
import java.util.List;

/** Opt-in trial helpers. Preparation and equipment grants never run on boot or raid creation. */
@EventBusSubscriber(modid = MythicTrpg.MOD_ID)
public final class TrialRaidCommands {
    public static final ResourceLocation TRIAL_RAID_ID =
            ResourceLocation.fromNamespaceAndPath("mythictrpg", "trial/sealed_guardian");

    private TrialRaidCommands() { }

    @SubscribeEvent public static void register(RegisterCommandsEvent event) {
        // Brigadier merges this child with the existing RaidCommands root in either registration order.
        event.getDispatcher().register(Commands.literal("mythraid")
                .then(Commands.literal("trial")
                        .executes(context -> help(context.getSource()))
                        .then(Commands.literal("prepare").requires(source -> source.hasPermission(2))
                                .executes(context -> prepare(context.getSource())))
                        .then(Commands.literal("create")
                                .executes(context -> create(context.getSource())))
                        .then(Commands.literal("kit")
                                .requires(source -> source.hasPermission(2) && source.getEntity() instanceof ServerPlayer)
                                .executes(context -> kit(context.getSource())))));
    }

    private static int help(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal("[시험 레이드] 비정사 프로토타입: 봉인된 수호자, 1~4명, 1인당 3목숨, 전투 10분."), false);
        source.sendSuccess(() -> Component.literal("[시험 레이드] 관리자 준비: /mythraid trial prepare. 테스트 장비가 필요하면 빈 인벤토리 9칸을 확보하고 /mythraid trial kit."), false);
        source.sendSuccess(() -> Component.literal("[시험 레이드] 모집 생성: /mythraid trial create. 참가·시작은 /mythraid 화면 또는 /mythraid join <UUID>, /mythraid start <UUID>."), false);
        return 1;
    }

    private static int prepare(CommandSourceStack source) {
        MinecraftServer server = source.getServer();
        RaidState state = RaidState.get(server);
        if (!state.ready()) return fail(source, "레이드 저장 상태를 읽을 수 없어 전장 준비를 중단했습니다.");
        for (RaidState.Attempt attempt : state.attempts()) {
            if (attempt.terminal() || attempt.status() == RaidState.Status.FORMING) continue;
            boolean trialAttempt = attempt.definition().id().equals(TRIAL_RAID_ID);
            boolean trialArena = attempt.arena().map(arena ->
                    arena.dimension().equals(TrialRaidArenaService.DIMENSION_ID)).orElse(false);
            if (trialAttempt || trialArena) {
                return fail(source, "시험 전장을 사용하는 대기·전투·정리 시도가 남아 있습니다. 종료 상태를 확인한 뒤 준비하세요.");
            }
        }
        TrialRaidArenaService.Result result = TrialRaidArenaService.prepare(server);
        if (!result.success()) return fail(source, result.message());
        source.sendSuccess(() -> Component.literal("[시험 레이드] " + result.message()), true);
        return 1;
    }

    private static int create(CommandSourceStack source) throws CommandSyntaxException {
        RaidRuntime.Result result = RaidRuntime.INSTANCE.create(source.getPlayerOrException(), TRIAL_RAID_ID);
        if (!result.succeeded()) return fail(source, result.message());
        source.sendSuccess(() -> Component.literal("[시험 레이드] " + result.message()
                + result.attemptId().map(id -> " UUID=" + id).orElse("")), false);
        source.sendSuccess(() -> Component.literal("[시험 레이드] /mythraid 화면에서 참가자를 확인한 뒤 리더가 시작하세요."
                + result.attemptId().map(id -> " 명령: /mythraid start " + id).orElse("")), false);
        return 1;
    }

    private static int kit(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        Inventory inventory = player.getInventory();
        List<ItemStack> stacks = List.of(new ItemStack(Items.IRON_SWORD),
                new ItemStack(Items.IRON_HELMET), new ItemStack(Items.IRON_CHESTPLATE),
                new ItemStack(Items.IRON_LEGGINGS), new ItemStack(Items.IRON_BOOTS),
                new ItemStack(Items.SHIELD), new ItemStack(Items.COOKED_BEEF, 32),
                new ItemStack(Items.BOW), new ItemStack(Items.ARROW, 32));
        List<Integer> emptySlots = new ArrayList<>(stacks.size());
        for (int slot = 0; slot < Inventory.INVENTORY_SIZE && emptySlots.size() < stacks.size(); slot++) {
            if (inventory.getItem(slot).isEmpty()) emptySlots.add(slot);
        }
        if (emptySlots.size() != stacks.size()) {
            return fail(source, "기본 인벤토리에 빈칸 9개가 필요합니다. 아이템을 지급하지 않았습니다.");
        }
        // One server-thread transaction after full preflight. No replacing, auto-equipping or world drops.
        for (int index = 0; index < stacks.size(); index++) inventory.setItem(emptySlots.get(index), stacks.get(index));
        inventory.setChanged();
        player.inventoryMenu.broadcastChanges();
        if (player.containerMenu != player.inventoryMenu) player.containerMenu.broadcastChanges();
        source.sendSuccess(() -> Component.literal("[시험 레이드] 본인에게 철 검·철 방어구 4종·방패·스테이크 32개·활·화살 32개를 지급했습니다. 방어구와 방패는 직접 착용하세요."), true);
        return 1;
    }

    private static int fail(CommandSourceStack source, String message) {
        source.sendFailure(Component.literal("[시험 레이드] " + message));
        return 0;
    }
}

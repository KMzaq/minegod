package com.sande.mythictrpg.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.sande.mythictrpg.economy.CurrencyService;
import com.sande.mythictrpg.economy.CurrencyState;
import com.sande.mythictrpg.shop.ShopCatalogManager;
import com.sande.mythictrpg.shop.ShopProductKey;
import com.sande.mythictrpg.shop.ShopService;
import com.sande.mythictrpg.shop.ShopType;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

public final class EconomyCommands {
    private EconomyCommands() {
    }

    public static void onRegisterCommands(RegisterCommandsEvent event) {
        register(event.getDispatcher());
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("money").executes(EconomyCommands::showOwnBalance));
        dispatcher.register(Commands.literal("shop")
                .then(Commands.literal("buy").executes(context -> open(context, ShopType.BUY)))
                .then(Commands.literal("sell").executes(context -> open(context, ShopType.SELL))));
        dispatcher.register(Commands.literal("mythadmin").requires(source -> source.hasPermission(2))
                .then(Commands.literal("economy")
                        .then(Commands.literal("balance")
                                .then(Commands.literal("get")
                                        .then(Commands.argument("player", EntityArgument.player())
                                                .executes(EconomyCommands::getBalance)))
                                .then(Commands.literal("set")
                                        .then(Commands.argument("player", EntityArgument.player())
                                                .then(Commands.argument("amount", LongArgumentType.longArg(0L,
                                                                CurrencyState.MAX_BALANCE))
                                                        .executes(context -> mutateBalance(context, "set")))))
                                .then(Commands.literal("add")
                                        .then(Commands.argument("player", EntityArgument.player())
                                                .then(Commands.argument("amount", LongArgumentType.longArg(1L,
                                                                CurrencyState.MAX_BALANCE))
                                                        .executes(context -> mutateBalance(context, "add")))))
                                .then(Commands.literal("remove")
                                        .then(Commands.argument("player", EntityArgument.player())
                                                .then(Commands.argument("amount", LongArgumentType.longArg(1L,
                                                                CurrencyState.MAX_BALANCE))
                                                        .executes(context -> mutateBalance(context, "remove"))))))
                        .then(Commands.literal("shop")
                                .then(Commands.literal("unlock").then(shopProductArguments(true)))
                                .then(Commands.literal("lock").then(shopProductArguments(false)))
                                .then(Commands.literal("list").executes(EconomyCommands::listShops)))));
    }

    private static com.mojang.brigadier.builder.ArgumentBuilder<CommandSourceStack, ?> shopProductArguments(
            boolean unlock) {
        return Commands.argument("shop", ResourceLocationArgument.id())
                .then(Commands.argument("product", ResourceLocationArgument.id())
                        .executes(context -> mutateProduct(context, unlock)));
    }

    private static int showOwnBalance(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        long balance = CurrencyService.INSTANCE.balance(player.server, player.getUUID());
        player.sendSystemMessage(Component.literal("보유 골드: " + balance).withStyle(ChatFormatting.GOLD));
        return 1;
    }

    private static int open(CommandContext<CommandSourceStack> context, ShopType type) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        ShopService.INSTANCE.open(player, type);
        return 1;
    }

    private static int getBalance(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = EntityArgument.getPlayer(context, "player");
        long balance = CurrencyService.INSTANCE.balance(player.server, player.getUUID());
        context.getSource().sendSuccess(() -> Component.literal(player.getGameProfile().getName()
                + "의 보유 골드: " + balance), false);
        return 1;
    }

    private static int mutateBalance(CommandContext<CommandSourceStack> context, String operation) throws CommandSyntaxException {
        ServerPlayer player = EntityArgument.getPlayer(context, "player");
        long amount = LongArgumentType.getLong(context, "amount");
        boolean changed = switch (operation) {
            case "set" -> CurrencyService.INSTANCE.set(player.server, player.getUUID(), amount);
            case "add" -> CurrencyService.INSTANCE.credit(player.server, player.getUUID(), amount);
            case "remove" -> CurrencyService.INSTANCE.debit(player.server, player.getUUID(), amount);
            default -> false;
        };
        if (!changed) {
            context.getSource().sendFailure(Component.literal("잔액 변경이 허용 범위를 벗어났습니다."));
            return 0;
        }
        long balance = CurrencyService.INSTANCE.balance(player.server, player.getUUID());
        context.getSource().sendSuccess(() -> Component.literal(player.getGameProfile().getName()
                + "의 보유 골드를 " + balance + "로 변경했습니다."), true);
        return 1;
    }

    private static int mutateProduct(CommandContext<CommandSourceStack> context, boolean unlock) {
        ResourceLocation shop = ResourceLocationArgument.getId(context, "shop");
        ResourceLocation product = ResourceLocationArgument.getId(context, "product");
        ShopProductKey key = new ShopProductKey(shop, product);
        if (ShopCatalogManager.INSTANCE.product(key).isEmpty()) {
            context.getSource().sendFailure(Component.literal("등록되지 않은 상점 상품입니다."));
            return 0;
        }
        boolean changed = unlock ? ShopService.INSTANCE.unlock(context.getSource().getServer(), key)
                : ShopService.INSTANCE.lock(context.getSource().getServer(), key);
        if (!changed) {
            context.getSource().sendFailure(Component.literal(unlock
                    ? "이미 해금된 상품입니다." : "기본 상품이거나 이미 잠긴 상품입니다."));
            return 0;
        }
        context.getSource().sendSuccess(() -> Component.literal((unlock ? "상품 해금: " : "상품 잠금: ")
                + shop + " / " + product), true);
        return 1;
    }

    private static int listShops(CommandContext<CommandSourceStack> context) {
        context.getSource().sendSuccess(() -> Component.literal("상점: "
                + String.join(", ", ShopCatalogManager.INSTANCE.ids().stream().map(ResourceLocation::toString).sorted().toList())),
                false);
        return ShopCatalogManager.INSTANCE.ids().size();
    }
}

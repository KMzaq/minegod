package com.sande.mythictrpg.prototype;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/** The root prototype command attaches this branch alongside arena/testing operations. */
public final class PrototypeGrantCommands {
    private PrototypeGrantCommands() { }

    public static LiteralArgumentBuilder<CommandSourceStack> grantBuilder() {
        return Commands.literal("grant").requires(source -> source.hasPermission(2))
                .then(Commands.literal("kit").executes(context -> report(context.getSource(), PrototypeGrantService.grantKit(context.getSource()))))
                .then(Commands.argument("prototype", StringArgumentType.word())
                        .suggests((context, builder) -> {
                            PrototypeGrantService.grantableIds().forEach(id -> builder.suggest(id.toString()));
                            return builder.buildFuture();
                        })
                        .executes(context -> {
                            String value = StringArgumentType.getString(context, "prototype");
                            ResourceLocation id = ResourceLocation.tryParse(value.contains(":") ? value : "proto_cbt:" + value);
                            if (id == null) { context.getSource().sendFailure(Component.literal("올바른 시제품 ID가 아닙니다.")); return 0; }
                            return report(context.getSource(), PrototypeGrantService.grant(context.getSource(), id));
                        }));
    }

    private static int report(CommandSourceStack source, PrototypeGrantService.Result result) {
        if (result.succeeded()) source.sendSuccess(() -> Component.literal("[시제품] " + result.message()), false);
        else source.sendFailure(Component.literal("[시제품] " + result.message()));
        return result.succeeded() ? 1 : 0;
    }
}

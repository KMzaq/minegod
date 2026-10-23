package com.sande.mythictrpg.equipment;

import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.prototype.PrototypeGrantService;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/** Operator authoring checks. Ordinary quest/raid grants keep their normal provider and rank validation. */
@EventBusSubscriber(modid=MythicTrpg.MOD_ID)
public final class EquipmentCommands {
    private EquipmentCommands() { }
    @SubscribeEvent public static void commands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("mythequipment").requires(source -> source.hasPermission(2))
                .then(Commands.literal("list").executes(context -> {
                    for (var id : PrototypeGrantService.equipmentIds())
                        context.getSource().sendSuccess(() -> Component.literal(id.toString()), false);
                    return 1;
                }))
                .then(Commands.literal("grant")
                    .then(Commands.literal("swords").executes(context -> report(context.getSource(),
                            PrototypeGrantService.grantEquipmentExamples(context.getSource(), "swords_demo"))))
                    .then(Commands.literal("examples").executes(context -> report(context.getSource(),
                            PrototypeGrantService.grantEquipmentExamples(context.getSource()))))
                    .then(Commands.argument("definitionId", ResourceLocationArgument.id())
                        .suggests((context,builder) -> {
                            for (var id : PrototypeGrantService.equipmentIds()) builder.suggest(id.toString());
                            return builder.buildFuture();
                        }).executes(context -> {
                            var id = ResourceLocationArgument.getId(context,"definitionId");
                            return report(context.getSource(), PrototypeGrantService.grantEquipment(context.getSource(), id));
                        }))));
    }
    private static int report(net.minecraft.commands.CommandSourceStack source, PrototypeGrantService.Result result) {
        if (result.succeeded()) source.sendSuccess(() -> Component.literal(result.message()), false);
        else source.sendFailure(Component.literal(result.message()));
        return result.succeeded() ? 1 : 0;
    }
}

package com.sande.mythictrpg.client.specialskill;

import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.specialskill.*;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.*;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

public final class SupportAvatarRenderer extends HumanoidMobRenderer<SupportAvatarEntity,PlayerModel<SupportAvatarEntity>> {
    private final PlayerModel<SupportAvatarEntity> classic,slim;
    public SupportAvatarRenderer(EntityRendererProvider.Context context) {
        super(context,new PlayerModel<>(context.bakeLayer(ModelLayers.PLAYER),false),.4F);
        classic=model;slim=new PlayerModel<>(context.bakeLayer(ModelLayers.PLAYER_SLIM),true);
    }
    @Override public void render(SupportAvatarEntity entity,float yaw,float partial,PoseStack pose,MultiBufferSource buffers,int light) {
        model=entity.slimModel()?slim:classic;model.setAllVisible(true);super.render(entity,yaw,partial,pose,buffers,light);
    }
    @Override public ResourceLocation getTextureLocation(SupportAvatarEntity entity) {
        int variant=entity.textureVariant();
        if(variant>0) {
            var resource=ResourceLocation.fromNamespaceAndPath(MythicTrpg.MOD_ID,"textures/entity/god_avatar/variant_"+variant+".png");
            if(Minecraft.getInstance().getResourceManager().getResource(resource).isPresent())return resource;
        }
        return ResourceLocation.withDefaultNamespace(entity.slimModel()?"textures/entity/player/slim/alex.png":"textures/entity/player/wide/steve.png");
    }
    @EventBusSubscriber(modid=MythicTrpg.MOD_ID,value=Dist.CLIENT,bus=EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent public static void register(EntityRenderersEvent.RegisterRenderers event) { event.registerEntityRenderer(SpecialSupportEntities.SUPPORT_AVATAR.get(),SupportAvatarRenderer::new); }
    }
}

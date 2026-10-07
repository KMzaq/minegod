package com.sande.mythictrpg.client.godavatar;

import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.godavatar.GodAvatarEntity;
import com.sande.mythictrpg.godavatar.GodAvatarEntities;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidArmorModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import java.util.concurrent.ConcurrentHashMap;

/** Player skin layout, without player identity/account skins or client-visible God IDs. */
public final class GodAvatarRenderer extends HumanoidMobRenderer<GodAvatarEntity, PlayerModel<GodAvatarEntity>> {
    private static final ResourceLocation CLASSIC_DEFAULT = ResourceLocation.withDefaultNamespace("textures/entity/player/wide/steve.png");
    private static final ResourceLocation SLIM_DEFAULT = ResourceLocation.withDefaultNamespace("textures/entity/player/slim/alex.png");
    private static final ResourceLocation[] VARIANTS = variants();
    private static volatile TextureCache textureCache;
    private final PlayerModel<GodAvatarEntity> classicModel;
    private final PlayerModel<GodAvatarEntity> slimModel;

    public GodAvatarRenderer(EntityRendererProvider.Context context) {
        super(context, new GodAvatarActivityModel(context.bakeLayer(ModelLayers.PLAYER), false), 0.5F);
        classicModel = this.model;
        slimModel = new GodAvatarActivityModel(context.bakeLayer(ModelLayers.PLAYER_SLIM), true);
        layers.removeIf(layer -> layer instanceof ItemInHandLayer<?, ?>);
        addLayer(new ItemInHandLayer<>(this, context.getItemInHandRenderer()) {
            @Override public void render(PoseStack pose, MultiBufferSource buffers, int light, GodAvatarEntity entity,
                    float walk, float strength, float partial, float age, float yaw, float pitch) {
                var prop = entity.activityProp();
                if (prop.isEmpty()) { super.render(pose, buffers, light, entity, walk, strength, partial, age, yaw, pitch); return; }
                // Pure render input: neither real inventory nor equipment is replaced even temporarily.
                renderArmWithItem(entity, prop, ItemDisplayContext.THIRD_PERSON_RIGHT_HAND, HumanoidArm.RIGHT, pose, buffers, light);
            }
        });
        // HumanoidMobRenderer already supplies head items, elytra and held-item layers.
        // Vanilla player armor uses the same 4-wide armor meshes for classic and slim bodies.
        addLayer(new HumanoidArmorLayer<>(this,
                new HumanoidArmorModel<>(context.bakeLayer(ModelLayers.PLAYER_INNER_ARMOR)),
                new HumanoidArmorModel<>(context.bakeLayer(ModelLayers.PLAYER_OUTER_ARMOR)),
                context.getModelManager()));
    }

    @Override public void render(GodAvatarEntity entity, float yaw, float partialTick,
            PoseStack poseStack, MultiBufferSource buffers, int light) {
        this.model = entity.slimModel() ? slimModel : classicModel;
        // NPCs have no player skin-part option bits: include the complete authored PNG overlay.
        this.model.setAllVisible(true);
        this.model.crouching = entity.isCrouching();
        poseStack.pushPose();
        if (entity.activitySeated()) poseStack.translate(0, -.55F * entity.getScale(), 0);
        super.render(entity, yaw, partialTick, poseStack, buffers, light);
        poseStack.popPose();
    }

    @Override public ResourceLocation getTextureLocation(GodAvatarEntity entity) {
        int variant = entity.textureVariant();
        ResourceLocation fallback = entity.slimModel() ? SLIM_DEFAULT : CLASSIC_DEFAULT;
        if (variant <= 0 || variant >= VARIANTS.length) return fallback;
        ResourceManager resources = Minecraft.getInstance().getResourceManager();
        TextureCache cache = textureCache;
        if (cache == null || cache.resources != resources) textureCache = cache = new TextureCache(resources);
        return cache.exists(variant) ? VARIANTS[variant] : fallback;
    }

    private static ResourceLocation[] variants() {
        ResourceLocation[] result = new ResourceLocation[256];
        for (int i = 1; i < result.length; i++) result[i] = ResourceLocation.fromNamespaceAndPath(
                MythicTrpg.MOD_ID, "textures/entity/god_avatar/variant_" + i + ".png");
        return result;
    }

    /** At most 255 existence checks per resource reload, never filesystem polling each frame. */
    private static final class TextureCache {
        private final ResourceManager resources;
        private final ConcurrentHashMap<Integer, Boolean> present = new ConcurrentHashMap<>();
        private TextureCache(ResourceManager resources) { this.resources = resources; }
        private boolean exists(int variant) {
            return present.computeIfAbsent(variant, key -> resources.getResource(VARIANTS[key]).isPresent());
        }
    }

    @Override protected boolean shouldShowName(GodAvatarEntity entity) {
        return false;
    }

    @EventBusSubscriber(modid = MythicTrpg.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static final class Registration {
        private Registration() {}

        @SubscribeEvent public static void renderers(EntityRenderersEvent.RegisterRenderers event) {
            event.registerEntityRenderer(GodAvatarEntities.GOD_AVATAR.get(), GodAvatarRenderer::new);
        }

        @SubscribeEvent public static void reloadListeners(RegisterClientReloadListenersEvent event) {
            event.registerReloadListener((ResourceManagerReloadListener) resources -> textureCache = new TextureCache(resources));
        }
    }
}

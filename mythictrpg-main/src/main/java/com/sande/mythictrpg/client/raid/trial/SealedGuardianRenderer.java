package com.sande.mythictrpg.client.raid.trial;

import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.raid.trial.SealedGuardianEntity;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.client.renderer.entity.layers.EyesLayer;
import net.minecraft.resources.ResourceLocation;

public final class SealedGuardianRenderer extends MobRenderer<SealedGuardianEntity, SealedGuardianModel> {
    private static final ResourceLocation TEXTURE = texture("sealed_guardian.png");
    private static final RenderType GLOW = RenderType.eyes(texture("sealed_guardian_emissive.png"));

    public SealedGuardianRenderer(EntityRendererProvider.Context context) {
        super(context, new SealedGuardianModel(context.bakeLayer(SealedGuardianModel.LAYER)), .95F);
        addLayer(new EyesLayer<SealedGuardianEntity, SealedGuardianModel>(this) {
            @Override public RenderType renderType() { return GLOW; }
        });
    }

    private static ResourceLocation texture(String file) {
        return ResourceLocation.fromNamespaceAndPath(MythicTrpg.MOD_ID, "textures/entity/trial/" + file);
    }

    @Override public ResourceLocation getTextureLocation(SealedGuardianEntity entity) { return TEXTURE; }
    @Override protected boolean shouldShowName(SealedGuardianEntity entity) { return false; }
}

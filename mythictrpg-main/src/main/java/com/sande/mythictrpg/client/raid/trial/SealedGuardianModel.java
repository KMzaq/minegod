package com.sande.mythictrpg.client.raid.trial;

import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.raid.trial.SealedGuardianEntity;
import net.minecraft.client.model.HierarchicalModel;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

/** Hand-authored native model. Geometry and pose curves are editable without an external loader. */
public final class SealedGuardianModel extends HierarchicalModel<SealedGuardianEntity> {
    public static final ModelLayerLocation LAYER = new ModelLayerLocation(
            ResourceLocation.fromNamespaceAndPath(MythicTrpg.MOD_ID, "sealed_guardian"), "main");
    private static final float PI = (float) Math.PI;
    private final ModelPart root, torso, head, core, rightArm, leftArm, rightForearm, leftForearm,
            rightShoulder, leftShoulder, rightLeg, leftLeg, seal;

    public SealedGuardianModel(ModelPart root) {
        this.root = root;
        torso = root.getChild("torso");
        head = torso.getChild("head");
        core = torso.getChild("core");
        rightArm = torso.getChild("right_arm");
        leftArm = torso.getChild("left_arm");
        rightForearm = rightArm.getChild("forearm");
        leftForearm = leftArm.getChild("forearm");
        rightShoulder = torso.getChild("right_pauldron");
        leftShoulder = torso.getChild("left_pauldron");
        rightLeg = root.getChild("right_leg");
        leftLeg = root.getChild("left_leg");
        seal = torso.getChild("seal");
    }

    public static LayerDefinition createLayer() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();
        PartDefinition torso = root.addOrReplaceChild("torso", CubeListBuilder.create()
                .texOffs(0, 0).addBox(-7, -14, -6, 14, 18, 12)
                .texOffs(0, 40).addBox(-12, -13, -7, 6, 12, 14)
                .texOffs(0, 40).addBox(6, -13, -7, 6, 12, 14)
                .texOffs(128, 0).addBox(-12.5F, -14.5F, -7.5F, 25, 3, 15)
                .texOffs(128, 0).addBox(-10, 1, -8, 20, 4, 16)
                .texOffs(128, 0).addBox(-12, -11, -8.5F, 3, 12, 2)
                .texOffs(128, 0).addBox(9, -11, -8.5F, 3, 12, 2)
                .texOffs(0, 128).addBox(-5.5F, -12.5F, -7.8F, 11, 11, 1.5F),
                PartPose.offset(0, -4, 0));
        // Native models use y=24 as the ground; the crown sits just above the 3.4-block body.
        PartDefinition head = torso.addOrReplaceChild("head", CubeListBuilder.create()
                .texOffs(0, 0).addBox(-5.5F, -7, -5, 11, 10, 10)
                .texOffs(0, 40).addBox(-6, 1.5F, -5.7F, 12, 3, 10.5F)
                .texOffs(128, 0).addBox(-7, -7.5F, -6, 14, 3, 12)
                .texOffs(128, 0).addBox(-6.5F, -4.5F, -6.2F, 3, 7, 2)
                .texOffs(128, 0).addBox(3.5F, -4.5F, -6.2F, 3, 7, 2)
                .texOffs(0, 128).addBox(-3.8F, -3.8F, -5.6F, 7.6F, 3, .6F)
                .texOffs(128, 128).addBox(-3.5F, -3.1F, -6.3F, 3, 1, .7F)
                .texOffs(128, 128).addBox(.5F, -3.1F, -6.3F, 3, 1, .7F)
                .texOffs(128, 0).addBox(-1, -9, -6.2F, 2, 3, 2),
                PartPose.offset(0, -18, 0));
        head.addOrReplaceChild("crown_left", CubeListBuilder.create().texOffs(128, 0)
                .addBox(-1.5F, -5, -1.5F, 3, 7, 3), PartPose.offsetAndRotation(6, -5, 0, 0, 0, -.25F));
        head.addOrReplaceChild("crown_right", CubeListBuilder.create().texOffs(128, 0)
                .addBox(-1.5F, -5, -1.5F, 3, 7, 3), PartPose.offsetAndRotation(-6, -5, 0, 0, 0, .25F));

        torso.addOrReplaceChild("core_frame", CubeListBuilder.create().texOffs(128, 0)
                .addBox(-6.5F, -6.5F, -1, 2, 13, 2)
                .addBox(4.5F, -6.5F, -1, 2, 13, 2)
                .addBox(-4.5F, -6.5F, -1, 9, 2, 2)
                .addBox(-4.5F, 4.5F, -1, 9, 2, 2),
                PartPose.offsetAndRotation(0, -7, -8.7F, 0, 0, PI / 4));
        torso.addOrReplaceChild("core", CubeListBuilder.create().texOffs(128, 128)
                .addBox(-3.4F, -3.4F, -2, 6.8F, 6.8F, 4)
                .texOffs(144, 144).addBox(-1.5F, -1.5F, -2.6F, 3, 3, 1),
                PartPose.offsetAndRotation(0, -7, -9.5F, 0, 0, PI / 4));
        torso.addOrReplaceChild("chest_glyphs", CubeListBuilder.create().texOffs(128, 128)
                .addBox(-10.6F, -9, -8.7F, .8F, 5, .35F)
                .addBox(9.8F, -9, -8.7F, .8F, 5, .35F)
                .addBox(-7, 2.4F, -8.4F, 14, .55F, .4F), PartPose.ZERO);

        arm(torso, "right_arm", -15, false);
        arm(torso, "left_arm", 15, true);
        pauldron(torso, "right_pauldron", -16, false);
        pauldron(torso, "left_pauldron", 16, true);

        root.addOrReplaceChild("pelvis", CubeListBuilder.create().texOffs(0, 0)
                .addBox(-10, -2, -6, 20, 8, 12)
                .texOffs(128, 0).addBox(-12, -2.5F, -7, 24, 3, 14)
                .texOffs(128, 0).addBox(-9, 0, -8, 7, 8, 2)
                .addBox(2, 0, -8, 7, 8, 2)
                .texOffs(128, 128).addBox(-1, 0, -7.5F, 2, 6, 1), PartPose.offset(0, -2, 0));
        leg(root, "right_leg", -6, false);
        leg(root, "left_leg", 6, true);

        PartDefinition seal = torso.addOrReplaceChild("seal", CubeListBuilder.create(), PartPose.offset(0, -8, 10));
        for (int index = 0; index < 8; index++) {
            float angle = index * PI / 4;
            seal.addOrReplaceChild("fragment_" + index, CubeListBuilder.create().texOffs(128, 0)
                    .addBox(-4.2F, -1.5F, -1, 8.4F, 3, 2)
                    .texOffs(128, 128).addBox(-2.7F, -.45F, -1.4F, 5.4F, .9F, .5F),
                    PartPose.offsetAndRotation(Mth.sin(angle) * 16, Mth.cos(angle) * 16, 0, 0, 0, -angle));
        }
        return LayerDefinition.create(mesh, 256, 256);
    }

    private static void arm(PartDefinition torso, String name, float x, boolean mirror) {
        PartDefinition arm = torso.addOrReplaceChild(name, CubeListBuilder.create().mirror(mirror)
                .texOffs(0, 0).addBox(-4.5F, -1, -4.5F, 9, 14, 9)
                .texOffs(0, 128).addBox(-3, 11.5F, -3, 6, 3, 6), PartPose.offset(x, -11, 0));
        arm.addOrReplaceChild("forearm", CubeListBuilder.create().mirror(mirror)
                .texOffs(0, 40).addBox(-5.5F, 0, -5.5F, 11, 13, 11)
                .texOffs(128, 0).addBox(-6.5F, .5F, -6.5F, 13, 4, 13)
                .texOffs(128, 0).addBox(-6, 10, -6, 12, 3, 12)
                .texOffs(0, 0).addBox(-6.2F, 13, -6.2F, 12.4F, 4, 12.4F)
                .texOffs(128, 128).addBox(-.65F, 4.5F, -5.9F, 1.3F, 5, .5F)
                .texOffs(128, 128).addBox(-3.2F, 6.5F, -5.9F, 6.4F, .9F, .5F), PartPose.offset(0, 12, 0));
    }

    private static void pauldron(PartDefinition torso, String name, float x, boolean mirror) {
        PartDefinition shell = torso.addOrReplaceChild(name, CubeListBuilder.create().mirror(mirror)
                .texOffs(128, 0).addBox(-7, -4, -7.5F, 14, 6, 15)
                .texOffs(0, 40).addBox(-6, -5, -6.5F, 12, 2, 13)
                .texOffs(128, 0).addBox(-8, 1, -7, 16, 4, 14)
                .texOffs(128, 128).addBox(-4.5F, -1.1F, -7.9F, 9, .9F, .5F)
                .texOffs(128, 0).addBox(-1.5F, -7, -1.5F, 3, 4, 3),
                PartPose.offsetAndRotation(x, -13, 0, 0, 0, mirror ? -.10F : .10F));
        shell.addOrReplaceChild("outer_plate", CubeListBuilder.create().mirror(mirror).texOffs(128, 0)
                .addBox(-3, -2, -5.5F, 6, 9, 11),
                PartPose.offsetAndRotation(mirror ? 7 : -7, 0, 0, 0, 0, mirror ? -.24F : .24F));
    }

    private static void leg(PartDefinition root, String name, float x, boolean mirror) {
        root.addOrReplaceChild(name, CubeListBuilder.create().mirror(mirror)
                .texOffs(0, 0).addBox(-4, 0, -4, 8, 10, 8)
                .texOffs(128, 0).addBox(-5, 7, -5, 10, 7, 10)
                .texOffs(0, 40).addBox(-5.5F, 14, -7, 11, 4, 14)
                .texOffs(128, 0).addBox(-5.7F, 17, -7.2F, 11.4F, 1, 14.4F)
                .texOffs(128, 128).addBox(-.5F, 8.5F, -5.4F, 1, 4, .5F), PartPose.offset(x, 6, 0));
    }

    @Override public ModelPart root() { return root; }

    @Override public void setupAnim(SealedGuardianEntity entity, float walk, float strength,
            float age, float yaw, float pitch) {
        root.getAllParts().forEach(ModelPart::resetPose);
        float rhythm = Mth.sin(age * .09F);
        float partial = Mth.clamp(age - entity.tickCount, 0, 1);
        float progress = Mth.clamp((entity.poseElapsed() + partial) / entity.poseDuration(), 0, 1);
        float stride = Math.min(strength, .7F);
        rightLeg.xRot = Mth.cos(walk * .55F) * .55F * stride;
        leftLeg.xRot = Mth.cos(walk * .55F + PI) * .55F * stride;
        torso.y += rhythm * .18F;
        head.yRot = Mth.clamp(yaw * PI / 180, -.35F, .35F);
        head.xRot = Mth.clamp(pitch * PI / 180, -.20F, .25F);
        rightArm.xRot = Mth.cos(walk * .55F + PI) * .22F * stride;
        leftArm.xRot = Mth.cos(walk * .55F) * .22F * stride;
        rightArm.zRot = .09F + rhythm * .02F;
        leftArm.zRot = -.09F - rhythm * .02F;
        rightShoulder.y += rhythm * .38F;
        leftShoulder.y -= rhythm * .38F;
        core.zRot += age * .008F;
        core.y += rhythm * .14F;
        seal.zRot = age * (entity.phase() >= 2 ? .012F : .006F);
        seal.yRot = Mth.sin(age * .04F) * .08F;
        float intensity = entity.phase() >= 2 ? 1.08F : 1;
        core.xScale = core.yScale = core.zScale = intensity + Mth.sin(age * .17F) * .035F;
        switch (entity.pose()) {
            case SealedGuardianEntity.SWEEP -> sweep(progress);
            case SealedGuardianEntity.SLAM -> slam(progress);
            case SealedGuardianEntity.BOMBARD -> bombard(progress, age);
            case SealedGuardianEntity.SHIELD -> shield(age);
            case SealedGuardianEntity.STAGGER -> {
                torso.xRot = .26F;
                torso.zRot = rhythm * .035F;
                torso.y += 1.1F;
                head.xRot += .28F;
                rightArm.xRot = leftArm.xRot = .20F;
                rightForearm.xRot = leftForearm.xRot = -.22F;
                seal.xRot = .16F;
                core.xScale = core.yScale = core.zScale = .86F;
            }
            case SealedGuardianEntity.RAGE -> {
                float swell = Mth.sin(progress * PI);
                rightArm.zRot = .55F + swell * .32F;
                leftArm.zRot = -.55F - swell * .32F;
                rightArm.xRot = leftArm.xRot = -.8F;
                head.xRot = -.3F;
                rightShoulder.x -= 1.5F * swell;
                leftShoulder.x += 1.5F * swell;
                seal.zRot = age * .04F;
                core.xScale = core.yScale = core.zScale = 1.15F + swell * .18F;
            }
            default -> { if (entity.shielded()) shield(age); }
        }
    }

    private void sweep(float progress) {
        // The server maps windup to 0..60, active to 60..61 and recovery to 61..100.
        float windup = smooth(progress / .60F);
        float hit = smooth((progress - .60F) / .01F);
        float settle = smooth((progress - .61F) / .39F);
        torso.yRot = lerpReturn(-.58F * windup, .72F, hit, settle);
        rightArm.xRot = lerpReturn(-1.05F * windup, -1.10F, hit, settle);
        rightArm.yRot = lerpReturn(-.90F * windup, 1.05F, hit, settle);
        rightArm.zRot = .22F * windup * (1 - settle);
        rightForearm.xRot = -.20F * windup * (1 - settle);
        leftArm.xRot = -.42F * windup * (1 - settle);
        head.yRot -= torso.yRot * .4F;
    }

    private void slam(float progress) {
        float windup = smooth(progress / .60F);
        float hit = smooth((progress - .60F) / .01F);
        float settle = smooth((progress - .61F) / .39F);
        rightArm.xRot = leftArm.xRot = lerpReturn(-2.65F * windup, .18F, hit, settle);
        rightArm.zRot = .22F * windup * (1 - hit);
        leftArm.zRot = -rightArm.zRot;
        torso.xRot = lerpReturn(-.13F * windup, .48F, hit, settle);
        torso.y += 1.8F * hit * (1 - settle);
        head.xRot += .12F * hit * (1 - settle);
        rightLeg.xRot = leftLeg.xRot = -.10F * hit * (1 - settle);
    }

    private void bombard(float progress, float age) {
        float aim = smooth(progress / .30F) * (1 - smooth((progress - .83F) / .17F));
        rightArm.xRot = leftArm.xRot = -1.18F * aim;
        rightArm.yRot = -.18F * aim;
        leftArm.yRot = .18F * aim;
        rightForearm.xRot = leftForearm.xRot = -.32F * aim;
        core.z -= aim * .9F;
        core.xScale += Mth.sin(age * 1.1F) * .06F * aim;
        seal.zRot = age * .027F;
    }

    private void shield(float age) {
        rightArm.xRot = leftArm.xRot = -.90F;
        rightArm.yRot = .72F;
        leftArm.yRot = -.72F;
        rightForearm.xRot = leftForearm.xRot = -.85F;
        rightShoulder.x -= 1;
        leftShoulder.x += 1;
        seal.zRot = age * .025F;
        core.z += .8F;
    }

    private static float smooth(float value) {
        float bounded = Mth.clamp(value, 0, 1);
        return bounded * bounded * (3 - 2 * bounded);
    }

    private static float lerpReturn(float windup, float strike, float hit, float settle) {
        return Mth.lerp(hit, windup, strike) * (1 - settle);
    }
}

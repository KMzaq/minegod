package com.sande.mythictrpg.client.godavatar;

import com.sande.mythictrpg.godavatar.GodAvatarEntity;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.util.Mth;

/** Cosmetic whole-body poses layered over vanilla walking/attack/armor transforms. */
public final class GodAvatarActivityModel extends PlayerModel<GodAvatarEntity> {
    public GodAvatarActivityModel(ModelPart root, boolean slim) { super(root, slim); }

    @Override public void setupAnim(GodAvatarEntity entity, float walk, float strength, float age, float yaw, float pitch) {
        // Models are shared by all avatars: restore coordinates as well as rotations before each entity.
        for (ModelPart part : new ModelPart[]{head, hat, body, rightArm, leftArm, rightLeg, leftLeg,
                rightSleeve, leftSleeve, rightPants, leftPants, jacket}) part.resetPose();
        super.setupAnim(entity, walk, strength, age, yaw, pitch);
        float rhythm = Mth.sin(age * .12F);
        switch (entity.activityPose()) {
            case "SIT", "READ" -> {
                rightLeg.xRot = leftLeg.xRot = -1.45F;
                rightLeg.yRot = .15F; leftLeg.yRot = -.15F;
                if (entity.activityPose().equals("READ")) {
                    rightArm.xRot = leftArm.xRot = -1.12F;
                    rightArm.yRot = -.24F; leftArm.yRot = .24F; head.xRot = .28F;
                }
            }
            case "EAT", "DRINK" -> {
                rightArm.xRot = -1.65F + rhythm * .09F; rightArm.yRot = -.30F;
                head.xRot = entity.activityPose().equals("DRINK") ? -.18F : .08F;
            }
            case "WORK" -> { rightArm.xRot = -1.15F + rhythm * .55F; leftArm.xRot = -.45F; body.xRot = .12F; }
            case "TRAIN" -> { rightArm.xRot = -1.05F + rhythm * .55F; leftArm.xRot = -.95F - rhythm * .12F; }
            case "RITUAL" -> { rightArm.xRot = leftArm.xRot = -1.35F; rightArm.zRot = .35F; leftArm.zRot = -.35F; }
            case "PLAY" -> { rightArm.xRot = -.85F + rhythm * .12F; leftArm.xRot = -.85F - rhythm * .12F; }
            case "PERFORM" -> { rightArm.xRot = -1.1F + rhythm * .2F; leftArm.xRot = -1.25F; leftArm.yRot = .4F; }
            case "TALK" -> { rightArm.xRot = -.5F + rhythm * .22F; rightArm.zRot = .25F; }
            case "OBSERVE" -> { /* Vanilla look control retains the actual target and head direction. */ }
            default -> { }
        }
        hat.copyFrom(head); jacket.copyFrom(body);
        rightSleeve.copyFrom(rightArm); leftSleeve.copyFrom(leftArm);
        rightPants.copyFrom(rightLeg); leftPants.copyFrom(leftLeg);
    }
}

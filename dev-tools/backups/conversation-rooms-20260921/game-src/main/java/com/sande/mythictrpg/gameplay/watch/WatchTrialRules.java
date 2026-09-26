package com.sande.mythictrpg.gameplay.watch;

import com.sande.mythictrpg.gameplay.ledger.ActionRecord;
import static com.sande.mythictrpg.gameplay.watch.WatchContract.*;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import java.util.*;

/** Explicit admin trial: a supplied box, sky-visible crop completion, owner-only disclosure. No authored god powers. */
final class WatchTrialRules implements GameWatchGateway.Rules {
    private final Map<Key, Approval> trials = new HashMap<>();
    static Ref disclosure(UUID player) { return new Ref("trial:owner/" + player, 1); }
    Approval register(UUID world, ResourceLocation god, ServerPlayer player, int radius) {
        if (radius < 1 || radius > 128) throw new IllegalArgumentException("trial radius 1..128");
        var p = player.blockPosition(); Key key = new Key(god.toString(), player.getUUID());
        var policy = new Policy(new Ref("trial:policy/" + UUID.randomUUID(), 1), god.toString(), "trial:explicit_sight", "trial:crop",
                List.of(new Area(player.level().dimension().location().toString(), p.getX()-radius, p.getY()-radius, p.getZ()-radius,
                        p.getX()+radius, p.getY()+radius, p.getZ()+radius)),
                Set.of(Field.ACTOR, Field.ACTION, Field.SUBJECT_TYPE, Field.TIME, Field.OUTCOME));
        var approval = new Approval(world, key, new Ref("trial:admin/" + UUID.randomUUID(), 1), new Ref("trial:explicit_start", 1), policy);
        trials.put(key, approval); return approval;
    }
    void remove(UUID player) { trials.keySet().removeIf(k -> k.playerId().equals(player)); }
    Set<String> godsFor(UUID player) { return trials.keySet().stream().filter(k -> k.playerId().equals(player)).map(Key::godId).collect(java.util.stream.Collectors.toSet()); }
    public Optional<Approval> approve(MinecraftServer server, UUID world, Key key) { return Optional.ofNullable(trials.get(key)).filter(a -> a.worldId().equals(world)); }
    public Scene captureScene(MinecraftServer server, ActionRecord.Draft event) {
        var level = server.getLevel(ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(event.dimensionId())));
        boolean blocked = level == null || event.position() == null || event.type() != ActionRecord.Type.MATURE_CROP_REMOVED
                || !level.canSeeSky(new BlockPos(event.position().x(), event.position().y(), event.position().z()));
        Map<Field, Visibility> fields = new EnumMap<>(Field.class);
        for (Field f : Field.values()) fields.put(f, new Visibility(!blocked, Map.of(event.actorId(), disclosure(event.actorId()))));
        return new Scene(event.occurrenceId(), event.captureSession(), event.captureOrder(), new Ref("trial:external_crop_scene", 1),
                Set.of("trial:explicit_sight"), Set.of("trial:crop"), blocked, false, fields);
    }
}

package com.sande.mythictrpg.weaponskill;

import com.google.gson.JsonParser;
import com.sande.mythictrpg.MythicTrpg;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.*;
import net.minecraft.util.profiling.ProfilerFiller;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import java.util.*;

/** No bundled weapon assignments. Bad reload fails closed instead of silently retaining removed skills. */
@EventBusSubscriber(modid = MythicTrpg.MOD_ID)
public final class WeaponSkillDefinitions extends SimplePreparableReloadListener<Map<ResourceLocation,WeaponSkillDefinition>> {
    public static final WeaponSkillDefinitions INSTANCE = new WeaponSkillDefinitions();
    private volatile Map<ResourceLocation,WeaponSkillDefinition> snapshot = Map.of();
    private WeaponSkillDefinitions() { }
    public Optional<WeaponSkillDefinition> find(ResourceLocation id) { return Optional.ofNullable(snapshot.get(id)); }
    @SubscribeEvent public static void reload(AddReloadListenerEvent event) { event.addListener(INSTANCE); }
    @Override protected Map<ResourceLocation,WeaponSkillDefinition> prepare(ResourceManager resources, ProfilerFiller profiler) {
        var converter = FileToIdConverter.json("mythictrpg/weapon_skills");
        try {
            var files = converter.listMatchingResources(resources);
            if (files.size() > 1024) throw new IllegalArgumentException("Too many weapon skills");
            Map<ResourceLocation,WeaponSkillDefinition> result = new HashMap<>();
            for (var entry : files.entrySet()) {
                try (var reader = entry.getValue().openAsReader()) {
                    StringBuilder text = new StringBuilder(); char[] buffer = new char[2048]; int count;
                    while ((count = reader.read(buffer)) != -1) {
                        if (text.length() + count > 16384) throw new IllegalArgumentException("Weapon skill document too large");
                        text.append(buffer,0,count);
                    }
                    var id = converter.fileToId(entry.getKey());
                    var skill = WeaponSkillDefinition.parse(id,JsonParser.parseString(text.toString()).getAsJsonObject());
                    validateEffect(skill); result.put(id,skill);
                }
            }
            return Map.copyOf(result);
        } catch (Exception failure) {
            MythicTrpg.LOGGER.error("Weapon skills disabled: invalid datapack",failure); return Map.of();
        }
    }
    public static void validateEffect(WeaponSkillDefinition skill) {
        if (skill.action() instanceof WeaponSkillDefinition.SelfEffect effect) {
            if (!BuiltInRegistries.MOB_EFFECT.containsKey(effect.effectId())) throw new IllegalArgumentException("Unknown skill effect");
            var value = BuiltInRegistries.MOB_EFFECT.get(effect.effectId());
            if (value.isInstantenous() || !value.isBeneficial()) throw new IllegalArgumentException("Self effect must be beneficial and non-instant");
        }
    }
    @Override protected void apply(Map<ResourceLocation,WeaponSkillDefinition> prepared, ResourceManager resources, ProfilerFiller profiler) {
        snapshot = Map.copyOf(prepared);
        MythicTrpg.LOGGER.info("Loaded {} optional weapon skills", snapshot.size());
    }
}

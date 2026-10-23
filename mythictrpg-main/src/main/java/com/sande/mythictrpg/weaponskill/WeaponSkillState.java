package com.sande.mythictrpg.weaponskill;

import com.sande.mythictrpg.MythicTrpg;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** One durable account per player, independent of inventory, death, dimension and connection. */
public final class WeaponSkillState extends SavedData {
    public enum Result { READY, SUCCESS, COOLDOWN, NO_MANA, DUPLICATE, UNAVAILABLE, INVALID }
    private static final int DATA_VERSION = 2;
    private static final int MAX_PLAYERS = 16_384;
    private static final String FILE_NAME = "mythictrpg_weapon_skills";
    private static final Factory<WeaponSkillState> FACTORY = new Factory<>(WeaponSkillState::new, WeaponSkillState::load);
    private final Map<UUID, ManaAccount> accounts = new LinkedHashMap<>();
    private final Map<UUID, String> selected = new LinkedHashMap<>();
    private CompoundTag rejectedRawData;

    public static WeaponSkillState get(MinecraftServer server) {
        if (!server.isSameThread()) throw new IllegalStateException("Weapon skill state requires the server thread");
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, FILE_NAME);
    }

    public boolean isReady() { return rejectedRawData == null; }

    /** Empty means unavailable, never a replacement account with free mana. */
    public Optional<ManaAccount> account(UUID playerId) {
        if (!isReady() || playerId == null) return Optional.empty();
        ManaAccount existing = accounts.get(playerId);
        if (existing != null) return Optional.of(existing);
        if (accounts.size() >= MAX_PLAYERS) return Optional.empty();
        ManaAccount account = new ManaAccount();
        accounts.put(playerId, account);
        setDirty();
        return Optional.of(account);
    }

    public Result canUse(UUID playerId, String skillId, int manaCost, int cooldownTicks, long gameTick) {
        return account(playerId).map(account -> account.canUse(skillId, manaCost, cooldownTicks, gameTick))
                .orElse(Result.UNAVAILABLE);
    }
    public boolean clamp(UUID playerId,double maximum) {
        boolean changed=account(playerId).map(a->a.clamp(maximum)).orElse(false);
        if(changed)setDirty(); return changed;
    }
    public double refund(UUID playerId,double amount,double maximum) {
        double value=account(playerId).map(a->a.refund(amount,maximum)).orElse(0.0);
        if(value!=0)setDirty(); return value;
    }
    public long reduceCooldown(UUID playerId,String skillId,long ticks,long now) {
        long value=account(playerId).map(a->a.reduceCooldown(skillId,ticks,now)).orElse(0L);
        if(value>0)setDirty(); return value;
    }
    public Optional<String> selected(UUID player) { return isReady()?Optional.ofNullable(selected.get(player)):Optional.empty(); }
    public boolean select(UUID player,String id) {
        if(!ManaAccount.validId(id)||account(player).isEmpty())return false;
        selected.put(player,id);setDirty();return true;
    }

    /** Commit a validated cast before its effect, on the server thread with a reentrant activation guard. */
    public Result trySpend(UUID playerId, String skillId, int manaCost, int cooldownTicks, long gameTick) {
        Result result = account(playerId).map(account -> account.spend(skillId, manaCost, cooldownTicks, gameTick))
                .orElse(Result.UNAVAILABLE);
        if (result == Result.SUCCESS) setDirty();
        return result;
    }

    /** Runtime calls once per online, living, non-spectator player's server tick. No offline catch-up. */
    public boolean regenerate(UUID playerId, long gameTick) {
        return regenerate(playerId,gameTick,com.sande.mythictrpg.skills.ManaTuning.BASE_MAX_MANA);
    }
    public boolean regenerate(UUID playerId,long gameTick,double maximum) {
        boolean changed = account(playerId).map(account -> account.regenerate(gameTick,maximum)).orElse(false);
        if (changed) setDirty();
        return changed;
    }

    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        if (!isReady()) return rejectedRawData.copy();
        tag.putInt("dataVersion", DATA_VERSION);
        ListTag players = new ListTag();
        accounts.forEach((id, account) -> {
            CompoundTag player = new CompoundTag();
            player.putUUID("playerId", id);
            player.putLong("manaMicroUnits", account.microUnits());
            player.putInt("regenRemainder", account.regenRemainder());
            player.putString("selectedSkill", selected.getOrDefault(id,""));
            player.putLong("lastUseTick", account.lastUseTick());
            player.putLong("lastRegenTick", account.lastRegenTick());
            ListTag cooldowns = new ListTag();
            account.cooldownEnds().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
                CompoundTag cooldown = new CompoundTag();
                cooldown.putString("skillId", entry.getKey());
                cooldown.putLong("endTick", entry.getValue());
                cooldowns.add(cooldown);
            });
            player.put("cooldowns", cooldowns);
            players.add(player);
        });
        tag.put("players", players);
        return tag;
    }

    static WeaponSkillState load(CompoundTag tag, HolderLookup.Provider registries) {
        WeaponSkillState state = new WeaponSkillState();
        try {
            fields(tag, "dataVersion", "players");
            int version=tag.getInt("dataVersion");
            if (!tag.contains("dataVersion", Tag.TAG_INT) || version < 1 || version > DATA_VERSION)
                throw new IllegalArgumentException("Unsupported weapon skill dataVersion");
            ListTag players = list(tag, "players", MAX_PLAYERS);
            for (Tag value : players) {
                CompoundTag player = (CompoundTag) value;
                if(version==1) fields(player, "playerId", "manaUnits", "lastUseTick", "lastRegenTick", "cooldowns");
                else fields(player,"playerId","manaMicroUnits","regenRemainder","selectedSkill","lastUseTick","lastRegenTick","cooldowns");
                if (!player.hasUUID("playerId") || (version==1 ? !player.contains("manaUnits", Tag.TAG_INT)
                        : !player.contains("manaMicroUnits",Tag.TAG_LONG)||!player.contains("regenRemainder",Tag.TAG_INT)||!player.contains("selectedSkill",Tag.TAG_STRING))
                        || !player.contains("lastUseTick", Tag.TAG_LONG) || !player.contains("lastRegenTick", Tag.TAG_LONG))
                    throw new IllegalArgumentException("Invalid weapon skill player fields");
                Map<String, Long> cooldowns = new LinkedHashMap<>();
                for (Tag cooldownValue : list(player, "cooldowns", ManaAccount.MAX_COOLDOWNS)) {
                    CompoundTag cooldown = (CompoundTag) cooldownValue;
                    fields(cooldown, "skillId", "endTick");
                    if (!cooldown.contains("skillId", Tag.TAG_STRING) || !cooldown.contains("endTick", Tag.TAG_LONG)
                            || cooldowns.putIfAbsent(cooldown.getString("skillId"), cooldown.getLong("endTick")) != null)
                        throw new IllegalArgumentException("Invalid or duplicate weapon skill cooldown");
                }
                ManaAccount account = version==1 ? new ManaAccount(player.getInt("manaUnits"), player.getLong("lastUseTick"),
                        player.getLong("lastRegenTick"), cooldowns) : new ManaAccount(player.getLong("manaMicroUnits"),player.getInt("regenRemainder"),
                        player.getLong("lastUseTick"),player.getLong("lastRegenTick"),cooldowns);
                if (state.accounts.putIfAbsent(player.getUUID("playerId"), account) != null)
                    throw new IllegalArgumentException("Duplicate weapon skill player");
                String selection=player.getString("selectedSkill");
                if(!selection.isEmpty()) {
                    if(!ManaAccount.validId(selection))throw new IllegalArgumentException("Invalid selected learned skill");
                    state.selected.put(player.getUUID("playerId"),selection);
                }
            }
        } catch (RuntimeException invalid) {
            state.accounts.clear();
            state.selected.clear();
            state.rejectedRawData = tag.copy();
            MythicTrpg.LOGGER.error("Weapon skill state rejected; preserving original data and disabling mana use", invalid);
        }
        return state;
    }

    private static ListTag list(CompoundTag tag, String key, int maximum) {
        if (!(tag.get(key) instanceof ListTag list) || list.size() > maximum
                || !list.isEmpty() && list.getElementType() != Tag.TAG_COMPOUND)
            throw new IllegalArgumentException("Invalid weapon skill list " + key);
        return list;
    }

    private static void fields(CompoundTag tag, String... fields) {
        if (!tag.getAllKeys().equals(Set.of(fields))) throw new IllegalArgumentException("Missing or unknown weapon skill fields");
    }
}

package com.sande.mythictrpg.weaponskill;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@GameTestHolder("mythictrpg_weapon_skills")
@PrefixGameTestTemplate(false)
public final class WeaponSkillStateGameTests {
    @GameTest(templateNamespace = "mythictrpg_weapon_skills", template = "empty")
    public static void freshManaAndChecksNeverCharge(GameTestHelper h) {
        var state = new WeaponSkillState(); var player = UUID.randomUUID();
        h.assertTrue(state.account(player).orElseThrow().mana() == 200, "initial mana must be 200");
        h.assertValueEqual(state.canUse(player, "test:skill", 170, 40, 10), WeaponSkillState.Result.READY,
                "first skill should be ready");
        h.assertTrue(state.account(player).orElseThrow().mana() == 200, "preflight charged mana");
        h.assertValueEqual(state.trySpend(player, "test:skill", 170, 40, 10), WeaponSkillState.Result.SUCCESS,
                "successful effect charge rejected");
        h.assertTrue(state.account(player).orElseThrow().mana() == 30, "wrong cost applied");
        h.assertValueEqual(state.trySpend(player, "test:skill", 170, 40, 11), WeaponSkillState.Result.COOLDOWN,
                "same skill must retain cooldown");
        h.assertValueEqual(state.trySpend(player, "test:other", 31, 40, 11), WeaponSkillState.Result.NO_MANA,
                "insufficient mana must reject");
        h.assertTrue(state.account(player).orElseThrow().mana() == 30, "rejected skill charged mana");
        h.succeed();
    }

    @GameTest(templateNamespace = "mythictrpg_weapon_skills", template = "empty")
    public static void immediateRegenerationHasNoOfflineCatchup(GameTestHelper h) {
        var state = new WeaponSkillState(); var player = UUID.randomUUID();
        state.trySpend(player, "test:skill", 100, 0, 100);
        h.assertTrue(state.regenerate(player, 100), "regeneration should start without a waiting window");
        h.assertTrue(state.account(player).orElseThrow().mana() == 100.1, "first online tick should restore 0.1");
        h.assertTrue(!state.regenerate(player, 100), "duplicate tick restored mana twice");
        for (long tick = 101; tick < 120; tick++) state.regenerate(player, tick);
        h.assertTrue(state.account(player).orElseThrow().mana() == 102, "twenty ticks must restore exactly two mana");
        state.regenerate(player, 100_000);
        h.assertTrue(state.account(player).orElseThrow().mana() == 102.1, "offline interval must not refill mana");
        for (long tick = 100_001; tick < 102_001; tick++) state.regenerate(player, tick);
        h.assertTrue(state.account(player).orElseThrow().mana() == 200, "regeneration must stop at 200");
        h.succeed();
    }

    @GameTest(templateNamespace = "mythictrpg_weapon_skills", template = "empty")
    public static void cooldownIsPerPlayerAndSkillNotWeapon(GameTestHelper h) {
        var state = new WeaponSkillState(); var owner = UUID.randomUUID(); var other = UUID.randomUUID();
        state.trySpend(owner, "test:shared", 20, 100, 50);
        h.assertValueEqual(state.canUse(owner, "test:shared", 20, 100, 51), WeaponSkillState.Result.COOLDOWN,
                "another copy of the same skill must share cooldown");
        h.assertValueEqual(state.canUse(owner, "test:different", 20, 100, 51), WeaponSkillState.Result.READY,
                "different skill must have its own cooldown");
        h.assertValueEqual(state.canUse(other, "test:shared", 20, 100, 51), WeaponSkillState.Result.READY,
                "other player's mana and cooldown must be independent");
        h.assertTrue(state.account(owner).orElseThrow().remainingTicks("test:shared", 149) == 1,
                "cooldown ended a tick early");
        h.assertValueEqual(state.canUse(owner, "test:shared", 20, 100, 150), WeaponSkillState.Result.READY,
                "cooldown should end on the exact deadline");
        h.succeed();
    }

    @GameTest(templateNamespace = "mythictrpg_weapon_skills", template = "empty")
    public static void zeroCostZeroCooldownCannotDoubleCastSameTick(GameTestHelper h) {
        var state = new WeaponSkillState(); var player = UUID.randomUUID();
        h.assertValueEqual(state.trySpend(player, "test:first", 0, 0, 0), WeaponSkillState.Result.SUCCESS,
                "a fresh account must support tick zero");
        h.assertValueEqual(state.trySpend(player, "test:second", 0, 0, 0), WeaponSkillState.Result.DUPLICATE,
                "two hands or repeated packets cannot execute twice in the same tick");
        var restored = WeaponSkillState.load(state.save(new CompoundTag(), h.getLevel().registryAccess()),
                h.getLevel().registryAccess());
        h.assertValueEqual(restored.canUse(player, "test:second", 0, 0, 0), WeaponSkillState.Result.DUPLICATE,
                "save reload cannot clear the duplicate guard");
        h.assertValueEqual(restored.trySpend(player, "test:second", 0, 0, 1), WeaponSkillState.Result.SUCCESS,
                "zero cooldown skill should be available next tick");
        h.succeed();
    }

    @GameTest(templateNamespace = "mythictrpg_weapon_skills", template = "empty")
    public static void savedManaAndCooldownDoNotResetWithPlayerObject(GameTestHelper h) {
        var state = new WeaponSkillState(); var player = UUID.randomUUID();
        state.trySpend(player, "test:skill", 199, 200, 10);
        state.regenerate(player, 11);
        var encoded = state.save(new CompoundTag(), h.getLevel().registryAccess());
        var restored = WeaponSkillState.load(encoded, h.getLevel().registryAccess());
        UUID samePlayerNewObject = UUID.fromString(player.toString());
        h.assertTrue(restored.account(samePlayerNewObject).orElseThrow().mana() == 1.1,
                "reconnection or respawn must not produce a fresh 200 mana account");
        h.assertTrue(restored.account(player).orElseThrow().remainingTicks("test:skill", 12) == 198,
                "cooldown lost on storage reload");
        h.assertTrue(!restored.regenerate(player, 11), "reload must not regenerate the same tick twice");
        h.assertTrue(restored.account(player).orElseThrow().mana() == 1.1, "duplicate regeneration changed saved mana");
        h.assertValueEqual(restored.save(new CompoundTag(), h.getLevel().registryAccess()), encoded,
                "read-only reload changed stored values");
        h.succeed();
    }

    @GameTest(templateNamespace = "mythictrpg_weapon_skills", template = "empty")
    public static void corruptedStorageIsPreservedAndNeverRefills(GameTestHelper h) {
        var state = new WeaponSkillState(); var player = UUID.randomUUID();
        state.trySpend(player, "test:skill", 200, 20, 1);
        var encoded = state.save(new CompoundTag(), h.getLevel().registryAccess());
        CompoundTag missingMana = encoded.copy();
        missingMana.getList("players", Tag.TAG_COMPOUND).getCompound(0).remove("manaMicroUnits");
        assertRejected(h, missingMana, player);
        CompoundTag overfilled = encoded.copy();
        overfilled.getList("players", Tag.TAG_COMPOUND).getCompound(0).putLong("manaMicroUnits", 1_000_000_000_001L);
        assertRejected(h, overfilled, player);
        CompoundTag invalidCarry = encoded.copy();
        invalidCarry.getList("players", Tag.TAG_COMPOUND).getCompound(0).putInt("regenRemainder", 2000);
        assertRejected(h, invalidCarry, player);
        CompoundTag futureVersion = encoded.copy(); futureVersion.putInt("dataVersion", 3);
        assertRejected(h, futureVersion, player);
        CompoundTag duplicatePlayer = encoded.copy();
        ListTag players = duplicatePlayer.getList("players", Tag.TAG_COMPOUND);
        players.add(players.getCompound(0).copy());
        assertRejected(h, duplicatePlayer, player);
        CompoundTag wrongList = encoded.copy(); wrongList.putString("players", "not a list");
        assertRejected(h, wrongList, player);
        h.succeed();
    }

    @GameTest(templateNamespace = "mythictrpg_weapon_skills", template = "empty")
    public static void cooldownCapacityPreflightAndExpiredPruning(GameTestHelper h) {
        Map<String, Long> full = new LinkedHashMap<>();
        for (int i = 0; i < ManaAccount.MAX_COOLDOWNS; i++) full.put("test:skill_" + i, 100L);
        var account = new ManaAccount(2000, -1, -1, full);
        h.assertValueEqual(account.canUse("test:new", 0, 10, 50), WeaponSkillState.Result.UNAVAILABLE,
                "capacity must fail before applying the skill effect");
        h.assertValueEqual(account.spend("test:new", 0, 10, 100), WeaponSkillState.Result.SUCCESS,
                "expired cooldowns must release capacity");
        h.assertTrue(account.cooldownEnds().size() == 1, "expired cooldown entries were not pruned");
        h.assertTrue(account.remainingTicks("test:new", 100) == 10, "new cooldown was lost during pruning");
        h.succeed();
    }

    @GameTest(templateNamespace = "mythictrpg_weapon_skills", template = "empty")
    public static void invalidCostsAndTimeCannotGiveManaOrFreeUse(GameTestHelper h) {
        var state = new WeaponSkillState(); var player = UUID.randomUUID();
        for (int cost : new int[] {-1, 1_000_001, Integer.MAX_VALUE})
            h.assertValueEqual(state.trySpend(player, "test:skill", cost, 10, 0), WeaponSkillState.Result.INVALID,
                    "invalid mana cost accepted");
        h.assertValueEqual(state.trySpend(player, "test:skill", 0, -1, 0), WeaponSkillState.Result.INVALID,
                "negative cooldown accepted");
        h.assertValueEqual(state.trySpend(player, "test:skill", 0, 20, Long.MAX_VALUE - 10), WeaponSkillState.Result.INVALID,
                "overflowed cooldown accepted");
        h.assertValueEqual(state.trySpend(player, "bad skill", 0, 0, 0), WeaponSkillState.Result.INVALID,
                "invalid skill identity accepted");
        state.trySpend(player, "test:skill", 20, 0, 100);
        h.assertValueEqual(state.trySpend(player, "test:other", 0, 0, 99), WeaponSkillState.Result.INVALID,
                "backward clock bypassed same-tick guard");
        h.assertTrue(!state.regenerate(player, 99), "backward clock regenerated mana");
        h.assertTrue(state.account(player).orElseThrow().mana() == 180, "invalid requests altered mana");
        h.succeed();
    }

    @GameTest(templateNamespace = "mythictrpg_weapon_skills", template = "empty")
    public static void dynamicMaximumPreservesFractionalRegenerationAndDoesNotRefillOnSwap(GameTestHelper h) {
        var state=new WeaponSkillState();var player=UUID.randomUUID();
        state.trySpend(player,"test:initial",100,0,1);
        state.clamp(player,333.3);
        h.assertTrue(state.account(player).orElseThrow().mana()==100,"equipping maximum mana refilled current mana");
        for(long tick=2;tick<22;tick++)state.regenerate(player,tick,333.3);
        h.assertTrue(state.account(player).orElseThrow().mana()==103.333,"one second did not recover exactly one percent of fractional maximum");
        var saved=state.save(new CompoundTag(),h.getLevel().registryAccess());
        var restored=WeaponSkillState.load(saved,h.getLevel().registryAccess());
        restored.regenerate(player,22,333.3);
        h.assertTrue(restored.account(player).orElseThrow().mana()==103.49965,"fractional recovery or carry changed after load");
        restored.refund(player,1000,333.3);
        h.assertTrue(restored.account(player).orElseThrow().mana()==333.3,"refund did not cap at current maximum");
        restored.clamp(player,200);
        h.assertTrue(restored.account(player).orElseThrow().mana()==200,"removing maximum mana accessory did not clamp current mana");
        restored.clamp(player,400);
        h.assertTrue(restored.account(player).orElseThrow().mana()==200,"re-equipping accessory restored discarded excess mana");
        h.assertValueEqual(restored.canUse(player,"test:large",250,0,23),WeaponSkillState.Result.NO_MANA,"authorable cost above 200 was treated as invalid");
        var precision=new WeaponSkillState();var fractional=UUID.randomUUID();precision.trySpend(fractional,"test:empty",200,0,0);
        precision.regenerate(fractional,1,333.333333);
        var withCarry=WeaponSkillState.load(precision.save(new CompoundTag(),h.getLevel().registryAccess()),h.getLevel().registryAccess());
        withCarry.regenerate(fractional,2,333.333333);
        h.assertTrue(withCarry.account(fractional).orElseThrow().mana()==0.333333,"sub-micro regeneration carry was discarded on storage reload");
        h.succeed();
    }

    @GameTest(templateNamespace = "mythictrpg_weapon_skills", template = "empty")
    public static void versionOneMigrationRetainsManaAndCooldownAndSelectedCooldownReductionPersists(GameTestHelper h) {
        UUID player=UUID.randomUUID();var old=new CompoundTag();old.putInt("dataVersion",1);var players=new ListTag();var row=new CompoundTag();
        row.putUUID("playerId",player);row.putInt("manaUnits",1234);row.putLong("lastUseTick",10);row.putLong("lastRegenTick",11);
        var cooldowns=new ListTag();var cooldown=new CompoundTag();cooldown.putString("skillId","test:old");cooldown.putLong("endTick",110);cooldowns.add(cooldown);row.put("cooldowns",cooldowns);players.add(row);old.put("players",players);
        var migrated=WeaponSkillState.load(old,h.getLevel().registryAccess());
        h.assertTrue(migrated.isReady()&&migrated.account(player).orElseThrow().mana()==123.4,"legacy mana was reset instead of migrated");
        h.assertTrue(migrated.account(player).orElseThrow().remainingTicks("test:old",20)==90,"legacy cooldown was lost");
        var fullLegacy=old.copy();fullLegacy.getList("players",Tag.TAG_COMPOUND).getCompound(0).putInt("manaUnits",2000);
        var fullMigrated=WeaponSkillState.load(fullLegacy,h.getLevel().registryAccess());
        h.assertTrue(fullMigrated.isReady()&&fullMigrated.account(player).orElseThrow().mana()==200,"version-one maximum must remain 200 mana independent of new-account tuning");
        var invalidLegacy=old.copy();invalidLegacy.getList("players",Tag.TAG_COMPOUND).getCompound(0).putInt("manaUnits",2001);
        assertRejected(h,invalidLegacy,player);
        migrated.select(player,"test:learned");
        h.assertTrue(migrated.reduceCooldown(player,"test:old",10,20)==10,"cooldown reduction did not use remaining active deadline");
        h.assertTrue(migrated.reduceCooldown(player,"test:inactive",10,20)==0,"reduction created an inactive cooldown");
        var encoded=migrated.save(new CompoundTag(),h.getLevel().registryAccess());
        var again=WeaponSkillState.load(encoded,h.getLevel().registryAccess());
        h.assertTrue(encoded.getInt("dataVersion")==2&&again.selected(player).orElseThrow().equals("test:learned")
                &&again.account(player).orElseThrow().remainingTicks("test:old",20)==80,"migration selection or reduced cooldown failed round trip");
        h.succeed();
    }

    private static void assertRejected(GameTestHelper h, CompoundTag encoded, UUID player) {
        var rejected = WeaponSkillState.load(encoded, h.getLevel().registryAccess());
        h.assertTrue(!rejected.isReady() && rejected.account(player).isEmpty(), "corrupt state became a fresh account");
        h.assertValueEqual(rejected.trySpend(player, "test:skill", 0, 0, 2), WeaponSkillState.Result.UNAVAILABLE,
                "corrupt state allowed skill use");
        h.assertTrue(!rejected.regenerate(player, 2), "corrupt state regenerated");
        h.assertValueEqual(rejected.save(new CompoundTag(), h.getLevel().registryAccess()), encoded,
                "original corrupt storage was overwritten");
    }
}

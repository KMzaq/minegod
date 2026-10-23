package com.sande.mythictrpg.accessory;

import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.sande.mythictrpg.quest.reward.ProgressionRewardEntry;
import com.sande.mythictrpg.quest.reward.RewardClaim;
import com.sande.mythictrpg.quest.reward.RewardClaimState;
import com.sande.mythictrpg.quest.reward.RewardOrigin;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@GameTestHolder("mythictrpg_accessories")
@PrefixGameTestTemplate(false)
public final class AccessoryAppearanceGameTests {
    private static final ResourceLocation MANA=ResourceLocation.parse("reuse_demo:mana_pendant");
    private static final ResourceLocation LINK=ResourceLocation.parse("reuse_demo:link_pendant");
    private AccessoryAppearanceGameTests() { }

    @GameTest(templateNamespace="mythictrpg_accessories",template="empty",batch="accessory_appearance")
    public static void sharedAppearanceDefinitionsKeepTheirOwnNameCosmeticsAndEffects(GameTestHelper h) {
        var mana=AccessoryDefinitions.INSTANCE.find(MANA).orElseThrow();
        var link=AccessoryDefinitions.INSTANCE.find(LINK).orElseThrow();
        var first=mana.displayStack(h.getLevel().registryAccess());var second=link.displayStack(h.getLevel().registryAccess());
        h.assertTrue(first.is(RewardAccessoryItems.AMETHYST_PENDANT.get())&&second.is(first.getItem()),"Shared texture selected different physical item types");
        h.assertTrue(first.getHoverName().getString().equals(mana.name())&&second.getHoverName().getString().equals(link.name())
                        &&!mana.name().equals(link.name()),"Definition names were lost or inferred from the common item type");
        h.assertTrue(first.getOrDefault(DataComponents.ENCHANTMENT_GLINT_OVERRIDE,false)
                        &&!second.getOrDefault(DataComponents.ENCHANTMENT_GLINT_OVERRIDE,true),"Per-definition glint was not applied");
        h.assertTrue(first.get(DataComponents.LORE).lines().getFirst().getString().contains("최대 마나"),"Authored mana lore was not decoded");
        h.assertTrue(!first.get(DataComponents.LORE).equals(second.get(DataComponents.LORE))&&first.getRarity()==Rarity.RARE,
                "Shared appearance collapsed authored lore or rarity");
        h.assertTrue(mana.effect(AccessoryEffect.Kind.MAX_MANA).orElseThrow().amount()==40
                        &&link.effect(AccessoryEffect.Kind.LINK_KNOT).orElseThrow().amount()==.1
                        &&mana.fixedPower()==12&&link.fixedPower()==12,"Appearance changes modified authored gameplay values");
        h.assertTrue(!first.has(DataComponents.CUSTOM_DATA)&&!second.has(DataComponents.CUSTOM_DATA),"A display-only example manufactured reward ownership");
        h.succeed();
    }

    @GameTest(templateNamespace="mythictrpg_accessories",template="empty",batch="accessory_appearance")
    public static void cosmeticWhitelistValidatesValuesAndRejectsGameplayOrOwnershipComponents(GameTestHelper h) {
        var valid=definition("""
                {"minecraft:lore":["{\\"text\\":\\"Cosmetic fixture\\"}"],
                 "minecraft:enchantment_glint_override":true,"minecraft:rarity":"epic","minecraft:custom_model_data":41}
                """);
        var stack=valid.displayStack(h.getLevel().registryAccess());
        h.assertTrue(stack.is(RewardAccessoryItems.AMETHYST_PENDANT.get())&&stack.get(DataComponents.CUSTOM_MODEL_DATA).value()==41
                        &&stack.getRarity()==Rarity.EPIC,"Allowed cosmetic components were discarded");
        for(String forbidden:List.of("minecraft:custom_data","minecraft:custom_name","minecraft:item_name","minecraft:attribute_modifiers",
                "minecraft:enchantments","minecraft:stored_enchantments","minecraft:food","minecraft:tool","minecraft:max_stack_size",
                "!minecraft:lore","unknown:skill_id")) {
            boolean rejected=false;
            try { definition("{\""+forbidden+"\":{}}"); } catch(IllegalArgumentException expected) { rejected=true; }
            h.assertTrue(rejected,"Unsupported component accepted: "+forbidden);
        }
        for(String malformed:List.of("{\"minecraft:rarity\":\"mythical\"}","{\"minecraft:lore\":1}",
                "{\"minecraft:enchantment_glint_override\":\"yes\"}","{\"minecraft:custom_model_data\":\"large\"}")) {
            boolean rejected=false;
            try { definition(malformed); } catch(IllegalArgumentException expected) { rejected=true; }
            h.assertTrue(rejected,"Invalid cosmetic value was deferred until reward execution: "+malformed);
        }
        h.succeed();
    }

    @GameTest(templateNamespace="mythictrpg_accessories",template="empty",batch="accessory_appearance")
    public static void canonicalRewardReissueAndEquipmentUseTheSameCosmeticsWithoutMixingOwnership(GameTestHelper h) {
        try(var f=new Fixture(h)) {
            UUID mana=f.grant(MANA),link=f.grant(LINK);
            ItemStack expectedMana=RewardAccessoryItems.stack(f.player,mana);
            ItemStack expectedLink=RewardAccessoryItems.stack(f.player,link);
            h.assertTrue(expectedMana.is(expectedLink.getItem())&&!data(expectedMana).getUUID("accessoryEntitlement")
                    .equals(data(expectedLink).getUUID("accessoryEntitlement")),"Shared appearance reused one entitlement for two definitions");
            h.assertTrue(expectedMana.get(DataComponents.LORE).equals(AccessoryDefinitions.INSTANCE.find(MANA).orElseThrow()
                    .displayStack(f.player.registryAccess()).get(DataComponents.LORE)),"Delivered token disagreed with the management preview");
            f.player.getInventory().clearContent();
            h.assertTrue(RewardAccessoryItems.restore(f.player,mana),"Missing owned example could not be restored");
            var restored=f.find(mana);
            h.assertTrue(ItemStack.isSameItemSameComponents(expectedMana,restored),"Restore discarded authored cosmetic components or changed ownership");
            int before=f.player.getInventory().countItem(expectedMana.getItem());
            RewardAccessoryItems.restore(f.player,mana);
            h.assertTrue(f.player.getInventory().countItem(expectedMana.getItem())==before,"Reissue duplicated the same represented entitlement");
            var state=AccessoryState.get(f.player.server);long now=System.currentTimeMillis();
            h.assertTrue(state.trigger(f.player.getUUID(),mana,AccessoryEffect.Kind.CLEANSE_CC,now,300000),"Cooldown fixture failed");
            h.assertTrue(AccessoryInventory.legacyEquip(f.player,0,mana)&&AccessoryInventory.legacyEquip(f.player,1,link),"Shared-appearance owned variants did not equip independently");
            h.assertTrue(AccessoryRuntime.maximumMana(f.player)==240&&AccessoryRuntime.bonus(f.player,AccessoryEffect.Kind.LINK_KNOT)==.1,
                    "Effects were inferred from the shared physical appearance instead of distinct definitions");
            h.assertTrue(state.remainingMillis(f.player.getUUID(),mana,AccessoryEffect.Kind.CLEANSE_CC,now)==300000,
                    "Appearance reconstruction or equipment reset entitlement cooldowns");
            var projected=f.player.inventoryMenu.getSlot(AccessoryInventory.slotIndex(f.player.inventoryMenu,0)).getItem();
            h.assertTrue(ItemStack.isSameItemSameComponents(expectedMana,projected),"E equipment projection omitted authored cosmetics");
            h.assertTrue(AccessoryRuntime.entitlements(f.player).size()==2,"Equipment or reissue fabricated progression rights");
        }
        h.succeed();
    }

    @GameTest(templateNamespace="mythictrpg_accessories",template="empty",batch="accessory_appearance")
    public static void legacyBoundTokenIsAcceptedButAnAlteredDefinitionCannotBorrowASharedAppearance(GameTestHelper h) {
        try(var f=new Fixture(h)) {
            UUID mana=f.grant(MANA);f.grant(LINK);
            var canonical=RewardAccessoryItems.stack(f.player,mana);var forged=canonical.copy();
            var wrong=data(forged);wrong.putString("accessoryDefinition",LINK.toString());forged.set(DataComponents.CUSTOM_DATA,CustomData.of(wrong));
            f.player.inventoryMenu.setCarried(forged);f.click(0);
            h.assertTrue(AccessoryState.get(f.player.server).equipped(f.player.getUUID(),0)==null,"Same item appearance bypassed entitlement-to-definition matching");
            ItemStack legacy=new ItemStack(RewardAccessoryItems.TOKEN.get());legacy.set(DataComponents.CUSTOM_DATA,canonical.get(DataComponents.CUSTOM_DATA));
            f.player.inventoryMenu.setCarried(legacy);f.click(0);
            h.assertTrue(mana.equals(AccessoryState.get(f.player.server).equipped(f.player.getUUID(),0)),"Previous bound_accessory token was rejected after cosmetic support");
            f.player.inventoryMenu.setCarried(ItemStack.EMPTY);f.click(0);
            h.assertTrue(ItemStack.isSameItemSameComponents(canonical,f.player.inventoryMenu.getCarried()),"Removing a legacy token did not reconstruct the authored canonical appearance");
            var fallback=new AccessoryDefinition(ResourceLocation.parse("test:legacy_display"),"Legacy display",ResourceLocation.parse("minecraft:amethyst_shard"),
                    new CompoundTag(),12,List.of(new AccessoryEffect(AccessoryEffect.Kind.MAX_MANA,40,0,0)));
            h.assertTrue(fallback.displayStack(f.player.registryAccess()).is(RewardAccessoryItems.TOKEN.get()),"Legacy display no longer matches its canonical bound token fallback");
        }
        h.succeed();
    }

    private static AccessoryDefinition definition(String components) {
        String json="{\"schemaVersion\":1,\"name\":\"Cosmetic fixture\",\"itemId\":\"mythictrpg:amethyst_pendant\",\"components\":"+components
                +",\"fixedPower\":12,\"effects\":[{\"type\":\"MAX_MANA\",\"amount\":40}]}";
        return AccessoryDefinition.parse(ResourceLocation.parse("test:cosmetic_fixture"),JsonParser.parseString(json).getAsJsonObject());
    }
    private static CompoundTag data(ItemStack stack){return stack.getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag();}
    private static final class Fixture implements AutoCloseable {
        final GameTestHelper h;final ServerPlayer player;final EmbeddedChannel channel;
        Fixture(GameTestHelper h) {
            this.h=h;var cookie=net.minecraft.server.network.CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(),"AppearanceOwner"),false);
            player=new ServerPlayer(h.getLevel().getServer(),h.getLevel(),cookie.gameProfile(),cookie.clientInformation());
            var connection=new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);channel=new EmbeddedChannel(connection);
            net.neoforged.neoforge.network.registration.NetworkRegistry.configureMockConnection(connection);
            player.server.getPlayerList().placeNewPlayer(connection,player,cookie);player.setGameMode(GameType.SURVIVAL);player.setNoGravity(true);
        }
        UUID grant(ResourceLocation definition) {
            var state=RewardClaimState.get(player.server);var source=ResourceLocation.parse("test:appearance_reward/"+UUID.randomUUID());
            var claim=state.create(new RewardClaim(UUID.randomUUID(),player.getUUID(),ResourceLocation.parse("test:appearance_fixture_source"),source,"Appearance fixture",
                    List.of(new ProgressionRewardEntry(ProgressionRewardEntry.Kind.ACCESSORY,definition)),List.of(),false,Optional.empty(),
                    player.server.overworld().getGameTime(),RewardOrigin.LEGACY_UNSPECIFIED));
            state.markAutomaticGranted(claim.claimId(),player.server.overworld().getGameTime());
            UUID right=state.progressionFor(player.getUUID()).stream().filter(entry->entry.sourceId().equals(source)).findFirst().orElseThrow().entitlementId();
            h.assertTrue(RewardAccessoryItems.restore(player,right),"Committed appearance reward failed to restore");return right;
        }
        ItemStack find(UUID id) {
            for(int slot=0;slot<player.getInventory().getContainerSize();slot++) {
                var stack=player.getInventory().getItem(slot);var custom=data(stack);
                if(custom.hasUUID("accessoryEntitlement")&&custom.getUUID("accessoryEntitlement").equals(id))return stack;
            }
            throw new IllegalStateException("Missing restored appearance token");
        }
        void click(int logical){player.inventoryMenu.clicked(AccessoryInventory.slotIndex(player.inventoryMenu,logical),0,ClickType.PICKUP,player);}
        @Override public void close(){player.inventoryMenu.setCarried(ItemStack.EMPTY);player.server.getPlayerList().remove(player);channel.finishAndReleaseAll();}
    }
}

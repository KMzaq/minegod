package com.sande.mythictrpg.prototype;

import com.sande.mythictrpg.accessory.RewardAccessoryItems;
import com.sande.mythictrpg.power.CombatPowerPolicyManager;
import com.sande.mythictrpg.power.RewardPowerState;
import com.sande.mythictrpg.quest.reward.NpcRewardEntry;
import com.sande.mythictrpg.quest.reward.ProgressionRewardEntry;
import com.sande.mythictrpg.quest.reward.RewardClaim;
import com.sande.mythictrpg.quest.reward.RewardClaimState;
import com.sande.mythictrpg.quest.reward.RewardEntry;
import com.sande.mythictrpg.quest.reward.RewardExecutionService;
import com.sande.mythictrpg.quest.reward.RewardGrantPurpose;
import com.sande.mythictrpg.quest.reward.RewardOrigin;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** OP-only development provisioning. The technical receipt source is not a registered NPC. */
public final class PrototypeGrantService {
    public static final ResourceLocation TECHNICAL_SOURCE = PrototypeCatalog.id("prototype_testing");

    public record Result(boolean succeeded, int delivered, String message) { }
    private record Product(ResourceLocation id, ResourceLocation source, RewardEntry reward) { }

    private PrototypeGrantService() { }

    public static Result grantKit(CommandSourceStack source) { return grantProducts(source, products()); }

    /** An accessory request restores its two independently receipted prototype entitlements. */
    public static Result grant(CommandSourceStack source, ResourceLocation id) {
        var selected = products().stream().filter(product -> product.id().equals(id)).toList();
        return selected.isEmpty() ? reject("시제품 목록에 없는 ID입니다.") : grantProducts(source, selected);
    }

    public static List<ResourceLocation> grantableIds() { return products().stream().map(Product::id).distinct().toList(); }

    /** Reuses the same receipt, acquisition and restore transaction for authored appearance examples. */
    public static Result grantEquipment(CommandSourceStack source, ResourceLocation id) {
        var selected = equipmentProducts().stream().filter(product -> product.id().equals(id)).toList();
        if (selected.isEmpty()) return reject("등록된 장비·장신구 정의가 아닙니다.");
        if (selected.size()!=1) return reject("무기와 장신구에 같은 정의 ID가 등록되어 있습니다. ID를 구분해 주세요.");
        return grantProducts(source, selected);
    }
    public static Result grantEquipmentExamples(CommandSourceStack source) {
        return grantEquipmentExamples(source, "reuse_demo");
    }
    public static Result grantEquipmentExamples(CommandSourceStack source, String namespace) {
        if (!namespace.equals("reuse_demo") && !namespace.equals("swords_demo")) return reject("등록된 시험 묶음이 아닙니다.");
        var examples = equipmentProducts().stream().filter(product -> product.id().getNamespace().equals(namespace)).toList();
        if (examples.stream().map(Product::id).distinct().count()!=examples.size())
            return reject("시험 장비·장신구의 정의 ID가 중복되어 있습니다.");
        return examples.isEmpty() ? reject(namespace.equals("swords_demo")
                ? "Simply Swords와 Better Combat이 설치되어 있어야 이 시험 무기를 지급할 수 있습니다."
                : "외형 재사용 예시가 등록되지 않았습니다.") : grantProducts(source, examples);
    }
    public static List<ResourceLocation> equipmentIds() { return equipmentProducts().stream().map(Product::id).distinct().toList(); }
    private static List<Product> equipmentProducts() {
        List<Product> result = new ArrayList<>();
        for (var definition : com.sande.mythictrpg.equipment.EquipmentCatalog.all())
            result.add(equipmentProduct(definition.id(), definition.reward(), "weapon"));
        for (var definition : com.sande.mythictrpg.accessory.AccessoryDefinitions.INSTANCE.snapshot().values())
            result.add(equipmentProduct(definition.id(), new ProgressionRewardEntry(ProgressionRewardEntry.Kind.ACCESSORY, definition.id()), "accessory"));
        return result.stream().sorted(java.util.Comparator.comparing(product -> product.id().toString())).toList();
    }
    private static Product equipmentProduct(ResourceLocation id, RewardEntry reward, String kind) {
        return new Product(id, ResourceLocation.fromNamespaceAndPath("mythictrpg", "equipment_testing/" + kind + "/" + id.getNamespace() + "/" + id.getPath()), reward);
    }

    private static List<Product> products() {
        List<Product> result = new ArrayList<>();
        for (var item : PrototypeCatalog.items()) result.add(new Product(item.id(), source("item/" + item.id().getPath()), item.reward()));
        result.add(new Product(PrototypeCatalog.SHORT_DODGE, source("learned/short_dodge"),
                new ProgressionRewardEntry(ProgressionRewardEntry.Kind.LEARNED, PrototypeCatalog.SHORT_DODGE)));
        for (var id : List.of(PrototypeCatalog.CLEAR_MANA_STONE, PrototypeCatalog.BLADE_KNOT))
            for (int i = 1; i <= 2; i++) result.add(new Product(id, source("accessory/" + id.getPath() + "/copy_" + i),
                    new ProgressionRewardEntry(ProgressionRewardEntry.Kind.ACCESSORY, id)));
        return List.copyOf(result);
    }

    private static ResourceLocation source(String suffix) { return PrototypeCatalog.id("prototype_testing/" + suffix); }

    private static Result grantProducts(CommandSourceStack source, List<Product> products) {
        if (!source.hasPermission(2)) return reject("운영자 권한 2 이상이 필요한 시제품 개발 명령입니다.");
        if (!(source.getEntity() instanceof ServerPlayer player) || source.getServer() != player.server
                || !player.server.isSameThread() || player.server.getPlayerList().getPlayer(player.getUUID()) != player)
            return reject("접속 중인 플레이어 본인만 시제품을 받을 수 있습니다.");
        var claims = RewardClaimState.get(player.server);
        if (!claims.isWritable() || !RewardPowerState.get(player.server).ready())
            return reject("보상 또는 획득 이력 저장소를 사용할 수 없습니다. 기존 기록을 보존합니다.");

        // Check the whole request first. Never author an NPC/rank or accept arbitrary reward entries here.
        int requiredSlots = 0;
        List<RewardEntry> newProgression = new ArrayList<>();
        for (var product : products) {
            var validation = RewardExecutionService.validateGrant(player, List.of(product.reward()), RewardGrantPurpose.QUEST);
            if (!validation.allowed()) return reject("시제품 정의 확인 실패: " + validation.reason());
            String error = policyError(player, product);
            if (error != null) return reject(error);
            var receipt = claims.findBySource(player.getUUID(), product.source()).orElse(null);
            if (receipt != null && (!receipt.godId().equals(TECHNICAL_SOURCE)
                    || !receipt.automaticRewards().equals(List.of(product.reward())) || !receipt.choices().isEmpty()))
                return reject("기존 시제품 영수증과 현재 정의가 다릅니다: " + product.id());
            if (!owned(player, product, receipt)) {
                if (!claims.canCreate(player.getUUID(), product.source())) return reject("시제품 영수증 저장 공간이 부족합니다.");
                if (product.reward() instanceof ProgressionRewardEntry) newProgression.add(product.reward());
            }
            if (needsPhysicalItem(player, product)) requiredSlots++;
        }
        if (!claims.canGrantProgression(newProgression)) return reject("습득 권리 저장 공간이 부족합니다.");
        long emptySlots = player.getInventory().items.stream().filter(ItemStack::isEmpty).count();
        if (emptySlots < requiredSlots)
            return reject("인벤토리에 빈 칸 " + requiredSlots + "개가 필요합니다. 아이템은 바닥에 떨어뜨리지 않습니다.");

        int delivered = 0;
        for (var product : products) {
            var receipt = claims.findBySource(player.getUUID(), product.source()).orElse(null);
            boolean wasOwned = owned(player, product, receipt);
            if (!wasOwned) {
                if (receipt == null) {
                    UUID claimId = UUID.nameUUIDFromBytes((player.getUUID() + "|" + product.source()).getBytes(StandardCharsets.UTF_8));
                    receipt = claims.create(new RewardClaim(claimId, player.getUUID(), TECHNICAL_SOURCE, product.source(),
                            "전투 1차 시제품 개발 지급", List.of(product.reward()), List.of(), false, Optional.empty(),
                            player.server.overworld().getGameTime(), RewardOrigin.LEGACY_UNSPECIFIED));
                }
                // Same claim transaction owns the progression entitlement before the ordinary executor runs.
                claims.markAutomaticGranted(receipt.claimId(), player.server.overworld().getGameTime());
                var result = RewardExecutionService.grant(player, TECHNICAL_SOURCE, List.of(product.reward()), RewardGrantPurpose.QUEST);
                if (!result.granted()) return new Result(false, delivered, "시제품 지급 실패: " + result.reason());
                delivered++;
                continue;
            }
            if (!needsPhysicalItem(player, product)) continue;
            if (product.reward() instanceof ProgressionRewardEntry progression) {
                var right = entitlement(player, product).orElseThrow();
                if (progression.kind() != ProgressionRewardEntry.Kind.ACCESSORY || !RewardAccessoryItems.restore(player, right.entitlementId()))
                    return new Result(false, delivered, "시제품 장신구 복구에 실패했습니다. 기존 권리는 보존되며 다시 시도할 수 있습니다.");
            } else {
                var result = RewardExecutionService.grant(player, TECHNICAL_SOURCE, List.of(product.reward()), RewardGrantPurpose.QUEST);
                if (!result.granted()) return new Result(false, delivered, "시제품 지급 실패: " + result.reason());
            }
            delivered++;
        }
        return new Result(true, delivered, delivered == 0 ? "시제품을 이미 보유 중입니다. 권리와 쿨타임을 유지했습니다."
                : "시제품 " + delivered + "개를 지급 또는 복구했습니다. 기존 권리와 쿨타임은 유지됩니다.");
    }

    private static String policyError(ServerPlayer player, Product product) {
        if (!(product.reward() instanceof NpcRewardEntry item)) return null;
        var loaded = CombatPowerPolicyManager.INSTANCE.current();
        if (loaded.policy() == null) return "전투력 정책이 준비되지 않았습니다: " + loaded.reason();
        try {
            var bound=loaded.policy().items().stream().filter(rule->rule.itemId().equals(item.itemId())&&rule.components().equals(item.components())).toList();
            for(var rule:bound)if(rule.rightClickSkill()!=null
                    &&com.sande.mythictrpg.weaponskill.WeaponSkillDefinitions.INSTANCE.find(rule.rightClickSkill()).isEmpty())
                return "장비에 연결된 무기 스킬이 없습니다: "+rule.rightClickSkill();
            var gear = loaded.policy().stateGear(List.of(RewardPowerState.identity(item.createStack(player.registryAccess()), player.registryAccess())), player.registryAccess());
            return gear.size() == 1 ? null : "시제품이 현행 전투력 정책에 등록되지 않았습니다: " + product.id();
        } catch (RuntimeException invalid) { return "시제품 변형과 현행 전투력 정책이 일치하지 않습니다: " + product.id(); }
    }

    private static boolean owned(ServerPlayer player, Product product, RewardClaim receipt) {
        if (product.reward() instanceof ProgressionRewardEntry) return entitlement(player, product).isPresent();
        if (receipt != null && receipt.automaticGranted()) return true;
        var item = (NpcRewardEntry) product.reward();
        var identity = RewardPowerState.identity(item.createStack(player.registryAccess()), player.registryAccess());
        return RewardPowerState.get(player.server).history(player.getUUID()).map(history -> history.items().contains(identity)).orElse(false);
    }

    private static Optional<RewardClaimState.ProgressionEntitlement> entitlement(ServerPlayer player, Product product) {
        if (!(product.reward() instanceof ProgressionRewardEntry progression)) return Optional.empty();
        return RewardClaimState.get(player.server).progressionFor(player.getUUID()).stream()
                .filter(right -> right.sourceId().equals(product.source()) && right.godId().equals(TECHNICAL_SOURCE)
                        && right.kind() == progression.kind() && right.definitionId().equals(progression.definitionId())).findFirst();
    }

    private static boolean needsPhysicalItem(ServerPlayer player, Product product) {
        if (product.reward() instanceof ProgressionRewardEntry progression) {
            if (progression.kind() != ProgressionRewardEntry.Kind.ACCESSORY) return false;
            var right = entitlement(player, product).orElse(null);
            if (right == null) return true;
            return !com.sande.mythictrpg.accessory.AccessoryInventory.represented(player, right.entitlementId());
        }
        var expected = ((NpcRewardEntry) product.reward()).createStack(player.registryAccess());
        // An unreceipted matching stack cannot stand in for a real personal acquisition.
        var receipt = RewardClaimState.get(player.server).findBySource(player.getUUID(), product.source()).orElse(null);
        if (!owned(player, product, receipt)) return true;
        var carried = player.inventoryMenu.getCarried().copyWithCount(1);
        if (!carried.isEmpty() && carried.isDamageableItem()) carried.setDamageValue(0);
        if (ItemStack.isSameItemSameComponents(expected, carried)) return false;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            var stack = player.getInventory().getItem(i);
            if (stack.isEmpty()) continue;
            var normalized = stack.copyWithCount(1);
            if (normalized.isDamageableItem()) normalized.setDamageValue(0);
            if (ItemStack.isSameItemSameComponents(expected, normalized)) return false;
        }
        return true;
    }

    private static Result reject(String message) { return new Result(false, 0, message); }
}

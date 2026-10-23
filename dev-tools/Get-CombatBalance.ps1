param([switch]$Json, [switch]$CheckOnly)

# Read source defaults only. Does not write files, load a world, or change a running server.
$ErrorActionPreference = 'Stop'
if ($Json -and $CheckOnly) { throw 'Choose either -Json or -CheckOnly.' }
$balanceRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$resourceRoot = 'mythictrpg-main/src/main/resources'
$prototypeData = "$resourceRoot/data/proto_cbt/mythictrpg"
$policyPath = "$resourceRoot/data/mythictrpg/mythictrpg/combat_power/default.json"
$manaPath = 'mythictrpg-main/src/main/java/com/sande/mythictrpg/skills/ManaTuning.java'

function Read-BalanceJson([string]$RelativePath) {
    return Get-Content -LiteralPath (Join-Path $balanceRoot $RelativePath) -Raw -Encoding UTF8 | ConvertFrom-Json
}
function Read-ManaConstant([string]$Name) {
    $source = [IO.File]::ReadAllText((Join-Path $balanceRoot $manaPath))
    $pattern = '\b(?:int|double)\s+' + [regex]::Escape($Name) + '\s*=\s*([0-9]+(?:\.[0-9]+)?)\s*;'
    $found = [regex]::Matches($source, $pattern)
    if ($found.Count -ne 1) { throw "Expected one numeric literal for $Name in $manaPath; update this reader if authoring changes." }
    return [double]::Parse($found[0].Groups[1].Value, [Globalization.CultureInfo]::InvariantCulture)
}

$skillRows = @(foreach ($kind in @('weapon_skills', 'learned_skills')) {
    foreach ($file in (Get-ChildItem -LiteralPath (Join-Path $balanceRoot "$prototypeData/$kind") -File -Filter '*.json' | Sort-Object Name)) {
        $path = "$prototypeData/$kind/$($file.Name)"
        $definition = Read-BalanceJson $path
        [pscustomobject][ordered]@{
            Id = "proto_cbt:$($file.BaseName)"; Kind = $kind; Name = $definition.name
            ManaCost = $definition.manaCost; CooldownTicks = $definition.cooldownTicks
            CooldownSeconds = $definition.cooldownTicks / 20.0; IntrinsicPower = $definition.intrinsicPower
            Action = $definition.action; Source = $path
        }
    }
})
$accessoryRows = @(foreach ($file in (Get-ChildItem -LiteralPath (Join-Path $balanceRoot "$prototypeData/accessories") -File -Filter '*.json' | Sort-Object Name)) {
    $path = "$prototypeData/accessories/$($file.Name)"
    $definition = Read-BalanceJson $path
    [pscustomobject][ordered]@{
        Id = "proto_cbt:$($file.BaseName)"; Name = $definition.name
        FixedPower = $definition.fixedPower; Effects = $definition.effects; Source = $path
    }
})
$policy = Read-BalanceJson $policyPath
$equipmentRows = @(foreach ($item in $policy.items) {
    $id = $item.components.'minecraft:custom_data'.prototype_id
    if (!$id -or !$id.StartsWith('proto_cbt:')) { continue }
    $modifiers = @($item.components.'minecraft:attribute_modifiers'.modifiers)
    $attack = @($modifiers | Where-Object { $_.type -eq 'minecraft:generic.attack_damage' })
    $speed = @($modifiers | Where-Object { $_.type -eq 'minecraft:generic.attack_speed' })
    $actualProtection = $item.components.'minecraft:enchantments'.levels.'minecraft:protection'
    $actualSharpness = $item.components.'minecraft:enchantments'.levels.'minecraft:sharpness'
    [pscustomobject][ordered]@{
        Id = $id; ItemId = $item.itemId; Slot = $item.slot; IntrinsicPower = $item.intrinsicPower
        PowerAttackDamage = $item.attackDamage; ActualAttackModifiers = $attack
        ActualSpeedModifiers = $speed; PowerProtectionLevel = $item.protectionLevel
        ActualProtectionLevel = [int]$actualProtection; PowerSharpnessLevel = $item.sharpnessLevel
        ActualSharpnessLevel = [int]$actualSharpness; Skills = $item.skills
        RightClickSkill = $item.rightClickSkill; Source = $policyPath
    }
})

# These are authoring consistency checks, not substitutes for the actual Java schema/behavior tests.
$seen = @{}
foreach ($item in $equipmentRows) {
    if ($seen.ContainsKey($item.Id)) { throw "Duplicate prototype equipment ID: $($item.Id)" }
    $seen[$item.Id] = $true
    if ($item.Slot -eq 'weapon') {
        if ($item.ActualAttackModifiers.Count -ne 1 -or $item.ActualAttackModifiers[0].operation -ne 'add_value' -or
                $item.ActualAttackModifiers[0].slot -ne 'mainhand') {
            throw "Unsupported prototype attack layout: $($item.Id). Review actual attributes and this check together."
        }
        if ([double]$item.PowerAttackDamage -ne [double]$item.ActualAttackModifiers[0].amount) {
            throw "Actual attack modifier differs from combat-power attackDamage: $($item.Id)"
        }
    }
    if ([int]$item.PowerProtectionLevel -ne $item.ActualProtectionLevel) {
        throw "Actual Protection enchantment differs from combat-power protectionLevel: $($item.Id)"
    }
    if ([int]$item.PowerSharpnessLevel -ne $item.ActualSharpnessLevel) {
        throw "Actual Sharpness enchantment differs from combat-power sharpnessLevel: $($item.Id)"
    }
    if ($item.RightClickSkill) {
        if (@($skillRows | Where-Object { $_.Kind -eq 'weapon_skills' -and $_.Id -eq $item.RightClickSkill }).Count -ne 1 -or
                !$item.Skills.PSObject.Properties[$item.RightClickSkill]) {
            throw "Missing right-click definition or power entry: $($item.Id) -> $($item.RightClickSkill)"
        }
    }
}
$baseMana = Read-ManaConstant 'BASE_MAX_MANA'
$regenPercent = Read-ManaConstant 'REGEN_PERCENT_PER_SECOND'
$report = [ordered]@{
    Scope = 'SOURCE_DEFAULTS_ONLY'; Units = '20 ticks = 1 second; effect fractions use 0..1, mana regen uses percent'
    Mana = [ordered]@{ BaseMaximum = $baseMana; RegenPercentPerSecond = $regenPercent; BaseRegenPerSecond = $baseMana * $regenPercent / 100; Source = $manaPath }
    Skills = $skillRows; Accessories = $accessoryRows; Equipment = $equipmentRows
    CombatPower = [ordered]@{ Source = $policyPath; Model = $policy.model }
}
if ($CheckOnly) {
    Write-Output "Combat balance source check: PASS ($($skillRows.Count) skills, $($accessoryRows.Count) accessories, $($equipmentRows.Count) equipment variants)."
    exit 0
}
if ($Json) { $report | ConvertTo-Json -Depth 20; exit 0 }
Write-Output 'SOURCE DEFAULTS - running-server values and datapack overrides are not inspected.'
Write-Output "Mana: base=$baseMana; regen=$regenPercent%/second; source=$manaPath"
Write-Output 'Skills (ticks; full action fields below):'
$skillRows | Select-Object Id, ManaCost, CooldownTicks, CooldownSeconds, IntrinsicPower | Format-Table -AutoSize
foreach ($skill in $skillRows) { Write-Output "$($skill.Id): $($skill.Action | ConvertTo-Json -Compress)"; Write-Output "  $($skill.Source)" }
Write-Output 'Accessories (effect fractions use 0..1):'
foreach ($accessory in $accessoryRows) { Write-Output "$($accessory.Id): fixedPower=$($accessory.FixedPower); effects=$($accessory.Effects | ConvertTo-Json -Compress)"; Write-Output "  $($accessory.Source)" }
Write-Output "Equipment (actual modifiers and evaluation values must agree): $policyPath"
$equipmentRows | Select-Object Id, Slot, IntrinsicPower, PowerAttackDamage, ActualProtectionLevel, RightClickSkill | Format-Table -AutoSize
Write-Output 'Power model (evaluation coefficients; see BALANCE_TUNING.md for combat effects):'
$policy.model | ConvertTo-Json -Depth 6

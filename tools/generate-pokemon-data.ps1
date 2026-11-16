#Requires -Version 7
<#
Builds app\src\main\assets\pokemon.json from the open PokeAPI CSV data.
Contains the 18 types, the type chart and every species' simplified Chinese name and types (base form only).
Run once from the project root:  pwsh -File .\tools\generate-pokemon-data.ps1
#>
$ErrorActionPreference = 'Stop'

$baseUrl = 'https://raw.githubusercontent.com/PokeAPI/pokeapi/master/data/v2/csv'
$simplifiedChinese = '12'
$output = Join-Path (Split-Path -Parent $PSScriptRoot) 'app\src\main\assets\pokemon.json'
$cache = Join-Path ([IO.Path]::GetTempPath()) 'pokeapi-csv'
New-Item -ItemType Directory -Force -Path $cache | Out-Null
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $output) | Out-Null

function Get-Csv([string]$name) {
    $file = Join-Path $cache $name
    Write-Host "下载 $name"
    Invoke-WebRequest -Uri "$baseUrl/$name" -OutFile $file
    Import-Csv -Path $file -Encoding UTF8
}

# Order defines the type index used by the app. Names must match the colors in ResultCardView.
$typeNames = [ordered]@{
    normal = '一般'; fire = '火'; water = '水'; electric = '电'; grass = '草'; ice = '冰'
    fighting = '格斗'; poison = '毒'; ground = '地面'; flying = '飞行'; psychic = '超能力'; bug = '虫'
    rock = '岩石'; ghost = '幽灵'; dragon = '龙'; dark = '恶'; steel = '钢'; fairy = '妖精'
}
$identifiers = [string[]]@($typeNames.Keys)
$count = $identifiers.Length

$typeIndexById = @{}
foreach ($type in Get-Csv 'types.csv') {
    $index = [array]::IndexOf($identifiers, $type.identifier)
    if ($index -ge 0) { $typeIndexById[[int]$type.id] = $index }
}
if ($typeIndexById.Count -ne $count) { throw "types.csv 中只找到 $($typeIndexById.Count) 个属性，预期 $count 个" }

$chart = [System.Collections.Generic.List[double[]]]::new()
for ($i = 0; $i -lt $count; $i++) {
    $row = [double[]]::new($count)
    [array]::Fill($row, 1.0)
    $chart.Add($row)
}
foreach ($entry in Get-Csv 'type_efficacy.csv') {
    $attacker = [int]$entry.damage_type_id
    $defender = [int]$entry.target_type_id
    if ($typeIndexById.ContainsKey($attacker) -and $typeIndexById.ContainsKey($defender))
    { $chart[$typeIndexById[$attacker]][$typeIndexById[$defender]] = [double]$entry.damage_factor / 100.0 }
}

$names = @{}
foreach ($row in Get-Csv 'pokemon_species_names.csv') {
    if ($row.local_language_id -eq $simplifiedChinese) { $names[[int]$row.pokemon_species_id] = $row.name }
}

$typesByPokemon = @{}
foreach ($row in (Get-Csv 'pokemon_types.csv' | Sort-Object { [int]$_.pokemon_id }, { [int]$_.slot })) {
    $pokemonId = [int]$row.pokemon_id
    if (-not $typeIndexById.ContainsKey([int]$row.type_id)) { continue }
    if (-not $typesByPokemon.ContainsKey($pokemonId)) { $typesByPokemon[$pokemonId] = [System.Collections.Generic.List[int]]::new() }
    $typesByPokemon[$pokemonId].Add($typeIndexById[[int]$row.type_id])
}

# Base stats in PokeAPI stat order: HP, attack, defense, special attack, special defense, speed.
$statsByPokemon = @{}
foreach ($row in Get-Csv 'pokemon_stats.csv') {
    $statId = [int]$row.stat_id
    if ($statId -lt 1 -or $statId -gt 6) { continue }
    $pokemonId = [int]$row.pokemon_id
    if (-not $statsByPokemon.ContainsKey($pokemonId)) { $statsByPokemon[$pokemonId] = [int[]]::new(6) }
    $statsByPokemon[$pokemonId][$statId - 1] = [int]$row.base_stat
}

$pokemon = [System.Collections.Generic.List[object]]::new()
$missing = [System.Collections.Generic.List[int]]::new()
foreach ($row in (Get-Csv 'pokemon.csv' | Where-Object { $_.is_default -eq '1' } | Sort-Object { [int]$_.species_id })) {
    $speciesId = [int]$row.species_id
    $pokemonId = [int]$row.id
    if (-not $names.ContainsKey($speciesId) -or -not $typesByPokemon.ContainsKey($pokemonId) -or -not $statsByPokemon.ContainsKey($pokemonId)) {
        $missing.Add($speciesId)
        continue
    }
    $pokemon.Add([pscustomobject]@{ id = $speciesId; name = $names[$speciesId]; types = [int[]]$typesByPokemon[$pokemonId]; stats = [int[]]$statsByPokemon[$pokemonId] })
}
if ($pokemon.Count -eq 0) { throw '没有生成任何宝可梦数据' }

$result = [pscustomobject]@{
    types = [string[]]@($typeNames.Values)
    chart = $chart
    pokemon = $pokemon
}
$json = $result | ConvertTo-Json -Depth 6 -Compress
[IO.File]::WriteAllText($output, $json, [Text.UTF8Encoding]::new($false))

Write-Host "已生成 $output（$($pokemon.Count) 只宝可梦）"
if ($missing.Count -gt 0) { Write-Warning "以下图鉴编号缺少简体中文名、属性或种族值，已跳过：$($missing -join ', ')" }

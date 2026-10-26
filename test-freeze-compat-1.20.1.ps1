param(
    [ValidateSet('forge', 'neoforge')][string]$Loader = 'neoforge',
    [ValidateSet('base', 'radium', 'observable', 'both')][string[]]$Scenarios = @('base', 'radium', 'observable', 'both')
)
$ErrorActionPreference = 'Stop'
$workspace = $PSScriptRoot
$testRoot = Join-Path $workspace 'build\freeze-compat'
$serverRoot = Join-Path $testRoot "production-$Loader"
$java = 'C:\Program Files\Eclipse Adoptium\jdk-17.0.17.10-hotspot\bin\java.exe'
if ($env:JAVA_HOME -match 'jdk-17' -and (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
    $java = Join-Path $env:JAVA_HOME 'bin\java.exe'
}
if ($Loader -eq 'forge') {
    $argumentsPath = 'libraries/net/minecraftforge/forge/1.20.1-47.3.0/win_args.txt'
} else {
    $argumentsPath = 'libraries/net/neoforged/forge/1.20.1-47.1.106/win_args.txt'
}
if (!(Test-Path -LiteralPath (Join-Path $serverRoot $argumentsPath))) {
    throw "Install the pinned $Loader server into $serverRoot first (see docs/freeze-compatibility-validation-1.20.1.md)."
}
$modsRoot = Join-Path $serverRoot 'mods'
New-Item -ItemType Directory -Force -Path $modsRoot | Out-Null
foreach ($artifact in @(
    'com.simibubi.create\create-1.20.1\6.0.8-291',
    'com.tterrag.registrate\Registrate\MC1.20-1.3.3',
    'net.createmod.ponder\Ponder-Forge-1.20.1\1.0.91',
    'dev.engine-room.flywheel\flywheel-forge-1.20.1\1.0.6-beta-266'
)) {
    $files = @(Get-ChildItem -LiteralPath (Join-Path $workspace ".gradle-user-home\caches\modules-2\files-2.1\$artifact") -Recurse -Filter '*.jar')
    if ($files.Count -ne 1) { throw "Expected exactly one cached runtime artifact for $artifact" }
    Copy-Item -LiteralPath $files[0].FullName -Destination $modsRoot -Force
}
$releaseVersion = (Get-Content -LiteralPath (Join-Path $workspace 'gradle.properties') |
    Where-Object { $_ -match '^mod_version=' }) -replace '^mod_version=', ''
if ([string]::IsNullOrWhiteSpace($releaseVersion)) { throw 'Missing mod_version in gradle.properties' }
# Only remove old CNF artifacts in this workspace's dedicated test server.
Get-ChildItem -LiteralPath $modsRoot -File | Where-Object {
    $_.Name -like 'create_nested_factory-1.20.1-*.jar' -or $_.Name -like 'cnf-freeze-compat-test-*-test-only.jar'
} | Remove-Item
foreach ($name in @("create_nested_factory-1.20.1-$releaseVersion.jar", "cnf-freeze-compat-test-$releaseVersion-test-only.jar")) {
    Copy-Item -LiteralPath (Join-Path $workspace "build\libs\$name") -Destination $modsRoot -Force
}
Set-Content -LiteralPath (Join-Path $serverRoot 'eula.txt') -Value 'eula=true'
Set-Content -LiteralPath (Join-Path $serverRoot 'server.properties') -Value @(
    'level-type=minecraft:flat', 'generate-structures=false', 'spawn-protection=0',
    'view-distance=2', 'simulation-distance=2', 'max-tick-time=120000',
    "server-port=$(if ($Loader -eq 'forge') {25586} else {25587})", 'online-mode=false', 'server-ip=127.0.0.1'
)
$optionalFiles = @('observable-4.4.2.jar', 'architectury-9.2.14-forge.jar',
                  'kotlinforforge-4.12.0-all.jar', 'radium-mc1.20.1-0.12.4+git.26c9d8e.jar')
$results = @()
foreach ($scenario in $Scenarios) {
    foreach ($name in $optionalFiles) {
        $path = Join-Path $modsRoot $name
        if (Test-Path -LiteralPath $path) { Remove-Item -LiteralPath $path }
    }
    $selected = @()
    if ($scenario -in @('observable', 'both')) { $selected += $optionalFiles[0..2] }
    if ($scenario -in @('radium', 'both')) { $selected += $optionalFiles[3] }
    foreach ($name in $selected) {
        Copy-Item -LiteralPath (Join-Path $testRoot "mods\$name") -Destination $modsRoot
    }
    $log = Join-Path $testRoot "production-$Loader-$scenario.log"
    Push-Location -LiteralPath $serverRoot
    try {
        & $java '-Xmx2G' '-Dcnf.freezeCompatTest=true' '-Dmixin.debug.export=true' "@$argumentsPath" '--nogui' *> $log
        $exitCode = $LASTEXITCODE
    } finally { Pop-Location }
    $passed = $exitCode -eq 0 -and (Select-String -LiteralPath $log -Pattern '\[CNF-FREEZE-TEST\] PASS:' -Quiet)
    $results += [PSCustomObject]@{loader=$Loader; scenario=$scenario; passed=$passed; exitCode=$exitCode; log=$log}
    $results | ConvertTo-Json -Depth 4 | Set-Content -Encoding UTF8 -LiteralPath (Join-Path $testRoot "production-$Loader-results.json")
    if (!$passed) { throw "Freeze compatibility test failed: $Loader / $scenario; see $log" }
    Write-Output "PASS: $Loader / $scenario"
}

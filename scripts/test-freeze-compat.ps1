param(
    [ValidateSet('base', 'lithium', 'observable', 'both')]
    [string[]]$Modes = @('base', 'lithium', 'observable', 'both'),
    [switch]$Offline
)

$ErrorActionPreference = 'Stop'
$projectDir = Split-Path -Parent $PSScriptRoot
$dependencyDir = Join-Path $projectDir 'build\freeze-compat-deps'
$headers = @{ 'User-Agent' = 'CreateNestedFactory-compat-verification/1.5.22' }
$artifacts = @(
    @{ Version = 'DDUrRVCA'; Name = 'lithium-neoforge-0.15.4+mc1.21.1.jar'; Sha1 = '97212e45c963730bbfc7c44780df99317814c21b'; Lithium = $true },
    @{ Version = 'f8lSH3bs'; Name = 'observable-5.4.4.jar'; Sha1 = 'e17417eee213aac8291ff8bad1c6a5f710636b72'; Lithium = $false },
    @{ Version = 'uhJhCT7X'; Name = 'kotlinforforge-5.12.0-all.jar'; Sha1 = 'd10d062caf1aad9aec82d7852f2fee1781735c1f'; Lithium = $false },
    @{ Version = '1IiqEQGl'; Name = 'architectury-13.0.11-neoforge.jar'; Sha1 = '008656a0702801174b8ec245ed7aad1921d6e9f1'; Lithium = $false }
)

New-Item -ItemType Directory -Path $dependencyDir -Force | Out-Null
foreach ($artifact in $artifacts) {
    $target = Join-Path $dependencyDir $artifact.Name
    if (!(Test-Path -LiteralPath $target)) {
        if ($Offline) { throw "Missing cached test dependency: $($artifact.Name)" }
        $version = Invoke-RestMethod -Headers $headers -Uri "https://api.modrinth.com/v2/version/$($artifact.Version)"
        $file = $version.files | Where-Object { $_.filename -eq $artifact.Name } | Select-Object -First 1
        if (!$file) { throw "Pinned dependency not found: $($artifact.Name)" }
        Invoke-WebRequest -Headers $headers -Uri $file.url -OutFile $target
    }
    if ((Get-FileHash -LiteralPath $target -Algorithm SHA1).Hash.ToLowerInvariant() -ne $artifact.Sha1) {
        throw "Test dependency checksum mismatch: $($artifact.Name)"
    }
}

$gradleArgs = @('--gradle-user-home', '.gradle-user-home', '--console=plain')
if ($Offline) {
    $gradleArgs += '--offline'
} else {
    # Java does not automatically use the Windows proxy used by Invoke-WebRequest.
    $probeUri = [Uri]'https://maven.neoforged.net'
    $proxyUri = [System.Net.WebRequest]::DefaultWebProxy.GetProxy($probeUri)
    if ($proxyUri -and $proxyUri.Host -ne $probeUri.Host) {
        $gradleArgs += @("-Dhttps.proxyHost=$($proxyUri.Host)", "-Dhttps.proxyPort=$($proxyUri.Port)",
                         "-Dhttp.proxyHost=$($proxyUri.Host)", "-Dhttp.proxyPort=$($proxyUri.Port)")
    }
}

Push-Location $projectDir
try {
    foreach ($mode in $Modes) {
        $runDir = Join-Path $projectDir "build\freeze-compat\$mode"
        $modsDir = Join-Path $runDir 'mods'
        New-Item -ItemType Directory -Path $modsDir -Force | Out-Null
        $expected = @($artifacts | Where-Object {
            ($_.Lithium -and $mode -in @('lithium', 'both')) -or
            (!$_.Lithium -and $mode -in @('observable', 'both'))
        })
        $unexpected = @(Get-ChildItem -LiteralPath $modsDir -Filter '*.jar' | Where-Object {
            $_.Name -notin $expected.Name
        })
        if ($unexpected.Count -gt 0) {
            throw "Unexpected mods in $modsDir; use a clean test directory: $($unexpected.Name -join ', ')"
        }
        foreach ($artifact in $expected) {
            Copy-Item -LiteralPath (Join-Path $dependencyDir $artifact.Name) -Destination $modsDir
        }
        $logPath = Join-Path $projectDir "build\freeze-compat-$mode.log"
        & .\gradlew.bat @gradleArgs "-PfreezeCompat=$mode" :runGameTestServer -x downloadAssets *> $logPath
        if ($LASTEXITCODE -ne 0) {
            Get-Content -LiteralPath $logPath -Tail 60
            throw "Freeze compatibility test failed: $mode (see $logPath)"
        }
        if (!(Select-String -LiteralPath $logPath -Pattern 'All \d+ required tests passed' -Quiet)) {
            throw "GameTest success marker missing: $mode (see $logPath)"
        }
        Write-Output "PASS: $mode (see $logPath)"
    }
} finally {
    Pop-Location
}

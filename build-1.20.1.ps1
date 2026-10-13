param([Parameter(ValueFromRemainingArguments = $true)][string[]]$GradleArguments = @('build'))
$ErrorActionPreference = 'Stop'
$env:GRADLE_USER_HOME = Join-Path $PSScriptRoot '.gradle-user-home'
$javaCandidates = @(
    $env:JAVA_HOME,
    'C:\Program Files\Eclipse Adoptium\jdk-17.0.17.10-hotspot'
) | Where-Object { $_ -and (Test-Path -LiteralPath (Join-Path $_ 'bin\java.exe')) }
$java17 = $javaCandidates | Where-Object { $_ -match 'jdk-17' } | Select-Object -First 1
if ($java17) { $env:JAVA_HOME = $java17 }
Push-Location -LiteralPath $PSScriptRoot
try {
    & .\gradlew.bat @GradleArguments --console=plain
    exit $LASTEXITCODE
} finally { Pop-Location }

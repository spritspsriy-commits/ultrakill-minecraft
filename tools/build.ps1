# Builds the Minecraft mod and the ULTRAKILL plugin, and installs both.
#   tools\build.ps1            build and install both
#   tools\build.ps1 -Only mc   just the Minecraft mod (or -Only uk)
#   tools\build.ps1 -NoInstall
# While maven.fabricmc.net can't be reached, the Minecraft mod builds from the Gradle cache and the Fabric API jar
# in mc\libs\fabric-api (see docs\HOW-IT-WORKS.md).
param(
    [ValidateSet('both', 'mc', 'uk')] [string] $Only = 'both',
    [switch] $NoInstall
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$mcGame = Join-Path $root 'game\minecraft'
$ukPlugins = Join-Path $env:APPDATA 'r2modmanPlus-local\ULTRAKILL\profiles\UK Passthrough\BepInEx\plugins\UKPassthrough'

if ($Only -ne 'uk') {
    if (-not $env:JAVA_HOME) { $env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-25.0.4.101-hotspot' }
    $gradleArgs = @('--console=plain', 'build')
    $fabricUp = $false
    try { $fabricUp = (Invoke-WebRequest -Uri 'https://maven.fabricmc.net/' -Method Head -TimeoutSec 8 -UseBasicParsing).StatusCode -lt 500 } catch { }
    if (-not $fabricUp) {
        Write-Host 'maven.fabricmc.net is unreachable: building from the Gradle cache (Loom 1.17.20, Loader 0.19.3)'
        $gradleArgs = @('-Ploom_version=1.17.20', '-Ploader_version=0.19.3', '-PlocalFabricApi',
            '-Dorg.gradle.internal.http.connectionTimeout=8000', '-Dorg.gradle.internal.http.socketTimeout=8000') + $gradleArgs
    }
    Push-Location (Join-Path $root 'mc')
    try {
        & .\gradlew.bat @gradleArgs
        if ($LASTEXITCODE -ne 0) { throw "Minecraft mod build failed ($LASTEXITCODE)" }
    } finally { Pop-Location }
    if (-not $NoInstall) {
        New-Item -ItemType Directory -Force (Join-Path $mcGame 'mods') | Out-Null
        Copy-Item (Join-Path $root 'mc\build\libs\ukpassthrough-0.1.0.jar') (Join-Path $mcGame 'mods') -Force
        Write-Host "installed the Minecraft mod in $mcGame\mods"
    }
}

if ($Only -ne 'mc') {
    dotnet build (Join-Path $root 'uk\UKPassthrough.csproj') -c Release -nologo -v q
    if ($LASTEXITCODE -ne 0) { throw "ULTRAKILL plugin build failed ($LASTEXITCODE)" }
    if (-not $NoInstall) {
        New-Item -ItemType Directory -Force $ukPlugins | Out-Null
        Copy-Item (Join-Path $root 'uk\bin\Release\UKPassthrough.dll') $ukPlugins -Force
        Write-Host "installed the ULTRAKILL plugin in $ukPlugins"
    }
}

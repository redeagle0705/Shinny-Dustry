# Builds everything with the JDK alone (no Gradle), compiling against the official v160.7 desktop jar (compile-only, not bundled).
#   build\mshaders.jar              the mod (classes + mod.hjson + shaders/ + bundles/)
#   build\mshaders-testkit.jar      screenshot helper, for the test profile only
#   build\exp1-water-override.zip   experiment 1: a mod that contains only shaders/water.frag
# Usage: powershell -ExecutionPolicy Bypass -File tools\build.ps1
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$paths = Join-Path $PSScriptRoot "local.ps1"
if (-not (Test-Path $paths)) { throw "tools\local.ps1 not found: copy tools\local.example.ps1 to tools\local.ps1 and put in your own paths" }
. $paths
$jdk = $LocalJdkBin
$gameJar = $LocalGameJar   # compile-only, never changed
$build = Join-Path $root "build"

# $meta: the mod.hjson to put into the jar; by default the one in the mod's own folder
function Build-JavaMod([string]$name, [string]$dir, [string]$meta = "") {
    if ($meta -eq "") { $meta = Join-Path $dir "mod.hjson" }
    $classes = Join-Path $build "classes-$name"
    if (Test-Path $classes) { Remove-Item -Recurse -Force $classes }
    New-Item -ItemType Directory -Force $classes | Out-Null
    $sources = @(Get-ChildItem -Recurse -Filter *.java (Join-Path $dir "src") | ForEach-Object { $_.FullName })
    & "$jdk\javac.exe" --release 17 -encoding UTF-8 -Xlint:deprecation -cp $gameJar -d $classes $sources
    if ($LASTEXITCODE -ne 0) { throw "javac failed for $name" }
    Copy-Item $meta (Join-Path $classes "mod.hjson")
    $assets = Join-Path $dir "assets"
    if (Test-Path $assets) { Copy-Item -Recurse -Force (Join-Path $assets "*") $classes }
    $out = Join-Path $build "$name.jar"
    if (Test-Path $out) { Remove-Item -Force $out }
    & "$jdk\jar.exe" --create --file $out -C $classes .
    if ($LASTEXITCODE -ne 0) { throw "jar failed for $name" }
    Write-Output "built $out"
}

New-Item -ItemType Directory -Force $build | Out-Null
# The mod's mod.hjson lies at the top of the repository: the list behind the in-game mod browser looks for it there.
Build-JavaMod "mshaders" (Join-Path $root "mod") (Join-Path $root "mod.hjson")
Build-JavaMod "mshaders-testkit" (Join-Path $root "testkit")

$zip = Join-Path $build "exp1-water-override.zip"
if (Test-Path $zip) { Remove-Item -Force $zip }
& "$jdk\jar.exe" --create --no-manifest --file $zip -C (Join-Path $root "exp1-water-override") .
if ($LASTEXITCODE -ne 0) { throw "zip failed" }
Write-Output "built $zip"

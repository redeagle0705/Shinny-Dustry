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
    # A normal map not drawn yet - still the very same picture as the base sprite next to it in sprites-override, put
    # there to draw on - is left out: read as a normal map, its colours would shade that block or unit at random.
    $normals = Join-Path $classes "normals"
    if (Test-Path $normals) {
        Get-ChildItem -Recurse -File $normals | ForEach-Object {
            $rel = $_.FullName.Substring($normals.Length + 1)
            $base = Join-Path (Join-Path $assets "sprites-override") $rel
            if ((Test-Path $base) -and ((Get-FileHash $_.FullName).Hash -eq (Get-FileHash $base).Hash)) {
                Remove-Item -Force $_.FullName
                Write-Output "left out (not drawn yet): normals\$rel"
                return
            }
            # one with no twin there: left out if most of it is no normal (as Surfaces.template: not a direction one
            # unit long facing the viewer - a sprite's greys or colours)
            Add-Type -AssemblyName System.Drawing
            $bmp = [Drawing.Bitmap]::FromFile($_.FullName)
            $seen = 0; $flat = 0
            try {
                for ($y = 0; $y -lt $bmp.Height; $y++) { for ($x = 0; $x -lt $bmp.Width; $x++) {
                    $c = $bmp.GetPixel($x, $y)
                    if ($c.A -lt 128) { continue }
                    $seen++
                    $nx = ($c.R - 128) / 127.0; $ny = ($c.G - 128) / 127.0; $nz = ($c.B - 128) / 127.0
                    $len = [Math]::Sqrt($nx * $nx + $ny * $ny + $nz * $nz)
                    if ($nz -lt -0.05 -or [Math]::Abs($len - 1) -gt 0.2) { $flat++ }
                } }
            } finally { $bmp.Dispose() }
            if ($seen -gt 0 -and $flat -gt $seen * 0.25) {   # (drawn maps: under 3%; half-drawn ones from 44%)
                Remove-Item -Force $_.FullName
                Write-Output "left out (not drawn yet, no normals in it): normals\$rel"
            }
        }
    }
    $out = Join-Path $build "$name.jar"
    if (Test-Path $out) { Remove-Item -Force $out }
    & "$jdk\jar.exe" --create --file $out -C $classes .
    if ($LASTEXITCODE -ne 0) { throw "jar failed for $name" }
    Write-Output "built $out"
}

New-Item -ItemType Directory -Force $build | Out-Null
# The mod's mod.hjson lies at the top of the repository: the list behind the in-game mod browser looks for it there.
Build-JavaMod "mshaders" (Join-Path $root "mod") (Join-Path $root "mod.hjson")

# Android: there the game loads a Java mod from classes.dex inside the jar, not from the .class files (Mods.java).
# If the dex converter is there (tools\vendor\r8-<version>.jar; it comes from Google's Maven repository,
# dl.google.com/android/maven2/com/android/tools/r8/), the classes are converted and classes.dex is added to the
# same jar; the desktop game ignores it.
$d8 = Get-ChildItem (Join-Path $PSScriptRoot "vendor") -Filter "r8-*.jar" -ErrorAction SilentlyContinue | Sort-Object Name | Select-Object -Last 1
if ($d8) {
    $modJar = Join-Path $build "mshaders.jar"
    $dex = Join-Path $build "dex"
    if (Test-Path $dex) { Remove-Item -Recurse -Force $dex }
    New-Item -ItemType Directory -Force $dex | Out-Null
    # --lib: the JDK stands in for the Android library (only the class hierarchy is looked up); --min-api 14 is the lowest the game supports
    & "$jdk\java.exe" -cp $d8.FullName com.android.tools.r8.D8 --min-api 14 --lib (Split-Path -Parent $jdk) --classpath $gameJar --output $dex $modJar
    if ($LASTEXITCODE -ne 0) { throw "d8 failed" }
    & "$jdk\jar.exe" --update --file $modJar -C $dex classes.dex
    if ($LASTEXITCODE -ne 0) { throw "adding classes.dex failed" }
    $dexSize = (Get-Item (Join-Path $dex "classes.dex")).Length
    Write-Output "added classes.dex for Android ($dexSize bytes, $($d8.Name))"
} else {
    Write-Output "no dex converter in tools\vendor: mshaders.jar is desktop-only"
}
Build-JavaMod "mshaders-testkit" (Join-Path $root "testkit")

$zip = Join-Path $build "exp1-water-override.zip"
if (Test-Path $zip) { Remove-Item -Force $zip }
& "$jdk\jar.exe" --create --no-manifest --file $zip -C (Join-Path $root "exp1-water-override") .
if ($LASTEXITCODE -ne 0) { throw "zip failed" }
Write-Output "built $zip"

# Runs the official v160.7 desktop jar (not the Steam game) with its own data folder inside this project and takes screenshots.
# Usage: powershell -ExecutionPolicy Bypass -File tools\run-test.ps1 -Mods mshaders,mshaders-testkit -Out screenshots\run1 -Shots "name|key=value,...;name2|..."
#   -Mods   files from build\ (without extension) to put into the test profile's mods folder; everything else there is removed first
#   -Shots  see testkit\src\mstest\TestKit.java.  Without -Shots the game just opens (play by hand, close it yourself).
# Data folder: profile\appdata\Mindustry. Two things point the game there:
#   -Dmindustry.data.dir       read in ClientLauncher.setup()  (core/src/mindustry/ClientLauncher.java:42-45)
#   the APPDATA variable       DesktopLauncher.main() opens %APPDATA%\Mindustry\last_log.txt before that (desktop/.../DesktopLauncher.java:48)
# The window is not maximized: DesktopLauncher reads -maximized / -width / -height (DesktopLauncher.java:77-78, 95).
param([string[]]$Mods = @("mshaders", "mshaders-testkit"), [string]$Shots = "", [string]$Out = "screenshots\run", [string]$Map = "archipelago", [string]$Find = "",
      [int]$Wait = 90, [int]$Width = 1280, [int]$Height = 720, [int]$TimeoutSec = 180)

$ErrorActionPreference = "Stop"
$Mods = @($Mods | ForEach-Object { $_ -split "," } | Where-Object { $_ -ne "" })   # "-File" passes a,b as one string
$root = Split-Path -Parent $PSScriptRoot
$paths = Join-Path $PSScriptRoot "local.ps1"
if (-not (Test-Path $paths)) { throw "tools\local.ps1 not found: copy tools\local.example.ps1 to tools\local.ps1 and put in your own paths" }
. $paths
$jar = $LocalGameJar   # never changed
$java = Join-Path $LocalJdkBin "javaw.exe"
$expected = "36d94941a1639478ad1d9be25a9d802c6f77552f400c6da0338b7b019f63a0d1"
if ((Get-FileHash -Algorithm SHA256 $jar).Hash.ToLower() -ne $expected) { throw "Mindustry.jar is not the official v160.7 release" }

$profileDir = Join-Path $root "profile"
$appdata = Join-Path $profileDir "appdata"
$data = Join-Path $appdata "Mindustry"
$modsDir = Join-Path $data "mods"
New-Item -ItemType Directory -Force $modsDir | Out-Null
Get-ChildItem $modsDir -File | Where-Object { $_.Extension -in ".jar", ".zip" } | Remove-Item -Force
foreach ($m in $Mods) {
    $f = Get-ChildItem (Join-Path $root "build") -File | Where-Object { $_.BaseName -eq $m -and $_.Extension -in ".jar", ".zip" } | Select-Object -First 1
    if (-not $f) { throw "build\$m.jar/.zip not found (run tools\build.ps1)" }
    Copy-Item $f.FullName $modsDir
}

$outDir = if ([IO.Path]::IsPathRooted($Out)) { $Out } else { Join-Path $root $Out }
$jvm = @("-Dnodiscord", "`"-Dmindustry.data.dir=$data`"")
if ($Shots -ne "") {
    New-Item -ItemType Directory -Force $outDir | Out-Null
    $jvm += @("`"-Dmstest.out=$outDir`"", "`"-Dmstest.shots=$Shots`"", "-Dmstest.map=$Map", "-Dmstest.wait=$Wait")
    if ($Find -ne "") { $jvm += "-Dmstest.find=$Find" }   # floor name to point the camera at instead of water
}
$jvm += @("-jar", "`"$jar`"", "-maximized", "false", "-width", "$Width", "-height", "$Height")

$oldAppdata = $env:APPDATA
$env:APPDATA = $appdata
try {
    $p = Start-Process -FilePath $java -ArgumentList $jvm -WorkingDirectory $profileDir -PassThru `
        -RedirectStandardOutput (Join-Path $profileDir "stdout.log") -RedirectStandardError (Join-Path $profileDir "stderr.log")
} finally {
    $env:APPDATA = $oldAppdata
}
Write-Output "started pid=$($p.Id) data=$data mods=$($Mods -join ',')"
if ($Shots -eq "") { return }

if (-not $p.WaitForExit($TimeoutSec * 1000)) {
    Stop-Process -Id $p.Id -Force   # only the process this script started
    throw "test client did not exit within $TimeoutSec s (see profile\stdout.log)"
}
Write-Output "exit code $($p.ExitCode)"
if (Test-Path (Join-Path $outDir "report.txt")) { Get-Content (Join-Path $outDir "report.txt") -Encoding UTF8 }

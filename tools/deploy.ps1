# Puts the built mod into a Mindustry mods folder as an unpacked folder mod (the game loads a folder the same as a jar).
# Usage: powershell -ExecutionPolicy Bypass -File tools\deploy.ps1
#        powershell -ExecutionPolicy Bypass -File tools\deploy.ps1 -Target "D:\somewhere\mods\mshaders"
#
# What it does: unpacks build\mshaders.jar into build\deploy-stage, then makes the target folder a copy of it.
# Files in the target that are not in the build are DELETED. Pictures saved into the target since the last deploy are
# looked after first (see below): copied to rescued\, taken into mod\assets if newer, and left in place.
# Still, your own normal maps and block heights are safest next to the mods folder, where a deploy never reaches:
#   <data folder>\mshaders-normals\<block name>.png      and      <data folder>\mshaders-heights.properties
# (for the Steam game the data folder is ...\steamapps\common\Mindustry\saves).
#
# It touches nothing but that one folder, and refuses to run unless the folder is called "mshaders", sits in a folder
# called "mods", and - if it already exists - holds this mod (mod.hjson with name "mshaders").
# The game reads mods when it starts: restart it to see the change. This script never starts or closes the game.
#
# Without -Target the folder is taken from tools\local.ps1 ($LocalDeployTarget).
param([string]$Target = "")

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$paths = Join-Path $PSScriptRoot "local.ps1"
if (-not (Test-Path $paths)) { throw "tools\local.ps1 not found: copy tools\local.example.ps1 to tools\local.ps1 and put in your own paths" }
. $paths
$jdk = $LocalJdkBin
if ($Target -eq "") { $Target = $LocalDeployTarget }
$jar = Join-Path $root "build\mshaders.jar"
if (-not (Test-Path $jar)) { throw "build\mshaders.jar not found (run tools\build.ps1)" }

$Target = [IO.Path]::GetFullPath($Target)
if ((Split-Path -Leaf $Target) -ne "mshaders") { throw "target must be a folder named mshaders: $Target" }
$mods = Split-Path -Parent $Target
if ((Split-Path -Leaf $mods) -ne "mods" -or -not (Test-Path $mods)) { throw "target must sit in an existing folder named mods: $Target" }
if (Test-Path $Target) {
    $meta = Join-Path $Target "mod.hjson"
    if (-not (Test-Path $meta) -or -not (Select-String -Path $meta -Pattern 'name:\s*"mshaders"' -Quiet)) {
        throw "the target exists but does not hold this mod (no mod.hjson with name mshaders): $Target"
    }
}

$stage = Join-Path $root "build\deploy-stage"
if (Test-Path $stage) { Remove-Item -Recurse -Force $stage }
New-Item -ItemType Directory -Force $stage | Out-Null
Push-Location $stage
try { & "$jdk\jar.exe" xf $jar } finally { Pop-Location }
if ($LASTEXITCODE -ne 0) { throw "unpacking the jar failed" }
$hash = (Get-FileHash -Algorithm SHA256 $jar).Hash.ToLower()
"built $((Get-Item $jar).LastWriteTime.ToString('yyyy-MM-dd HH:mm:ss')), jar sha256 $hash" | Out-File -Encoding utf8 (Join-Path $stage "mshaders-build.txt")
# Drawing with the game open: the mod also reads pictures from the folder named in this file, before all others, and
# reads them again whenever one is saved (Surfaces.watched). It points at this checkout's mod\assets - so a picture
# saved there shows in the running game a second later. Only in this deployed copy; the jar has no such file.
[IO.File]::WriteAllText((Join-Path $stage "mshaders-watch.txt"), (Join-Path $root "mod\assets"))

# Pictures saved into the game's mod folder since the last deploy would be wiped by the copy below (it happened: a
# gloss map painted there). They are kept first: copied, with their folders, into rescued\<date-time> in this checkout.
$stamp = Join-Path $Target "mshaders-build.txt"
if (Test-Path $stamp) {
    $since = (Get-Item $stamp).LastWriteTime.AddSeconds(5)
    $saved = @(Get-ChildItem $Target -Recurse -File | Where-Object { $_.LastWriteTime -gt $since -and $_.Name -notin @("mshaders-build.txt", "mshaders-watch.txt") })
    if ($saved.Count -gt 0) {
        $keep = Join-Path $root ("rescued\" + (Get-Date -Format "yyyyMMdd-HHmmss"))
        foreach ($f in $saved) {
            $to = Join-Path $keep $f.FullName.Substring($Target.Length).TrimStart("\")
            New-Item -ItemType Directory -Force (Split-Path -Parent $to) | Out-Null
            Copy-Item $f.FullName $to
        }
        Write-Output "kept $($saved.Count) file(s) that were saved into the game's mod folder since the last deploy: $keep"
        $saved | ForEach-Object { Write-Output "  $($_.FullName.Substring($Target.Length).TrimStart('\'))" }
        # A picture drawn there is the newest there is: it also goes into this checkout's mod\assets (what was there is
        # kept beside the rescued copy, under "replaced"), so the next build has it. The copy below leaves the game
        # folder's newer file alone (/XO). (Without this a deploy put the older picture of mod\assets back over one
        # the maker had just painted in the game folder - twice in one night.)
        foreach ($f in $saved) {
            $rel = $f.FullName.Substring($Target.Length).TrimStart("\")
            if ($f.Extension -ne ".png" -or $rel -notmatch '^(normals|heightmaps|gloss|sprites-override)\\') { continue }
            $mine = Join-Path $root (Join-Path "mod\assets" $rel)
            if ((Test-Path $mine) -and (Get-Item $mine).LastWriteTime -ge $f.LastWriteTime) { continue }
            if (Test-Path $mine) {
                $old = Join-Path $keep (Join-Path "replaced" $rel)
                New-Item -ItemType Directory -Force (Split-Path -Parent $old) | Out-Null
                Copy-Item $mine $old
            }
            New-Item -ItemType Directory -Force (Split-Path -Parent $mine) | Out-Null
            Copy-Item $f.FullName $mine
            (Get-Item $mine).LastWriteTime = $f.LastWriteTime
            Write-Output "  -> also put into mod\assets\$rel (it was newer than the one there)"
        }
    }
}

# /MIR = make the target a copy (copies new and changed files, deletes the rest);
# /XO = but a file that is newer in the target than in the build stays (a picture just drawn there)
& robocopy $stage $Target /MIR /XO /NJH /NJS /NDL /NP /R:1 /W:1 | Out-Null
if ($LASTEXITCODE -ge 8) { throw "copy failed (robocopy code $LASTEXITCODE) - is the folder writable, is a file in use?" }
$count = (Get-ChildItem $Target -Recurse -File | Measure-Object).Count
Write-Output "deployed $count files to $Target"
Write-Output "jar sha256 $hash"
Write-Output "restart the game to load it"

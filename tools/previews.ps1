# Makes the preview pictures of the settings page (Preview.java): for each effect a shot with it off and one with it on,
# in a test scene that shows it, shrunk to 480x270 JPEG in mod\assets\previews\<setting>-off.jpg / -on.jpg.
# Usage: powershell -ExecutionPolicy Bypass -File tools\previews.ps1 [-Only group,group]
# Runs the test client (tools\run-test.ps1) once per group of effects that share a scene.
param([string[]]$Only = @())

$ErrorActionPreference = "Stop"
# (through -File a list comes as one string: "day,heat")
$Only = @($Only | ForEach-Object { $_ -split "," } | Where-Object { $_ -ne "" })
$root = Split-Path -Parent $PSScriptRoot
$out = Join-Path $root "mod\assets\previews"
New-Item -ItemType Directory -Force $out | Out-Null
Add-Type -AssemblyName System.Drawing

# Settings every scene starts from: the weather off, the mod's own day, midday, the effects as they come.
$clean = "showweather=true,weather=none,mshaders-test-fog=0,mshaders-test-snow=0,mshaders-test-snowdeep=0,mshaders-test-spore=-1,mshaders-test-sand=-1,mshaders-day-own=true,mshaders-test-daytime=45"

# group = scene, then the effects shown in it: setting, value when off, value when on (and anything else to set)
# a small base by the camera: turrets, a smelter, walls, a belt (put=name@dx@dy[@rotation], from the test row)
$base = "blocks=true,put=ripple@0@-3,put=lancer@-3@-3,put=duo@-3@-1,put=silicon-smelter@3@-3,put=titanium-wall-large@3@0,put=thorium-wall@0@0,put=thorium-wall@1@0,put=conveyor@-4@1@0,put=conveyor@-3@1@0,put=conveyor@-2@1@0,put=conveyor@-1@1@0,warmcrafters=1,cam=0:7"

$groups = [ordered]@{
    "day" = @{
        scene = "$clean,$base,zoom=9"
        pairs = @(
            @("mshaders-shadow", "false", "true"),
            @("mshaders-normals", "false", "true"),
            @("mshaders-post", "false", "true"),
            @("mshaders-day-own", "false", "true", "mshaders-test-daytime=82")
        )
    }
    # close on metal, low sun from the side: the glint shows
    "glint" = @{
        scene = "$clean,blocks=true,put=thorium-wall-large@-2@-3,put=titanium-wall-large@0@-3,put=plastanium-wall-large@2@-3,put=surge-wall-large@4@-3,put=thorium-wall@-2@-1,put=titanium-wall@0@-1,cam=1:6,zoom=14,mshaders-test-daytime=70"
        pairs = @(, @("mshaders-glint", "false", "true"))
    }
    # bare ground close up, sand, grass and rock together
    "floors" = @{
        scene = "$clean,zoom=12"
        pairs = @(, @("mshaders-floors", "false", "true"))
    }
    "rain" = @{
        scene = "$clean,$base,zoom=9,weather=rain,mshaders-test-rain=100"
        pairs = @(
            @("mshaders-weather-rain", "-20", "100"),
            @("mshaders-wet", "false", "true")
        )
    }
    "clouds" = @{
        scene = "$clean,blocks=true,zoom=1"
        pairs = @(, @("mshaders-clouds", "false", "true"))
    }
    "pools" = @{
        scene = "$clean,pools=true,zoom=8"
        pairs = @(, @("mshaders-liquids", "false", "true"))
    }
    "walkers" = @{
        scene = "$clean,walkers=dagger+mace+fortress,zoom=9,weather=snowing,mshaders-test-snow=100"
        pairs = @(, @("mshaders-tracks", "false", "true"))
    }
    "flyers" = @{
        scene = "$clean,$base,swarm=horizon:12,zoom=7,mshaders-test-daytime=0"
        pairs = @(
            @("mshaders-boosters", "false", "true"),
            @("mshaders-shot-shadows", "false", "true")
        )
    }
    "pulses" = @{
        scene = "$clean,pulses=true,menders=true,zoom=8"
        wait = 60
        pairs = @(, @("mshaders-pulses", "false", "true"))
    }
    "beams" = @{
        scene = "$clean,beams=true,zoom=8"
        wait = 90
        pairs = @(, @("mshaders-beams", "false", "true"))
    }
    "smoke" = @{
        scene = "$clean,smoke=true,zoom=8"
        wait = 90
        pairs = @(, @("mshaders-smoke-shadows", "false", "true"))
    }
    "grid" = @{
        scene = "$clean,grid=true,zoom=8"
        pairs = @(
            @("mshaders-power", "false", "true"),
            @("mshaders-factories", "false", "true")
        )
    }
    # (cam=DX:DY is from the test row's corner; set before what is placed round the middle of the view)
    # (blocks=true first: it is what sets the test row's corner that cam= counts from)
    "tracks" = @{
        scene = "$clean,blocks=true,cam=0:-8,zoom=8,paint=sand+snow+sand,walkers=dagger+mace+fortress,weather=snowing,mshaders-test-snow=100"
        wait = 400
        pairs = @(, @("mshaders-tracks", "false", "true"))
    }
    "wakes" = @{
        scene = "$clean,blocks=true,cam=0:-8,zoom=8,lake=true,walkers=risso+minke+bryde"
        wait = 400
        pairs = @(, @("mshaders-wakes", "false", "true"))
    }
    # placing a block where air units are: those round the cursor are seen through (hover= puts one over the smelter, at 9.5:3.5)
    "cutaway" = @{
        scene = "$clean,blocks=true,cam=9:3,zoom=10,hover=zenith,place=thorium-wall-large"
        wait = 30
        pairs = @(, @("mshaders-cutaway", "0", "1"))
    }
    "ice" = @{
        scene = "$clean,cam=0:3,zoom=9,paint=ice+ice-snow+ice,$base"
        pairs = @(, @("mshaders-ice", "false", "true"))
    }
    "statuses" = @{
        scene = "$clean,blocks=true,lamp=true,cam=7:3,zoom=10,statuses=burning+wet+freezing+overclock+tarred+shocked,crowd=dagger+mace+fortress+dagger+mace+fortress"
        wait = 120
        pairs = @(, @("mshaders-statuses", "false", "true"))
    }
    "shields" = @{
        scene = "$clean,blocks=true,shields=true,cam=4:10,zoom=7"
        wait = 300
        pairs = @(, @("mshaders-shields", "false", "true"))
    }
    "orepeek" = @{
        scene = "$clean,blocks=true,drill=true,peekdrill=true,cam=1:0,zoom=12"
        pairs = @(, @("mshaders-ore-peek", "false", "true"))
    }
    # an explosion held at one moment: the game's time stopped (time=), the blast that many ticks old (blast=DX:DY:REACH:AGE)
    "blast" = @{
        scene = "$clean,$base,zoom=8,time=600,blast=0:-16:48:10"
        wait = 30
        pairs = @(, @("mshaders-blasts", "false", "true"))
    }
    # the shimmer over hot machines, with the time stopped so the shot is not smeared
    "haze" = @{
        scene = "$clean,blocks=true,smelters=true,warmcrafters=1,zoom=12,time=600"
        wait = 30
        pairs = @(, @("mshaders-heat", "false", "true"))
    }
    "unitlights" = @{
        scene = "$clean,$base,walkers=dagger+mace+fortress,zoom=8,mshaders-test-daytime=0"
        pairs = @(, @("mshaders-lights-units", "false", "true"))
    }
    # (shot soon after the heat is set: it cools in a few seconds; set again for each shot)
    "heat" = @{
        scene = "$clean,$base,zoom=10"
        wait = 20
        pairs = @(, @("mshaders-hit-heat", "false", "true", "heatturrets=1"))
    }
    "night" = @{
        scene = "$clean,$base,mshaders-test-daytime=0,zoom=9"
        pairs = @(
            @("mshaders-lights", "false", "true"),
            @("mshaders-fire", "false", "true")
        )
    }
    # (each weather in a run of its own: a weather started in a run goes on for the rest of it)
    "snow" = @{
        scene = "$clean,blocks=true,turrets=true,cam=0:3,zoom=6,weather=snowing,mshaders-test-snow=100,mshaders-test-snowdeep=100"
        pairs = @(, @("mshaders-weather-snow", "-20", "100"))
    }
    "fog" = @{
        scene = "$clean,blocks=true,turrets=true,cam=0:3,zoom=4,weather=fog,mshaders-test-fog=100"
        pairs = @(, @("mshaders-weather-fog", "-20", "100"))
    }
    # (spores and sand at full, close enough for the clumps and piles to show)
    "spore" = @{
        scene = "$clean,$base,zoom=9,weather=sporestorm,mshaders-test-spore=100,mshaders-weather-spore-pile=200"
        pairs = @(, @("mshaders-weather-spore", "-20", "200"))
    }
    "sand" = @{
        scene = "$clean,$base,zoom=8,weather=sandstorm,mshaders-test-sand=100,mshaders-weather-sand-pile=200,mshaders-test-spore=0,mshaders-weather-spore-pile=100"
        pairs = @(, @("mshaders-weather-sand", "-20", "200"))
    }
    "water" = @{
        scene = "$clean,shore=true,turrets=true,zoom=6"
        pairs = @(, @("mshaders-water", "false", "true"))
    }
    # tall blocks on the shore, close: their mirror images in the water
    "reflect" = @{
        scene = "$clean,shore=true,zoom=10,mshaders-test-daytime=60"
        pairs = @(, @("mshaders-reflect", "false", "true"))
    }
}

foreach ($name in $groups.Keys) {
    if ($Only.Count -gt 0 -and $Only -notcontains $name) { continue }
    $g = $groups[$name]
    $shots = @()
    $first = $true
    foreach ($p in $g.pairs) {
        $key = $p[0]; $off = $p[1]; $on = $p[2]
        $extra = if ($p.Count -gt 3) { "," + $p[3] } else { "" }
        $lead = if ($first) { $g.scene + "," } else { "" }
        # (the extra settings for both shots: what is set for the scene of this effect)
        $shots += "$key-off|$lead$key=$off$extra"
        $shots += "$key-on|$key=$on$extra"
        $first = $false
    }
    $dir = Join-Path $root "screenshots\previews\$name"
    if (Test-Path $dir) { Remove-Item -Recurse -Force $dir }
    $wait = if ($g.wait) { $g.wait } else { 150 }
    Write-Output "group $name : $($shots.Count) shots"
    & powershell -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot "run-test.ps1") -Out $dir -Wait $wait -Shots ($shots -join ";") -TimeoutSec 900 | Out-Null
    foreach ($p in $g.pairs) {
        foreach ($side in "off", "on") {
            $src = Join-Path $dir "$($p[0])-$side.png"
            if (-not (Test-Path $src)) { Write-Output "  missing $src"; continue }
            $img = [Drawing.Image]::FromFile($src)
            $small = New-Object Drawing.Bitmap 480, 270
            $gfx = [Drawing.Graphics]::FromImage($small)
            $gfx.InterpolationMode = "HighQualityBicubic"
            $gfx.DrawImage($img, 0, 0, 480, 270)
            $codec = [Drawing.Imaging.ImageCodecInfo]::GetImageEncoders() | Where-Object { $_.MimeType -eq "image/jpeg" }
            $params = New-Object Drawing.Imaging.EncoderParameters 1
            $params.Param[0] = New-Object Drawing.Imaging.EncoderParameter ([Drawing.Imaging.Encoder]::Quality), 85L
            $small.Save((Join-Path $out "$($p[0])-$side.jpg"), $codec, $params)
            $gfx.Dispose(); $small.Dispose(); $img.Dispose()
        }
        # each effect back to how it comes, in the test profile
    }
}
function Save-Small($src, $dst){
    $img = [Drawing.Image]::FromFile($src)
    $small = New-Object Drawing.Bitmap 480, 270
    $gfx = [Drawing.Graphics]::FromImage($small)
    $gfx.InterpolationMode = "HighQualityBicubic"
    $gfx.DrawImage($img, 0, 0, 480, 270)
    $codec = [Drawing.Imaging.ImageCodecInfo]::GetImageEncoders() | Where-Object { $_.MimeType -eq "image/jpeg" }
    $params = New-Object Drawing.Imaging.EncoderParameters 1
    $params.Param[0] = New-Object Drawing.Imaging.EncoderParameter ([Drawing.Imaging.Encoder]::Quality), 85L
    $small.Save($dst, $codec, $params)
    $gfx.Dispose(); $small.Dispose(); $img.Dispose()
}

# The mod's own sprites (ArtSwitch) take effect only when the game starts: three runs - make sure it starts with them
# on; shoot with them on and turn them off for the next start; shoot with them off and turn them back on.
if ($Only.Count -eq 0 -or $Only -contains "art") {
    $scene = "$clean,$base,zoom=12"
    $dir = Join-Path $root "screenshots\previews\art"
    if (Test-Path $dir) { Remove-Item -Recurse -Force $dir }
    $test = Join-Path $PSScriptRoot "run-test.ps1"
    Write-Output "group art : 3 runs"
    & powershell -ExecutionPolicy Bypass -File $test -Out (Join-Path $dir "0") -Wait 10 -Shots "prep|mshaders-art=true" -TimeoutSec 300 | Out-Null
    & powershell -ExecutionPolicy Bypass -File $test -Out (Join-Path $dir "1") -Wait 150 -Shots "mshaders-art-on|$scene;next|mshaders-art=false" -TimeoutSec 600 | Out-Null
    & powershell -ExecutionPolicy Bypass -File $test -Out (Join-Path $dir "2") -Wait 150 -Shots "mshaders-art-off|$scene;next|mshaders-art=true" -TimeoutSec 600 | Out-Null
    foreach ($side in "on", "off") {
        $n = if ($side -eq "on") { "1" } else { "2" }
        $src = Join-Path $dir "$n\mshaders-art-$side.png"
        if (Test-Path $src) { Save-Small $src (Join-Path $out "mshaders-art-$side.jpg") } else { Write-Output "  missing $src" }
    }
}
# The mod's own day and night: time passing is what it does - "on" is the same scene by day, at sunrise and by night,
# side by side in three strips (each strip the third of its own shot, so they join into one view); "off" is the day as
# the map has it.
if ($Only.Count -eq 0 -or $Only -contains "dayown") {
    $scene = "$clean,$base,zoom=8"
    $dir = Join-Path $root "screenshots\previews\dayown"
    if (Test-Path $dir) { Remove-Item -Recurse -Force $dir }
    Write-Output "group dayown : 4 shots"
    $shots = "off|$scene,mshaders-day-own=false,mshaders-test-daytime=-1;day|mshaders-day-own=true,mshaders-test-daytime=45;dawn|mshaders-test-daytime=26;night|mshaders-test-daytime=0"
    & powershell -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot "run-test.ps1") -Out $dir -Wait 150 -Shots $shots -TimeoutSec 600 | Out-Null
    if (Test-Path (Join-Path $dir "off.png")) { Save-Small (Join-Path $dir "off.png") (Join-Path $out "mshaders-day-own-off.jpg") }
    $parts = "day", "dawn", "night" | ForEach-Object { Join-Path $dir "$_.png" }
    if (($parts | Where-Object { -not (Test-Path $_) }).Count -eq 0) {
        $first = [Drawing.Image]::FromFile($parts[0])
        $w = $first.Width; $h = $first.Height
        $first.Dispose()
        $joined = New-Object Drawing.Bitmap $w, $h
        $gfx = [Drawing.Graphics]::FromImage($joined)
        $gfx.Clear([Drawing.Color]::Black)
        for ($i = 0; $i -lt 3; $i++) {
            $img = [Drawing.Image]::FromFile($parts[$i])
            $x0 = [int]($i * $w / 3); $x1 = [int](($i + 1) * $w / 3)
            # (a black line between the strips)
            $gap = if ($i -gt 0) { 6 } else { 0 }
            $gfx.DrawImage($img, (New-Object Drawing.Rectangle ($x0 + $gap), 0, ($x1 - $x0 - $gap), $h), (New-Object Drawing.Rectangle ($x0 + $gap), 0, ($x1 - $x0 - $gap), $h), [Drawing.GraphicsUnit]::Pixel)
            $img.Dispose()
        }
        $gfx.Dispose()
        $tmp = Join-Path $dir "joined.png"
        $joined.Save($tmp)
        $joined.Dispose()
        Save-Small $tmp (Join-Path $out "mshaders-day-own-on.jpg")
    }
}
Write-Output "previews in $out"

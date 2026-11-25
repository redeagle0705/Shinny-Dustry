# Usage: powershell -ExecutionPolicy Bypass -File tools\snap-normals.ps1 [-Apply]
#   Without -Apply: lists the normal-map pixels that are a little off the exact palette (docs\normal-palette.png) -
#   colours smeared by saving as JPG, or an older set one step off. With -Apply: snaps them (originals backed up once).
param([switch]$Apply)
# Snaps every normal-map pixel that is a near miss (JPG smear, older steep set) to the exact palette colour nearest it.
Add-Type -AssemblyName System.Drawing
$root = (Resolve-Path (Join-Path $PSScriptRoot "..\mod\assets\normals")).Path
$bakRoot = Join-Path $PSScriptRoot "..\screenshots\normal-backups\before-snap"   # screenshots\ is git-ignored
# still plain sprite copies (templates to be drawn): left alone
$skip = @('blocks\storage\container.png','blocks\storage\vault.png','blocks\units\ground-factory.png',
          'units\beta.png','units\dagger.png','units\dagger-leg.png','units\fortress.png','units\fortress-leg.png',
          'units\mace.png','units\mace-leg.png','units\weapons\artillery.png','units\weapons\flamethrower.png',
          'units\weapons\heal-weapon.png','units\weapons\large-weapon.png')
$palette = @(
  @(128,128,255),
  # blue 248
  @(85,128,248),@(170,128,248),@(128,85,248),@(128,170,248),@(98,98,248),@(157,157,248),@(98,157,248),@(157,98,248),
  # blue 244
  @(75,128,244),@(180,128,244),@(128,75,244),@(128,180,244),@(91,91,244),@(164,164,244),@(91,164,244),@(164,91,244),
  # blue 231
  @(53,128,231),@(202,128,231),@(128,53,231),@(128,202,231),@(75,75,231),@(180,180,231),@(75,180,231),@(180,75,231),
  # factory slope: sides blue 218, corners blue 201
  @(37,128,218),@(218,128,218),@(128,37,218),@(128,218,218),@(54,54,201),@(201,201,201),@(54,201,201),@(201,54,201),
  # wall slope: blue 173
  @(8,128,173),@(247,128,173),@(128,8,173),@(128,247,173),@(43,43,173),@(212,212,173),@(43,212,173),@(212,43,173),
  # drill rotator set, blue 144 (as drawn)
  @(128,1,144),@(254,128,144),@(1,128,144),@(128,254,144)
)
$exact = @{}
foreach($p in $palette){ $exact["$($p[0]),$($p[1]),$($p[2])"] = $true }
$total = 0
Get-ChildItem -Recurse -File $root -Filter *.png | ForEach-Object {
  $rel = $_.FullName.Substring($root.Length + 1)
  if($skip -contains $rel){ return }
  $src = [System.Drawing.Bitmap]::FromFile($_.FullName)
  $out = New-Object System.Drawing.Bitmap $src.Width, $src.Height, ([System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
  $changed = 0; $far = @{}; $how = @{}
  for($y = 0; $y -lt $src.Height; $y++){
    for($x = 0; $x -lt $src.Width; $x++){
      $c = $src.GetPixel($x, $y)
      $k = "$($c.R),$($c.G),$($c.B)"
      if($c.A -ge 1 -and $c.B -ge 140 -and -not $exact.ContainsKey($k)){
        $best = $null; $bd = 1e9
        foreach($p in $palette){
          $d = [Math]::Sqrt(($c.R-$p[0])*($c.R-$p[0]) + ($c.G-$p[1])*($c.G-$p[1]) + ($c.B-$p[2])*($c.B-$p[2]))
          if($d -lt $bd){ $bd = $d; $best = $p }
        }
        if($bd -le 40){
          $how["$k->$($best -join ',')"] = 1 + $how["$k->$($best -join ',')"]
          $c = [System.Drawing.Color]::FromArgb($c.A, $best[0], $best[1], $best[2]); $changed++
        }else{ $far[$k] = 1 + $far[$k] }
      }
      $out.SetPixel($x, $y, $c)
    }
  }
  $src.Dispose()
  if($changed -gt 0){
    $line = "{0}: {1} px  {2}" -f $rel, $changed, (($how.GetEnumerator() | Sort-Object Value -Descending | Select-Object -First 5 | ForEach-Object { "$($_.Key) x$($_.Value)" }) -join '; ')
    if($far.Count -gt 0){ $line += "  | left (far): " + (($far.GetEnumerator() | Sort-Object Value -Descending | Select-Object -First 3 | ForEach-Object { "$($_.Key) x$($_.Value)" }) -join '; ') }
    Write-Output $line
    $total += $changed
    if($Apply){
      $bak = Join-Path $bakRoot $rel
      New-Item -ItemType Directory -Force (Split-Path $bak) | Out-Null
      if(-not (Test-Path $bak)){ Copy-Item $_.FullName $bak }
      $out.Save($_.FullName, [System.Drawing.Imaging.ImageFormat]::Png)
    }
  }elseif($far.Count -gt 0){
    Write-Output ("{0}: nothing near; left (far): {1}" -f $rel, (($far.GetEnumerator() | Sort-Object Value -Descending | Select-Object -First 3 | ForEach-Object { "$($_.Key) x$($_.Value)" }) -join '; '))
  }
  $out.Dispose()
}
Write-Output "total $total px $(if($Apply){'changed'}else{'would change (dry run)'})"


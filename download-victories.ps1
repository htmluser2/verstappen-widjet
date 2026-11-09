$ErrorActionPreference = "Continue"
$ua = "VerstappenRaceDash/1.0 (educational offline app)"
$dir = "app\src\main\assets\victory-gallery"
New-Item -ItemType Directory -Force -Path $dir | Out-Null

# Verified Wikimedia Commons files (freely licensed) — one photo per Grand Prix win.
$images = [ordered]@{
  "spain-2016.jpg"        = "Max_Verstappen_won_the_2016_Spanish_Grand_Prix.jpg"
  "malaysia-2017.jpg"     = "Max_Verstappen_2017_Malaysia_3.jpg"
  "hungary-2019.jpg"      = "2019_Formula_1_Hungarian_GP_podium.jpg"
  "france-2021.jpg"       = "Max_Verstappen_(Austin_2021)_(cropped).jpg"
  "austria-2022.jpg"      = "FIA_F1_Austria_2022_Podium_Race_(1).jpg"
  "japan-2022.jpg"        = "2022_Japanese_Grand_Prix_Award_Ceremony_1.jpg"
  "sao-paulo-2019.jpg"    = [char]0x0046 + [char]0x00F3 + "rmula_1_" + [char]0x2013 + "_Grande_Pr" + [char]0x00EA + "mio_do_Brasil_de_F1_2019_(49080039653).jpg"
  "china-2024.jpg"        = "Max_Verstappen_2024_Chinese_GP.jpg"
}

$ok = 0; $fail = 0
foreach ($entry in $images.GetEnumerator()) {
  $dest = Join-Path $dir $entry.Key
  if (Test-Path $dest) { Write-Output "SKIP $($entry.Key)"; $ok++; continue }
  $encoded = [uri]::EscapeDataString($entry.Value)
  $url = "https://commons.wikimedia.org/wiki/Special:FilePath/$($encoded)?width=1400"
  $done = $false
  foreach ($attempt in 1..3) {
    try {
      Invoke-WebRequest -Uri $url -UserAgent $ua -OutFile $dest -TimeoutSec 60
      $size = (Get-Item $dest).Length
      if ($size -gt 20000) {
        Write-Output ("OK   {0,-22} {1,9:N0} bytes" -f $entry.Key, $size)
        $ok++; $done = $true; break
      }
      Remove-Item $dest -Force
    } catch {
      Start-Sleep -Seconds (3 * $attempt)
    }
  }
  if (-not $done) { Write-Output "FAIL $($entry.Key)"; $fail++ }
  Start-Sleep -Milliseconds 1200
}
Write-Output "DONE ok=$ok fail=$fail"

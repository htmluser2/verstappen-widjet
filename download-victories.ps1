$ErrorActionPreference = "Continue"
$ua = "VerstappenRaceDash/1.0 (educational offline app)"
$dir = "app\src\main\assets\victory-gallery"
New-Item -ItemType Directory -Force -Path $dir | Out-Null

# Wikimedia Commons file titles, resolved through its public API.
$images = [ordered]@{
  "spain-2016.jpg"        = "Verstappen_Spain_2016.jpg"
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
  $done = $false
  foreach ($attempt in 1..3) {
    try {
      $encodedTitle = [uri]::EscapeDataString("File:$($entry.Value)")
      $apiUrl = "https://commons.wikimedia.org/w/api.php?action=query&titles=$encodedTitle&prop=imageinfo&iiprop=url%7Cextmetadata&iiurlwidth=1400&format=json"
      $metadata = Invoke-RestMethod -Uri $apiUrl -UserAgent $ua -TimeoutSec 30
      $page = $metadata.query.pages.PSObject.Properties.Value | Select-Object -First 1
      $imageInfo = $page.imageinfo | Select-Object -First 1
      if (-not $imageInfo) { throw "Commons API returned no image for $($entry.Value)" }

      $imageUri = [uri]$imageInfo.thumburl
      if ($imageUri.Scheme -ne "https" -or $imageUri.Host -notin @("upload.wikimedia.org", "thumb.wikimedia.org")) {
        throw "Commons API returned an unexpected image host for $($entry.Value)"
      }

      Invoke-WebRequest -Uri $imageUri.AbsoluteUri -UserAgent $ua -OutFile $dest -TimeoutSec 60
      $size = (Get-Item $dest).Length
      if ($size -gt 20000) {
        $license = $imageInfo.extmetadata.LicenseShortName.value
        Write-Output ("OK   {0,-22} {1,9:N0} bytes · {2}" -f $entry.Key, $size, $license)
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

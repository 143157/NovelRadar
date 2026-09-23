$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'bootstrap-android.ps1') -FunctionsOnly

function Assert-True([bool] $Condition, [string] $Message) { if (-not $Condition) { throw $Message } }

$expectedCliSha256 = '90AE805D20434428BFFCB699C290860F19BB5F66A67E6B330067E3DE801FB04A'
$fixedHashes = @($cliSha256, $gradleSha256)
Assert-True (($fixedHashes | Where-Object { $_.Length -ne 64 -or $_ -notmatch '^[0-9A-F]{64}$' }).Count -eq 0) 'Fixed SHA-256 constants must be 64 hexadecimal characters'
Assert-True ($cliSha256 -eq $expectedCliSha256) 'CLI SHA-256 constant does not match the official value'

$fixtureRoot = Assert-StagingPath (Join-Path $toolCacheDir 'bootstrap-test-fixtures')
Remove-SafeTree $fixtureRoot
New-Item -ItemType Directory -Path $fixtureRoot -Force | Out-Null
try {
    $sourceDir = Join-Path $fixtureRoot 'source'
    New-Item -ItemType Directory -Path $sourceDir -Force | Out-Null
    Set-Content -LiteralPath (Join-Path $sourceDir 'payload.txt') -Value 'verified payload' -Encoding ASCII
    $sourceZip = Join-Path $fixtureRoot 'source.zip'
    Compress-Archive -Path (Join-Path $sourceDir 'payload.txt') -DestinationPath $sourceZip
    $destination = Join-Path $fixtureRoot 'cached.zip'
    Set-Content -LiteralPath $destination -Value 'corrupt cache' -Encoding ASCII
    $expected = (Get-FileHash -LiteralPath $sourceZip -Algorithm SHA256).Hash
    Ensure-VerifiedDownload ('file:///' + $sourceZip.Replace('\', '/')) $destination $expected
    Assert-True ((Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash -eq $expected) 'Corrupt cache was not replaced'

    $unicodeInput = 'C:' + [char]92 + [char]0x4E66 + '=' + [char]0x672C + [char]92 + [char]0x00E9
    $unicode = ConvertTo-JavaPropertyValue $unicodeInput
    Assert-True ($unicode.Contains('\\')) 'Backslash was not escaped'
    Assert-True ($unicode.Contains(([char]92 + 'u4E66')) -and $unicode.Contains(([char]92 + 'u00E9'))) 'Unicode was not escaped'
    Assert-True ($unicode.Contains(([char]92 + '='))) 'Equals sign was not escaped'

    $sdkManager = Join-Path $fixtureRoot 'stderr-flood.bat'
    Set-Content -LiteralPath $sdkManager -Encoding ASCII -Value "@echo off`r`nfor /L %%i in (1,1,5000) do @echo stderr %%i 1>&2`r`nexit /b 0"
    $exitCode = Invoke-SdkManager @('flood')
    Assert-True ($exitCode -eq 0) 'SDK manager stderr flood did not complete successfully'
    Write-Output 'focused-tests=passed (corrupt-cache, unicode-properties, stderr-flood)'
} finally {
    Remove-SafeTree $fixtureRoot
}

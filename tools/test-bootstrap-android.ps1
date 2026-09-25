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
    $global:BootstrapDownloadOverride = { param([string] $Url, [string] $OutFile) Copy-Item -LiteralPath $sourceZip -Destination $OutFile -Force }
    Ensure-VerifiedDownload 'https://fixture.invalid/source.zip' $destination $expected
    Assert-True ((Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash -eq $expected) 'Corrupt cache was not replaced'

    $cliFixture = Join-Path $fixtureRoot 'cli-incomplete'
    New-Item -ItemType Directory -Path (Join-Path $cliFixture 'bin') -Force | Out-Null
    Set-Content -LiteralPath (Join-Path $cliFixture 'bin\sdkmanager.bat') -Value '@echo off' -Encoding ASCII
    Assert-True (-not (Test-CliToolsComplete $cliFixture)) 'CLI dependency gap was not detected'
    New-Item -ItemType Directory -Path (Join-Path $cliFixture 'lib') -Force | Out-Null
    Set-Content -LiteralPath (Join-Path $cliFixture 'lib\sdkmanager-classpath.jar') -Value 'jar' -Encoding ASCII
    Assert-True (Test-CliToolsComplete $cliFixture) 'Complete CLI fixture was not accepted'

    $gradleFixture = Join-Path $fixtureRoot 'gradle-incomplete'
    New-Item -ItemType Directory -Path (Join-Path $gradleFixture 'bin') -Force | Out-Null
    Set-Content -LiteralPath (Join-Path $gradleFixture 'bin\gradle.bat') -Value '@echo off' -Encoding ASCII
    Assert-True (-not (Test-GradleComplete $gradleFixture)) 'Gradle dependency gap was not detected'
    New-Item -ItemType Directory -Path (Join-Path $gradleFixture 'lib') -Force | Out-Null
    Set-Content -LiteralPath (Join-Path $gradleFixture 'lib\gradle-launcher-9.6.0.jar') -Value 'jar' -Encoding ASCII
    Assert-True (Test-GradleComplete $gradleFixture) 'Complete Gradle fixture was not accepted'

    $unicodeInput = 'C:' + [char]92 + [char]0x4E66 + '=' + [char]0x672C + [char]92 + [char]0x00E9
    $unicode = ConvertTo-JavaPropertyValue $unicodeInput
    Assert-True ($unicode.Contains('\\')) 'Backslash was not escaped'
    Assert-True ($unicode.Contains(([char]92 + 'u4E66')) -and $unicode.Contains(([char]92 + 'u00E9'))) 'Unicode was not escaped'
    Assert-True ($unicode.Contains(([char]92 + '='))) 'Equals sign was not escaped'

    $sdkManager = Join-Path $fixtureRoot 'stderr-flood.bat'
    Set-Content -LiteralPath $sdkManager -Encoding ASCII -Value "@echo off`r`nfor /L %%i in (1,1,2147483647) do @echo stderr %%i 1>&2"
    $timedOut = $false
    try { Invoke-SdkManager @('flood') '' 1 $true | Out-Null } catch { $timedOut = $_.Exception.Message -match 'timed out' }
    Assert-True $timedOut 'stderr flood timeout did not fail and terminate the child process'
    Set-Content -LiteralPath $sdkManager -Encoding ASCII -Value "@echo off`r`nfor /L %%i in (1,1,5000) do @echo stderr %%i 1>&2`r`nexit /b 0"
    $exitCode = Invoke-SdkManager @('flood') '' 5 $true
    Assert-True ($exitCode -eq 0) 'SDK manager stderr flood did not complete successfully'

    $pidFile = Join-Path $fixtureRoot 'child.pid'
    $treeFixture = Join-Path $fixtureRoot 'process-tree.bat'
    $childCommand = "`$child = Start-Process -FilePath powershell.exe -ArgumentList '-NoProfile','-Command','Start-Sleep -Seconds 120' -PassThru; Set-Content -LiteralPath '$pidFile' -Value `$child.Id; Wait-Process -Id `$child.Id"
    Set-Content -LiteralPath $treeFixture -Encoding ASCII -Value "@echo off`r`npowershell.exe -NoProfile -Command `"$childCommand`""
    $sdkManager = $treeFixture
    $treeTimedOut = $false
    try {
        Invoke-SdkManager @('tree') '' 2 $true | Out-Null
    } catch {
        $treeTimedOut = $_.Exception.Message -match 'timed out'
    }
    Assert-True $treeTimedOut 'Process-tree fixture did not hit the hard timeout'
    $deadline = [DateTime]::UtcNow.AddSeconds(5)
    do {
        Start-Sleep -Milliseconds 100
        $childAlive = $false
        if (Test-Path -LiteralPath $pidFile) {
            $childPid = [int](Get-Content -LiteralPath $pidFile -Raw).Trim()
            $childAlive = $null -ne (Get-Process -Id $childPid -ErrorAction SilentlyContinue)
        }
    } while ($childAlive -and [DateTime]::UtcNow -lt $deadline)
    Assert-True (-not $childAlive) 'Timed-out SDK manager descendant remained alive'
    Write-Output 'focused-tests=passed (corrupt-cache, unicode-properties, stderr-flood)'
} finally {
    if ($childPid) { Stop-Process -Id $childPid -Force -ErrorAction SilentlyContinue }
    Remove-Variable BootstrapDownloadOverride -Scope Global -ErrorAction SilentlyContinue
    Remove-SafeTree $fixtureRoot
}

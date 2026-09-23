param([switch] $FunctionsOnly)

$ErrorActionPreference = 'Stop'
$androidSdkDir = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
$toolCacheDir = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\.tools'))
$cliZip = Join-Path $toolCacheDir 'commandlinetools-win-15859902_latest.zip'
$cliUrl = 'https://dl.google.com/android/repository/commandlinetools-win-15859902_latest.zip'
$cliSha256 = '90AE805D20434428BFFCB699C290860F19BB5F66A67E6B330067E3DE801FB04A'
$gradleVersion = '9.6.0'
$gradleZip = Join-Path $toolCacheDir "gradle-$gradleVersion-bin.zip"
$gradleUrl = "https://services.gradle.org/distributions/gradle-$gradleVersion-bin.zip"
$gradleSha256 = 'BBAEB2FEF8710818CF0E261201DAB964C572F92B942812DF0C3620D62A529A01'
$sdkToolsDir = Join-Path $androidSdkDir 'cmdline-tools\latest'
$gradleDir = Join-Path $toolCacheDir "gradle-$gradleVersion"

function Assert-NoReparsePath([string] $Path) {
    $full = [IO.Path]::GetFullPath($Path)
    $root = [IO.Path]::GetPathRoot($full)
    $current = $root
    foreach ($part in $full.Substring($root.Length).Split('\')) {
        if ([string]::IsNullOrEmpty($part)) { continue }
        $current = Join-Path $current $part
        if (Test-Path -LiteralPath $current) {
            $item = Get-Item -LiteralPath $current -Force
            if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { throw "Refusing to operate through reparse point: $current" }
        }
    }
    return $full
}

function Assert-StagingPath([string] $Path) {
    $resolvedPath = Assert-NoReparsePath $Path
    $resolvedRoot = [IO.Path]::GetFullPath($toolCacheDir).TrimEnd('\') + '\'
    if (-not $resolvedPath.StartsWith($resolvedRoot, [StringComparison]::OrdinalIgnoreCase)) { throw "Refusing to operate on path outside tool cache: $resolvedPath" }
    return $resolvedPath
}

function Assert-SdkToolsTarget([string] $Path) {
    $expected = [IO.Path]::GetFullPath((Join-Path $androidSdkDir 'cmdline-tools\latest'))
    $actual = Assert-NoReparsePath $Path
    if (-not $actual.Equals($expected, [StringComparison]::OrdinalIgnoreCase)) { throw "Refusing to replace unexpected SDK tools target: $actual" }
    return $actual
}

function Remove-SafeTree([string] $Path) {
    $safe = Assert-StagingPath $Path
    if (Test-Path -LiteralPath $safe) { Remove-Item -LiteralPath $safe -Recurse -Force }
}

function Ensure-Directory([string] $Path) {
    Assert-NoReparsePath $Path | Out-Null
    if (-not (Test-Path -LiteralPath $Path -PathType Container)) { New-Item -ItemType Directory -Path $Path -Force | Out-Null }
}

function Test-ZipReadable([string] $Archive) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = $null
    try { $zip = [IO.Compression.ZipFile]::OpenRead($Archive); return ($zip.Entries.Count -gt 0) }
    finally { if ($null -ne $zip) { $zip.Dispose() } }
}

function Ensure-VerifiedDownload([string] $Url, [string] $Destination, [string] $ExpectedSha256) {
    $destination = Assert-StagingPath $Destination
    $valid = $false
    if (Test-Path -LiteralPath $destination -PathType Leaf) {
        try { $valid = ((Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash -eq $ExpectedSha256 -and (Test-ZipReadable $destination)) } catch { $valid = $false }
    }
    if ($valid) { return }
    if (Test-Path -LiteralPath $destination) { Remove-SafeTree $destination }
    $temporary = Assert-StagingPath (Join-Path $toolCacheDir ('.download-' + [Guid]::NewGuid().ToString('N') + '.zip'))
    try {
        Write-Host "Downloading $Url"
        Invoke-WebRequest -Uri $Url -OutFile $temporary -UseBasicParsing
        if ((Get-FileHash -LiteralPath $temporary -Algorithm SHA256).Hash -ne $ExpectedSha256 -or -not (Test-ZipReadable $temporary)) { throw "Downloaded archive failed SHA-256 or ZIP validation: $Url" }
        Move-Item -LiteralPath $temporary -Destination $destination -Force
    } finally { if (Test-Path -LiteralPath $temporary) { Remove-SafeTree $temporary } }
}

function Expand-ArchiveToStaging([string] $Archive, [string] $StagingPath) {
    $safeStaging = Assert-StagingPath $StagingPath
    Remove-SafeTree $safeStaging
    New-Item -ItemType Directory -Path $safeStaging -Force | Out-Null
    Expand-Archive -LiteralPath $Archive -DestinationPath $safeStaging -Force
}

function Invoke-SdkManager([string[]] $Arguments, [string] $InputText = '') {
    $argumentText = $Arguments -join ' '
    $startInfo = New-Object Diagnostics.ProcessStartInfo
    $startInfo.FileName = 'cmd.exe'
    $startInfo.Arguments = "/d /c `"call `"$sdkManager`" $argumentText`""
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardInput = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $process = New-Object Diagnostics.Process
    $process.StartInfo = $startInfo
    try {
        [void] $process.Start()
        if ($InputText) { $process.StandardInput.Write($InputText) }
        $process.StandardInput.Close()
        $outputTask = $process.StandardOutput.ReadToEndAsync()
        $errorTask = $process.StandardError.ReadToEndAsync()
        $process.WaitForExit()
        $output = $outputTask.Result
        $errorOutput = $errorTask.Result
        if ($output) { Write-Host $output }
        if ($errorOutput) { Write-Host $errorOutput }
        return $process.ExitCode
    } finally { $process.Dispose() }
}

function ConvertTo-JavaPropertyValue([string] $Value) {
    $builder = New-Object Text.StringBuilder
    foreach ($character in $Value.ToCharArray()) {
        $code = [int][char]$character
        if ($code -gt 127) { [void] $builder.Append(('\u{0:X4}' -f $code)) }
        elseif ($character -eq '\') { [void] $builder.Append('\\') }
        elseif ($character -eq ':' -or $character -eq '=') { [void] $builder.Append('\').Append($character) }
        else { [void] $builder.Append($character) }
    }
    return $builder.ToString()
}

if ($FunctionsOnly) { return }

Ensure-Directory $toolCacheDir
Ensure-Directory (Split-Path -Parent $androidSdkDir)
$cliStaging = Assert-StagingPath (Join-Path $toolCacheDir 'commandline-tools-staging')
$sdkManager = Join-Path $sdkToolsDir 'bin\sdkmanager.bat'
if ((Test-Path -LiteralPath $cliZip -PathType Leaf) -or -not (Test-Path -LiteralPath $sdkManager -PathType Leaf)) {
    Ensure-VerifiedDownload $cliUrl $cliZip $cliSha256
}
if (-not (Test-Path -LiteralPath $sdkManager -PathType Leaf)) {
    Expand-ArchiveToStaging $cliZip $cliStaging
    $extracted = Join-Path $cliStaging 'cmdline-tools'
    if (-not (Test-Path -LiteralPath (Join-Path $extracted 'bin\sdkmanager.bat') -PathType Leaf)) { throw 'CLI archive lacked cmdline-tools\bin\sdkmanager.bat' }
    $sdkTarget = Assert-SdkToolsTarget $sdkToolsDir
    Ensure-Directory (Split-Path -Parent $sdkTarget)
    if (Test-Path -LiteralPath $sdkTarget) { Assert-SdkToolsTarget $sdkTarget | Out-Null; Remove-Item -LiteralPath $sdkTarget -Recurse -Force }
    Move-Item -LiteralPath $extracted -Destination $sdkTarget
    Remove-SafeTree $cliStaging
}

Write-Host 'Accepting Android SDK licenses...'
if ((Invoke-SdkManager @('--licenses') (('y' + [Environment]::NewLine) * 100)) -ne 0) { throw 'sdkmanager --licenses failed' }
Write-Host 'Installing required Android SDK packages...'
$packageFailures = @()
foreach ($package in @('platform-tools', 'platforms;android-37.2', 'build-tools;36.0.0')) {
    $exitCode = Invoke-SdkManager @($package)
    if ($exitCode -ne 0) { $packageFailures += "$package (exit code $exitCode)" }
}

Ensure-VerifiedDownload $gradleUrl $gradleZip $gradleSha256
$gradleStaging = Assert-StagingPath (Join-Path $toolCacheDir "gradle-$gradleVersion-staging")
$gradleExecutable = Join-Path $gradleDir 'bin\gradle.bat'
if (-not (Test-Path -LiteralPath $gradleExecutable -PathType Leaf)) {
    Expand-ArchiveToStaging $gradleZip $gradleStaging
    $gradleExtracted = Join-Path $gradleStaging "gradle-$gradleVersion"
    if (-not (Test-Path -LiteralPath (Join-Path $gradleExtracted 'bin\gradle.bat') -PathType Leaf)) { throw 'Gradle archive lacked expected bin\gradle.bat' }
    if (Test-Path -LiteralPath $gradleDir) { Remove-SafeTree $gradleDir }
    Move-Item -LiteralPath $gradleExtracted -Destination $gradleDir
    Remove-SafeTree $gradleStaging
}

$localProperties = Join-Path (Split-Path -Parent $PSScriptRoot) 'local.properties'
Set-Content -LiteralPath $localProperties -Value ('sdk.dir=' + (ConvertTo-JavaPropertyValue $androidSdkDir)) -Encoding ASCII
Write-Host "Android SDK: $androidSdkDir"
Write-Host "Gradle: $gradleDir"
Write-Host "ADB: $(Join-Path $androidSdkDir 'platform-tools\adb.exe')"
if ($packageFailures.Count -gt 0) { throw "Required Android SDK package installation failed: $($packageFailures -join ', ')" }
Write-Host 'Android toolchain bootstrap complete.'

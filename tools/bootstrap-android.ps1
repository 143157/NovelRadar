$ErrorActionPreference = 'Stop'

$androidSdkDir = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
$toolCacheDir = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\.tools'))
$cliZip = Join-Path $toolCacheDir 'commandlinetools-win-15859902_latest.zip'
$cliUrl = 'https://dl.google.com/android/repository/commandlinetools-win-15859902_latest.zip'
$gradleVersion = '9.6.0'
$gradleZip = Join-Path $toolCacheDir "gradle-$gradleVersion-bin.zip"
$gradleUrl = "https://services.gradle.org/distributions/gradle-$gradleVersion-bin.zip"
$sdkToolsDir = Join-Path $androidSdkDir 'cmdline-tools\latest'
$gradleDir = Join-Path $toolCacheDir "gradle-$gradleVersion"

function Assert-StagingPath([string] $Path) {
    $resolvedPath = [IO.Path]::GetFullPath($Path)
    $resolvedRoot = [IO.Path]::GetFullPath($toolCacheDir).TrimEnd('\') + '\'
    if (-not $resolvedPath.StartsWith($resolvedRoot, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to operate on path outside tool cache: $resolvedPath"
    }
    return $resolvedPath
}

function Ensure-Directory([string] $Path) {
    if (-not (Test-Path -LiteralPath $Path -PathType Container)) {
        New-Item -ItemType Directory -Path $Path -Force | Out-Null
    }
}

function Ensure-Download([string] $Url, [string] $Destination) {
    if (-not (Test-Path -LiteralPath $Destination -PathType Leaf)) {
        Write-Host "Downloading $Url"
        Invoke-WebRequest -Uri $Url -OutFile $Destination -UseBasicParsing
    }
}

function Expand-ArchiveToStaging([string] $Archive, [string] $StagingPath) {
    $safeStaging = Assert-StagingPath $StagingPath
    if (Test-Path -LiteralPath $safeStaging) {
        Remove-Item -LiteralPath $safeStaging -Recurse -Force
    }
    New-Item -ItemType Directory -Path $safeStaging -Force | Out-Null
    Expand-Archive -LiteralPath $Archive -DestinationPath $safeStaging -Force
}

Ensure-Directory $toolCacheDir
Ensure-Directory (Split-Path -Parent $androidSdkDir)

Ensure-Download $cliUrl $cliZip
$cliStaging = Assert-StagingPath (Join-Path $toolCacheDir 'commandline-tools-staging')
if (-not (Test-Path -LiteralPath $sdkToolsDir -PathType Container)) {
    Expand-ArchiveToStaging $cliZip $cliStaging
    $extracted = Join-Path $cliStaging 'cmdline-tools'
    if (-not (Test-Path -LiteralPath $extracted -PathType Container)) {
        $extracted = $cliStaging
    }
    Ensure-Directory (Split-Path -Parent $sdkToolsDir)
    Move-Item -LiteralPath $extracted -Destination $sdkToolsDir
    if (Test-Path -LiteralPath $cliStaging) {
        Remove-Item -LiteralPath $cliStaging -Recurse -Force
    }
}

$sdkManager = Join-Path $sdkToolsDir 'bin\sdkmanager.bat'
if (-not (Test-Path -LiteralPath $sdkManager -PathType Leaf)) {
    throw "Android sdkmanager was not found at $sdkManager"
}

function Invoke-SdkManager([string[]] $Arguments, [string] $InputText = '') {
    $argumentText = $Arguments -join ' '
    $startInfo = [Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = 'cmd.exe'
    $startInfo.Arguments = "/d /c `"call `"$sdkManager`" $argumentText`""
    $startInfo.UseShellExecute = $false
    $startInfo.RedirectStandardInput = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $startInfo
    [void] $process.Start()
    if ($InputText) {
        $process.StandardInput.Write($InputText)
    }
    $process.StandardInput.Close()
    $output = $process.StandardOutput.ReadToEnd()
    $errorOutput = $process.StandardError.ReadToEnd()
    $process.WaitForExit()
    if ($output) { Write-Host $output }
    if ($errorOutput) { Write-Host $errorOutput }
    return $process.ExitCode
}

Write-Host 'Accepting Android SDK licenses...'
$licenseExitCode = Invoke-SdkManager @('--licenses') (('y' + [Environment]::NewLine) * 100)
if ($licenseExitCode -ne 0) {
    throw "sdkmanager --licenses failed with exit code $licenseExitCode"
}

Write-Host 'Installing required Android SDK packages...'
$packageFailures = @()
foreach ($package in @('platform-tools', 'platforms;android-37.2', 'build-tools;36.0.0')) {
    $packageExitCode = Invoke-SdkManager @($package)
    if ($packageExitCode -ne 0) {
        $packageFailures += "$package (exit code $packageExitCode)"
    }
}

Ensure-Download $gradleUrl $gradleZip
$gradleStaging = Assert-StagingPath (Join-Path $toolCacheDir "gradle-$gradleVersion-staging")
if (-not (Test-Path -LiteralPath (Join-Path $gradleDir 'bin\gradle.bat') -PathType Leaf)) {
    Expand-ArchiveToStaging $gradleZip $gradleStaging
    $gradleExtracted = Join-Path $gradleStaging "gradle-$gradleVersion"
    if (-not (Test-Path -LiteralPath $gradleExtracted -PathType Container)) {
        throw "Gradle archive did not contain expected directory $gradleExtracted"
    }
    Move-Item -LiteralPath $gradleExtracted -Destination $gradleDir
    if (Test-Path -LiteralPath $gradleStaging) {
        Remove-Item -LiteralPath $gradleStaging -Recurse -Force
    }
}

$localProperties = Join-Path (Split-Path -Parent $PSScriptRoot) 'local.properties'
$escapedSdkDir = $androidSdkDir.Replace('\', '\\')
Set-Content -LiteralPath $localProperties -Value "sdk.dir=$escapedSdkDir" -Encoding ASCII

$adb = Join-Path $androidSdkDir 'platform-tools\adb.exe'
Write-Host "Android SDK: $androidSdkDir"
Write-Host "Gradle: $gradleDir"
Write-Host "ADB: $adb"
if ($packageFailures.Count -gt 0) {
    throw "Required Android SDK package installation failed: $($packageFailures -join ', ')"
}
Write-Host 'Android toolchain bootstrap complete.'

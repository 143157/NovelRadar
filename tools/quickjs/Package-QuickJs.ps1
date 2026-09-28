param(
    [Parameter(Mandatory = $true)][string] $CacheRoot,
    [Parameter(Mandatory = $true)][string] $RepositoryRoot
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem

$version = '1.0.15-nr1'
$upstreamVersion = '1.0.15'
$fixedTime = [DateTimeOffset]::new(2024, 1, 1, 0, 0, 0, [TimeSpan]::Zero)
$vendorMaven = Join-Path $RepositoryRoot 'vendor/maven/com/novelradar/vendor'
$downloads = Join-Path $CacheRoot 'downloads'
$build = Join-Path $CacheRoot 'build'

function New-RepackedArchive {
    param([string] $InputPath, [string] $OutputPath, [hashtable] $NativeFiles)

    $destination = Split-Path -Parent $OutputPath
    New-Item -ItemType Directory -Force $destination | Out-Null
    $inputStream = [IO.File]::OpenRead($InputPath)
    try {
        $inputZip = [IO.Compression.ZipArchive]::new($inputStream, [IO.Compression.ZipArchiveMode]::Read)
        try {
            $outputStream = [IO.File]::Create($OutputPath)
            try {
                $outputZip = [IO.Compression.ZipArchive]::new($outputStream, [IO.Compression.ZipArchiveMode]::Create)
                try {
                    foreach ($sourceEntry in ($inputZip.Entries | Sort-Object FullName)) {
                        if ($sourceEntry.FullName.StartsWith('jni/', [StringComparison]::Ordinal)) { continue }
                        $targetEntry = $outputZip.CreateEntry($sourceEntry.FullName, [IO.Compression.CompressionLevel]::Optimal)
                        $targetEntry.LastWriteTime = $fixedTime
                        if ($sourceEntry.FullName.EndsWith('/')) { continue }
                        $source = $sourceEntry.Open()
                        $target = $targetEntry.Open()
                        try { $source.CopyTo($target) }
                        finally { $target.Dispose(); $source.Dispose() }
                    }
                    $jniDirectory = $outputZip.CreateEntry('jni/')
                    $jniDirectory.LastWriteTime = $fixedTime
                    foreach ($entryName in ($NativeFiles.Keys | Sort-Object)) {
                        $directoryName = $entryName.Substring(0, $entryName.LastIndexOf('/') + 1)
                        $directory = $outputZip.CreateEntry($directoryName)
                        $directory.LastWriteTime = $fixedTime
                        $targetEntry = $outputZip.CreateEntry($entryName, [IO.Compression.CompressionLevel]::Optimal)
                        $targetEntry.LastWriteTime = $fixedTime
                        $source = [IO.File]::OpenRead($NativeFiles[$entryName])
                        $target = $targetEntry.Open()
                        try { $source.CopyTo($target) }
                        finally { $target.Dispose(); $source.Dispose() }
                    }
                } finally { $outputZip.Dispose() }
            } finally { $outputStream.Dispose() }
        } finally { $inputZip.Dispose() }
    } finally { $inputStream.Dispose() }
}

function Write-VendorPom {
    param([string] $Original, [string] $Output, [string] $Artifact)
    $pom = [IO.File]::ReadAllText($Original)
    $pom = [Text.RegularExpressions.Regex]::Replace($pom,
        '(?s)\s*<!-- This module was also published with a richer model, Gradle metadata,.*?<!-- do_not_remove: published-with-gradle-metadata -->\s*', "`n  ")
    $pom = $pom.Replace('<groupId>io.github.dokar3</groupId>', '<groupId>com.novelradar.vendor</groupId>')
    $pom = $pom.Replace('<version>1.0.15</version>', "<version>$version</version>")
    $pom = $pom.Replace('<description>A QuickJS binding for idiomatic Kotlin, with Async/DSL/ES Modules support.</description>',
        '<description>NovelRadar security-patched QuickJS-KT 1.0.15 native binaries; upstream Kotlin API and dependencies.</description>')
    [IO.File]::WriteAllText($Output, $pom, [Text.UTF8Encoding]::new($false))
}

$androidFiles = @{}
foreach ($abi in @('arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64')) {
    $native = Join-Path $build "android-$abi/libquickjs.so"
    if (-not (Test-Path $native)) { throw "Missing patched Android library: $native" }
    $androidFiles["jni/$abi/libquickjs.so"] = $native
}
$windowsNative = Join-Path $build 'windows_x64/libquickjs.dll'
if (-not (Test-Path $windowsNative)) { throw "Missing patched Windows library: $windowsNative" }
$windowsFiles = @{ 'jni/windows_x64/libquickjs.dll' = $windowsNative }

foreach ($target in @('android', 'jvm')) {
    $artifact = "quickjs-kt-$target"
    $ext = if ($target -eq 'android') { 'aar' } else { 'jar' }
    $outputDirectory = Join-Path $vendorMaven "$artifact/$version"
    New-Item -ItemType Directory -Force $outputDirectory | Out-Null
    $originalArchive = Join-Path $downloads "$artifact-$upstreamVersion.$ext"
    $outputArchive = Join-Path $outputDirectory "$artifact-$version.$ext"
    $nativeFiles = if ($target -eq 'android') { $androidFiles } else { $windowsFiles }
    New-RepackedArchive $originalArchive $outputArchive $nativeFiles
    Write-VendorPom (Join-Path $downloads "$artifact-$upstreamVersion.pom") `
        (Join-Path $outputDirectory "$artifact-$version.pom") $target
    Write-Host "Packed $outputArchive"
}

$hashLines = @()
foreach ($file in (Get-ChildItem $vendorMaven -Recurse -File | Sort-Object FullName)) {
    $relative = $file.FullName.Substring($RepositoryRoot.Length + 1).Replace('\', '/')
    $hashLines += "$((Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant())  $relative"
}
$hashPath = Join-Path $RepositoryRoot 'vendor/quickjs/artifacts.sha256'
[IO.File]::WriteAllLines($hashPath, $hashLines, [Text.UTF8Encoding]::new($false))
Write-Host "Wrote $hashPath"

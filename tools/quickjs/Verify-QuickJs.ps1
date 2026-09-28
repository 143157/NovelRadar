$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem

$repository = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$cache = Join-Path $repository '.tools/quickjs-native'
$downloads = Join-Path $cache 'downloads'
$build = Join-Path $cache 'build'
$maven = Join-Path $repository 'vendor/maven/com/novelradar/vendor'
$bin = Join-Path $cache 'android-ndk-r28c/toolchains/llvm/prebuilt/windows-x86_64/bin'

function Get-StreamHash([IO.Stream] $Stream) {
    $digest = [Security.Cryptography.SHA256]::Create()
    try { return [BitConverter]::ToString($digest.ComputeHash($Stream)).Replace('-', '') }
    finally { $digest.Dispose() }
}

function Get-EntryHash([IO.Compression.ZipArchiveEntry] $Entry) {
    $stream = $Entry.Open()
    try { return Get-StreamHash $stream }
    finally { $stream.Dispose() }
}

function Assert-Archive([string] $Target, [hashtable] $NativeFiles) {
    $artifact = "quickjs-kt-$Target"
    $extension = if ($Target -eq 'android') { 'aar' } else { 'jar' }
    $originalPath = Join-Path $downloads "$artifact-1.0.15.$extension"
    $repackedPath = Join-Path $maven "$artifact/1.0.15-nr1/$artifact-1.0.15-nr1.$extension"
    $original = [IO.Compression.ZipFile]::OpenRead($originalPath)
    $repacked = [IO.Compression.ZipFile]::OpenRead($repackedPath)
    try {
        foreach ($entry in $original.Entries) {
            if ($entry.FullName.StartsWith('jni/', [StringComparison]::Ordinal)) { continue }
            $copy = $repacked.GetEntry($entry.FullName)
            if ($null -eq $copy -or (Get-EntryHash $entry) -ne (Get-EntryHash $copy)) {
                throw "$artifact non-native entry changed: $($entry.FullName)"
            }
        }
        foreach ($entry in $repacked.Entries) {
            if ($entry.FullName.StartsWith('jni/', [StringComparison]::Ordinal)) { continue }
            if ($null -eq $original.GetEntry($entry.FullName)) {
                throw "$artifact has extra non-native entry: $($entry.FullName)"
            }
        }
        $actualNative = @($repacked.Entries | Where-Object {
            $_.FullName.StartsWith('jni/', [StringComparison]::Ordinal) -and
            -not $_.FullName.EndsWith('/')
        } | ForEach-Object FullName | Sort-Object)
        $expectedNative = @($NativeFiles.Keys | Sort-Object)
        if (($actualNative -join ',') -ne ($expectedNative -join ',')) {
            throw "$artifact native entries are wrong: $($actualNative -join ',')"
        }
        foreach ($entryName in $expectedNative) {
            $fileStream = [IO.File]::OpenRead($NativeFiles[$entryName])
            try { $buildHash = Get-StreamHash $fileStream }
            finally { $fileStream.Dispose() }
            if ((Get-EntryHash $repacked.GetEntry($entryName)) -ne $buildHash) {
                throw "$artifact contains a native library different from its patched build: $entryName"
            }
        }
    } finally { $original.Dispose(); $repacked.Dispose() }

    $upstreamPom = [IO.File]::ReadAllText((Join-Path $downloads "$artifact-1.0.15.pom"))
    $vendorPom = [IO.File]::ReadAllText((Join-Path $maven "$artifact/1.0.15-nr1/$artifact-1.0.15-nr1.pom"))
    $expression = '(?s)<dependencies>.*?</dependencies>'
    if ([regex]::Match($upstreamPom, $expression).Value -ne [regex]::Match($vendorPom, $expression).Value) {
        throw "$artifact POM dependencies changed"
    }
    [xml]$xml = $vendorPom
    if ($xml.project.groupId -ne 'com.novelradar.vendor' -or
        $xml.project.version -ne '1.0.15-nr1' -or
        $xml.project.artifactId -ne $artifact -or
        $vendorPom.Contains('published-with-gradle-metadata')) {
        throw "$artifact POM coordinate or metadata marker is wrong"
    }
    Write-Host "PASS ${artifact}: upstream classes/resources and dependencies preserved; only patched native entries"
}

$androidFiles = @{}
foreach ($abi in @('arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64')) {
    $androidFiles["jni/$abi/libquickjs.so"] = Join-Path $build "android-$abi/libquickjs.so"
}
$windowsFiles = @{ 'jni/windows_x64/libquickjs.dll' = (Join-Path $build 'windows_x64/libquickjs.dll') }
Assert-Archive 'android' $androidFiles
Assert-Archive 'jvm' $windowsFiles

$machines = @{
    'arm64-v8a' = 'AArch64'; 'armeabi-v7a' = 'ARM';
    'x86' = 'Intel 80386'; 'x86_64' = 'Advanced Micro Devices X86-64'
}
foreach ($abi in $machines.Keys) {
    $file = $androidFiles["jni/$abi/libquickjs.so"]
    $header = & (Join-Path $bin 'llvm-readelf.exe') -h $file
    $segments = & (Join-Path $bin 'llvm-readelf.exe') -l $file
    $exports = & (Join-Path $bin 'llvm-readelf.exe') --wide --dyn-syms $file
    if ($LASTEXITCODE -ne 0 -or ($header -join "`n") -notmatch [regex]::Escape("Machine:                           $($machines[$abi])")) {
        throw "$abi has wrong ELF machine"
    }
    $load = @($segments | Where-Object { $_ -match '^  LOAD\s' })
    if ($load.Count -eq 0 -or @($load | Where-Object { $_ -notmatch '\s0x4000\s*$' }).Count -ne 0) {
        throw "$abi does not have 16KB-compatible ELF load alignment"
    }
    if (@($exports | Select-String 'Java_com_dokar_quickjs_QuickJs_').Count -lt 29) {
        throw "$abi is missing JNI exports"
    }
    Write-Host "PASS $abi ELF machine, 16KB LOAD alignment, JNI exports"
}
$pe = & (Join-Path $bin 'llvm-readobj.exe') --file-headers --coff-exports $windowsFiles['jni/windows_x64/libquickjs.dll']
if ($LASTEXITCODE -ne 0 -or ($pe -join "`n") -notmatch 'Format: COFF-x86-64' -or
    ($pe -join "`n") -notmatch 'Name: Java_com_dokar_quickjs_QuickJs_newRuntime') {
    throw 'Windows DLL is not x64 PE or is missing JNI exports'
}
Write-Host 'PASS Windows x64 PE JNI exports'

$manifest = Join-Path $repository 'vendor/quickjs/artifacts.sha256'
foreach ($line in [IO.File]::ReadAllLines($manifest)) {
    $parts = $line -split '  ', 2
    if ($parts.Count -ne 2) { throw "Malformed artifact manifest line: $line" }
    $file = Join-Path $repository $parts[1]
    if ((Get-FileHash $file -Algorithm SHA256).Hash -ine $parts[0]) {
        throw "Artifact checksum mismatch: $($parts[1])"
    }
}
Write-Host 'PASS vendor artifact SHA256 manifest'

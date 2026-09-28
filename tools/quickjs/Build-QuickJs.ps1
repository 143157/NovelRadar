param(
    [Parameter(Mandatory = $true)][string] $JdkHome
)

$ErrorActionPreference = 'Stop'
$repository = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$cache = Join-Path $repository '.tools/quickjs-native'
$downloads = Join-Path $cache 'downloads'
$source = Join-Path $cache 'source'
$patchFile = Join-Path $repository 'vendor/quickjs/patches/0001-fix-js-error-mapping.patch'
New-Item -ItemType Directory -Force $downloads | Out-Null

function Assert-ExitCode([string] $Action) {
    if ($LASTEXITCODE -ne 0) { throw "$Action failed with exit code $LASTEXITCODE" }
}

function Ensure-Download([string] $Name, [string] $Url, [string] $Sha256) {
    $path = Join-Path $downloads $Name
    if (-not (Test-Path $path) -or (Get-Item $path).Length -eq 0) {
        & curl.exe -fL -C - --retry 3 --silent --show-error $Url -o $path
        Assert-ExitCode "Download $Name"
    }
    $actual = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash
    if ($actual -ine $Sha256) {
        throw "SHA256 mismatch for $Name (got $actual). Remove only that download and rerun."
    }
    return $path
}

function Ensure-Extracted([string] $Archive, [string] $Directory, [string] $Marker) {
    if (Test-Path (Join-Path $Directory $Marker)) { return }
    New-Item -ItemType Directory -Force $Directory | Out-Null
    & tar.exe -xf $Archive -C $Directory
    Assert-ExitCode "Extract $Archive"
    if (-not (Test-Path (Join-Path $Directory $Marker))) {
        throw "Extracted archive is missing $Marker"
    }
}

$ndkArchive = Ensure-Download 'android-ndk-r28c-windows.zip' `
    'https://dl.google.com/android/repository/android-ndk-r28c-windows.zip' `
    '6bec98ac2354d8a919760889a1a41d020132e5e8cfa1b1fe51610a72c36a466b'
$cmakeArchive = Ensure-Download 'cmake-3.22.1-windows.zip' `
    'https://dl.google.com/android/repository/cmake-3.22.1-windows.zip' `
    'c9a9d568452a20cf27d703cb21ef9529dd67bda6be048c4d4b884acb3ac3a2b8'
$zigArchive = Ensure-Download 'zig-windows-x86_64-0.12.0.zip' `
    'https://ziglang.org/download/0.12.0/zig-windows-x86_64-0.12.0.zip' `
    '2199eb4c2000ddb1fba85ba78f1fcf9c1fb8b3e57658f6a627a8e513131893f5'

$upstreamBase = 'https://repo.maven.apache.org/maven2/io/github/dokar3'
foreach ($target in @('android', 'jvm')) {
    $extension = if ($target -eq 'android') { 'aar' } else { 'jar' }
    $artifact = "quickjs-kt-$target"
    $hash = if ($target -eq 'android') {
        @{ aar = '406c3efb80d5fa3495df4a7f2663be8cc30c974fe5c2368effa929de77cf0ed8';
           pom = 'cc3ef4b3e8674771ef29d059c28935dd009e6dac7fb90765e497bd8414fe2bbc' }
    } else {
        @{ jar = 'c8a8d0b47e2c3cd6fb21ea618204ba0a04027e09e41dc036c0f60518823f3d6d';
           pom = 'b2958759b1b34a4cc6bd768926c4a29c76aaee578c2dfb80d8e2e1bbdd300936' }
    }
    foreach ($fileExtension in @($extension, 'pom')) {
        $name = "$artifact-1.0.15.$fileExtension"
        $null = Ensure-Download $name "$upstreamBase/$artifact/1.0.15/$name" $hash[$fileExtension]
    }
}

$null = Ensure-Download 'kotlin-stdlib-2.4.10.jar' `
    'https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-stdlib/2.4.10/kotlin-stdlib-2.4.10.jar' `
    '4ec0293bc3751423b203f1d8493251c57c42e73eb6377a6b8560d0974ff0a6df'
$null = Ensure-Download 'kotlinx-coroutines-core-jvm-1.11.0.jar' `
    'https://repo.maven.apache.org/maven2/org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/1.11.0/kotlinx-coroutines-core-jvm-1.11.0.jar' `
    'd1d75aa01dffbb4d1c520e67e4c4e7f5f6174718e7cb4632412503f2f0e604fa'

Ensure-Extracted $ndkArchive $cache 'android-ndk-r28c/source.properties'
Ensure-Extracted $cmakeArchive (Join-Path $cache 'cmake-3.22.1') 'bin/cmake.exe'
Ensure-Extracted $zigArchive $cache 'zig-windows-x86_64-0.12.0/zig.exe'

if (-not (Test-Path (Join-Path $source '.git'))) {
    & git clone --branch v1.0.15 --depth 1 --recurse-submodules --shallow-submodules `
        'https://github.com/dokar3/quickjs-kt.git' $source
    Assert-ExitCode 'Clone upstream QuickJS-KT source'
}
$expected = @{
    $source = 'a081755bcdb18ee6afa7ffee25b8fa976f06d31f'
    (Join-Path $source 'quickjs/native/quickjs') = '04be246001599f5995fa2f2d8c91a0f198d3f34c'
    (Join-Path $source 'quickjs/native/c-vector') = '774773d4cb1e66dd736e90e7f482d4f22af464c6'
}
foreach ($checkout in $expected.Keys) {
    $actual = (& git -C $checkout rev-parse HEAD).Trim()
    Assert-ExitCode "Read source revision $checkout"
    if ($actual -ne $expected[$checkout]) { throw "Unexpected source revision $checkout $actual" }
}

& git -C $source apply --reverse --check $patchFile 2>$null
if ($LASTEXITCODE -ne 0) {
    & git -C $source apply --check $patchFile
    Assert-ExitCode 'Check security patch'
    & git -C $source apply $patchFile
    Assert-ExitCode 'Apply security patch'
}
$currentPatch = Join-Path $cache 'source-current.patch'
& git -C $source diff --output=$currentPatch -- quickjs/native/jni/mapping/js_value_to_jobject.c
Assert-ExitCode 'Inspect applied security patch'
if ((Get-FileHash $currentPatch).Hash -ne (Get-FileHash $patchFile).Hash) {
    throw 'Applied source differs from reviewed security patch'
}
$dirty = @(& git -C $source status --porcelain --untracked-files=no)
Assert-ExitCode 'Inspect source modifications'
if ($dirty.Count -ne 1 -or $dirty[0] -ne ' M quickjs/native/jni/mapping/js_value_to_jobject.c') {
    throw "Unexpected source modifications: $($dirty -join ', ')"
}

$jdk = (Resolve-Path $JdkHome).Path
if (-not (Test-Path (Join-Path $jdk 'include/jni.h')) -or
    -not (Test-Path (Join-Path $jdk 'include/win32/jni_md.h'))) {
    throw 'JdkHome must point to a Windows JDK with JNI headers'
}
$cmake = Join-Path $cache 'cmake-3.22.1/bin/cmake.exe'
$env:PATH = (Join-Path $cache 'zig-windows-x86_64-0.12.0') + ';' +
    (Join-Path $cache 'cmake-3.22.1/bin') + ';' + $env:PATH
$nativeSource = Join-Path $source 'quickjs/native'
$toolchain = Join-Path $cache 'android-ndk-r28c/build/cmake/android.toolchain.cmake'
foreach ($abi in @('arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64')) {
    $buildDirectory = Join-Path $cache "build/android-$abi"
    & $cmake -S $nativeSource -B $buildDirectory -G Ninja `
        "-DCMAKE_TOOLCHAIN_FILE=$toolchain" "-DANDROID_ABI=$abi" `
        '-DANDROID_PLATFORM=android-23' '-DANDROID_TOOLCHAIN=clang' `
        '-DTARGET_PLATFORM=android' '-DBUILD_WITH_JNI=ON' `
        '-DLIBRARY_TYPE=shared' '-DCMAKE_BUILD_TYPE=MinSizeRel'
    Assert-ExitCode "Configure Android $abi"
    & $cmake --build $buildDirectory --parallel 4
    Assert-ExitCode "Build Android $abi"
}
$windowsBuild = Join-Path $cache 'build/windows_x64'
& $cmake -S $nativeSource -B $windowsBuild -G Ninja `
    '-DTARGET_PLATFORM=windows_x64' '-DBUILD_WITH_JNI=ON' `
    '-DLIBRARY_TYPE=shared' '-DCMAKE_BUILD_TYPE=MinSizeRel' `
    "-DPLATFORM_JAVA_HOME=$($jdk.Replace('\', '/'))"
Assert-ExitCode 'Configure Windows x64'
& $cmake --build $windowsBuild --parallel 4
Assert-ExitCode 'Build Windows x64'

& (Join-Path $PSScriptRoot 'Package-QuickJs.ps1') -CacheRoot $cache -RepositoryRoot $repository
Write-Host 'Built and packaged patched QuickJS-KT 1.0.15-nr1 artifacts.'

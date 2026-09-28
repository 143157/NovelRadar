param(
    [Parameter(Mandatory = $true)][string] $JdkHome
)

$ErrorActionPreference = 'Stop'
$repository = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$cache = Join-Path $repository '.tools/quickjs-native'
$downloads = Join-Path $cache 'downloads'
$classes = Join-Path $cache 'harness/classes'
$jdk = (Resolve-Path $JdkHome).Path
$javac = Join-Path $jdk 'bin/javac.exe'
$java = Join-Path $jdk 'bin/java.exe'
$source = Join-Path $PSScriptRoot 'NativeSecurityRegression.java'
$original = Join-Path $downloads 'quickjs-kt-jvm-1.0.15.jar'
$patched = Join-Path $repository `
    'vendor/maven/com/novelradar/vendor/quickjs-kt-jvm/1.0.15-nr1/quickjs-kt-jvm-1.0.15-nr1.jar'
$stdlib = Join-Path $downloads 'kotlin-stdlib-2.4.10.jar'
$coroutines = Join-Path $downloads 'kotlinx-coroutines-core-jvm-1.11.0.jar'
foreach ($file in @($javac, $java, $source, $original, $patched, $stdlib, $coroutines)) {
    if (-not (Test-Path $file)) { throw "Missing harness input $file. Run Build-QuickJs.ps1 first." }
}
New-Item -ItemType Directory -Force $classes | Out-Null
$compileClasspath = "$original;$stdlib;$coroutines"
& $javac -encoding UTF-8 -cp $compileClasspath -d $classes $source
if ($LASTEXITCODE -ne 0) { throw 'Harness compilation failed' }

$mainClass = 'com.novelradar.quickjs.NativeSecurityRegression'
Write-Host 'Proving the published unmodified native JAR activates the harmless sentinel:'
& $java -cp "$classes;$compileClasspath" $mainClass --expect-legacy
if ($LASTEXITCODE -ne 0) { throw 'Legacy sentinel reproduction failed' }

Write-Host 'Verifying the packaged patched Windows native JAR:'
& $java -cp "$classes;$patched;$stdlib;$coroutines" $mainClass
if ($LASTEXITCODE -ne 0) { throw 'Patched native regression failed' }

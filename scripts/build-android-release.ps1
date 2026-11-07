param(
    [Parameter(Mandatory = $true)] [string] $Keystore,
    [Parameter(Mandatory = $true)] [string] $Alias
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$keystorePath = (Resolve-Path -LiteralPath $Keystore).Path
$sdk = $env:ANDROID_SDK_ROOT
if (-not $sdk) { $sdk = $env:ANDROID_HOME }
if (-not $sdk) { $sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$toolsRoot = Join-Path $sdk 'build-tools'
if (-not (Test-Path -LiteralPath $toolsRoot)) { throw 'Android SDK build-tools not found. Set ANDROID_SDK_ROOT or ANDROID_HOME.' }
$tools = Get-ChildItem -LiteralPath $toolsRoot -Directory | Where-Object {
    (Test-Path (Join-Path $_.FullName 'apksigner.bat')) -and (Test-Path (Join-Path $_.FullName 'zipalign.exe'))
} | Sort-Object { try { [version]$_.Name } catch { [version]'0.0' } } -Descending | Select-Object -First 1
if (-not $tools) { throw 'Install Android SDK build-tools containing apksigner and zipalign.' }
$signer = Join-Path $tools.FullName 'apksigner.bat'
$aligner = Join-Path $tools.FullName 'zipalign.exe'
Push-Location $root
try {
    & .\gradlew.bat :composeApp:assembleRelease --no-daemon
    if ($LASTEXITCODE -ne 0) { throw "Release build failed (exit code $LASTEXITCODE)." }
    $out = Join-Path $root 'composeApp\build\outputs\apk\release'
    $unsigned = Join-Path $out 'composeApp-release-unsigned.apk'
    $aligned = Join-Path $out 'composeApp-release-aligned.apk'
    $signed = Join-Path $out 'HabitLab-release.apk'
    if (-not (Test-Path -LiteralPath $unsigned)) { throw 'Unsigned release APK not found; check Gradle signing configuration.' }
    & $aligner -f -p 4 $unsigned $aligned
    if ($LASTEXITCODE -ne 0) { throw 'APK alignment failed.' }
    # apksigner prompts for the keystore and key passwords; none are stored in the script.
    & $signer sign --ks $keystorePath --ks-key-alias $Alias --out $signed $aligned
    if ($LASTEXITCODE -ne 0) { throw 'APK signing failed. Check the key alias and passwords.' }
    & $signer verify --verbose $signed
    if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }
    Write-Host "Signed release APK: $signed"
} finally {
    Pop-Location
}

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $root '.env'
if (-not (Test-Path -LiteralPath $envFile -PathType Leaf)) {
    throw 'The existing .env file is missing. Restore it before rebuilding so existing login tokens remain valid; see docs/docker.md.'
}
if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { throw 'Docker is not installed or is not on PATH.' }
# Compose environment variables take precedence over --env-file. Use the saved
# settings, even if a different value happens to be set in this shell.
$oldSecret = $env:HABITLAB_SYNC_SECRET
$oldSignup = $env:HABITLAB_SYNC_ALLOW_SIGNUP
try {
    Remove-Item Env:HABITLAB_SYNC_SECRET, Env:HABITLAB_SYNC_ALLOW_SIGNUP -ErrorAction SilentlyContinue
    & docker compose --project-name rabit --project-directory $root --env-file $envFile up --build -d
    if ($LASTEXITCODE -ne 0) { throw "Docker Compose failed (exit code $LASTEXITCODE)." }
} finally {
    $env:HABITLAB_SYNC_SECRET = $oldSecret
    $env:HABITLAB_SYNC_ALLOW_SIGNUP = $oldSignup
}
$healthy = $false
for ($i = 0; $i -lt 30; $i++) {
    try {
        $response = Invoke-WebRequest -Uri 'http://127.0.0.1:8787/health' -UseBasicParsing -TimeoutSec 2
        if ($response.StatusCode -eq 200) { $healthy = $true; break }
    } catch { }
    Start-Sleep -Seconds 1
}
if (-not $healthy) { throw 'The container started, but the health check did not respond. Check Docker Desktop logs.' }
Write-Host 'Habit Lab is running at http://127.0.0.1:8787/'

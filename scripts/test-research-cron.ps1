$ErrorActionPreference = 'Stop'
$script = Join-Path $PSScriptRoot 'configure-research-cron.ps1'
$tokens = $null
$parseErrors = $null
$null = [System.Management.Automation.Language.Parser]::ParseFile($script,[ref]$tokens,[ref]$parseErrors)
if ($parseErrors.Count -ne 0) { throw 'Cron script syntax invalid.' }
# Default must not even look for psql or read encrypted project Secret files.
$preview = (& $script | Out-String)
if ($preview -notmatch 'Preview only' -or $preview -notmatch 'enabled boolean := false' -or $preview -match '__ENABLE__') { throw 'Default preview is not inactive.' }
$blocked = $false
try { & $script -Mode Enable } catch { $blocked = $_.Exception.Message -like '*SupplierSmokeVerified*' }
if (-not $blocked) { throw 'Activation without actual smoke acknowledgment was not blocked.' }
$priorRef = $env:BOOMERANG_PROJECT_REF
$priorUrl = $env:BOOMERANG_DATABASE_URL
try {
    $env:BOOMERANG_PROJECT_REF = 'another-project'
    $blocked = $false
    try { & $script -Mode InstallInactive } catch { $blocked = $_.Exception.Message -like '*dedicated boomerang*' }
    if (-not $blocked) { throw 'Wrong project was not rejected before connection.' }
    $env:BOOMERANG_PROJECT_REF = 'skeghmapzrmahxehazlp'
    $env:BOOMERANG_DATABASE_URL = 'postgresql://postgres:synthetic-test-only@db.skeghmapzrmahxehazlp.supabase.co/postgres?host=attacker.example'
    $blocked = $false
    try { & $script -Mode InstallInactive } catch { $blocked = $_.Exception.Message -like '*dedicated boomerang*' }
    if (-not $blocked) { throw 'Connection query override was not rejected before connection.' }
} finally {
    $env:BOOMERANG_PROJECT_REF = $priorRef
    $env:BOOMERANG_DATABASE_URL = $priorUrl
}
Write-Output 'Cron script checks passed: syntax, inactive preview, activation gate, project boundary, connection override rejection. No network calls.'

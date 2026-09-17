[CmdletBinding()]
param(
    [ValidateSet('Preview','InstallInactive','Enable','Disable')]
    [string]$Mode = 'Preview',
    [switch]$SupplierSmokeVerified
)
$ErrorActionPreference = 'Stop'
$projectRef = 'skeghmapzrmahxehazlp'
$templatePath = Join-Path $PSScriptRoot 'research-cron.sql'
$sql = Get-Content -LiteralPath $templatePath -Raw

if ($Mode -eq 'Preview') {
    Write-Output 'Preview only: no database connection, no Secret read, no scheduled jobs changed.'
    Write-Output 'InstallInactive: install two inactive jobs. Enable: requires explicit SupplierSmokeVerified and a pre-provisioned Vault WORKER_SECRET.'
    Write-Output $sql.Replace('__ENABLE__', 'false')
    return
}
if ($Mode -eq 'Enable' -and -not $SupplierSmokeVerified) {
    throw 'Enable requires -SupplierSmokeVerified after actual provider and worker smoke. No changes made.'
}
if ($env:BOOMERANG_PROJECT_REF -ne $projectRef) {
    throw 'BOOMERANG_PROJECT_REF must identify the dedicated boomerang project. No changes made.'
}
if ([string]::IsNullOrWhiteSpace($env:BOOMERANG_DATABASE_URL)) {
    throw 'BOOMERANG_DATABASE_URL must be supplied by the secret environment. No changes made.'
}
try { $connection = [Uri]$env:BOOMERANG_DATABASE_URL } catch { throw 'Invalid database connection setting.' }
$direct = $connection.Host -eq "db.$projectRef.supabase.co"
$pooler = $connection.Host.EndsWith('.pooler.supabase.com') -and $connection.UserInfo.StartsWith("postgres.${projectRef}:")
if ($connection.Scheme -notin @('postgres','postgresql') -or (-not $direct -and -not $pooler) -or $connection.AbsolutePath -ne '/postgres' -or $connection.Query -notmatch '^(\?sslmode=(require|verify-ca|verify-full))?$') {
    throw 'Database connection must target the dedicated boomerang project postgres database.'
}
# Do not print the connection string or pass credentials in process arguments.
$psql = Get-Command psql -ErrorAction Stop
if ($Mode -eq 'Disable') {
    $sql = @'
BEGIN;
DO $disable$ DECLARE j record; BEGIN
 FOR j IN SELECT jobid FROM cron.job WHERE jobname IN ('boomerang-enqueue-due','boomerang-worker') LOOP
  PERFORM cron.alter_job(j.jobid,active:=false);
 END LOOP;
END $disable$;
COMMIT;
SELECT jobname,active FROM cron.job WHERE jobname IN ('boomerang-enqueue-due','boomerang-worker') ORDER BY jobname;
'@
} else {
    $sql = $sql.Replace('__ENABLE__', $(if ($Mode -eq 'Enable') { 'true' } else { 'false' }))
}
$previousDatabase = $env:PGDATABASE
$previousSsl = $env:PGSSLMODE
try {
    $env:PGDATABASE = $env:BOOMERANG_DATABASE_URL
    $env:PGSSLMODE = 'require'
    # SQL contains Vault references only. No secret material is copied to files or logs.
    $sql | & $psql.Source -X -v ON_ERROR_STOP=1 -q
    if ($LASTEXITCODE -ne 0) { throw 'Cron configuration failed; transaction rolled back. Check extension permissions and Vault configuration.' }
} finally {
    $env:PGDATABASE = $previousDatabase
    $env:PGSSLMODE = $previousSsl
}

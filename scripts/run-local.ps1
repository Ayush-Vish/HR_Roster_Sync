param([switch]$PrepareOnly)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
Push-Location $projectRoot
try {
    $jdk = Get-ChildItem .tools -Directory -Filter 'jdk-*' | Sort-Object Name -Descending | Select-Object -First 1
    $maven = "$projectRoot/.tools/apache-maven-3.9.9/bin/mvn.cmd"
    $pg = "$projectRoot/.tools/postgres/pgsql/bin"
    if (!$jdk -or !(Test-Path $maven) -or !(Test-Path "$pg/pg_ctl.exe")) {
        throw 'Local toolchain missing. Use docker compose up --build instead; see README.md.'
    }
    $env:JAVA_HOME = $jdk.FullName
    & "$pg/pg_ctl.exe" -D .local/pgdata status
    if ($LASTEXITCODE -ne 0) {
        & "$pg/pg_ctl.exe" -D .local/pgdata -l .local/postgres.log -o '-p 55432 -h 127.0.0.1' start
        if ($LASTEXITCODE -ne 0) { throw 'Could not start the local database.' }
    }
    $exists = & "$pg/psql.exe" -h 127.0.0.1 -p 55432 -U roster -d postgres -tAc "SELECT 1 FROM pg_database WHERE datname='roster_local'"
    if ($LASTEXITCODE -ne 0) { throw 'Could not connect to PostgreSQL.' }
    if (($exists -join '').Trim() -ne '1') {
        & "$pg/createdb.exe" -h 127.0.0.1 -p 55432 -U roster roster_local
        if ($LASTEXITCODE -ne 0) { throw 'Could not create demo database.' }
    }
    & $maven -B package -DskipTests
    if ($LASTEXITCODE -ne 0) { throw 'Build failed. If another demo is running, stop it with Ctrl+C first.' }
    if (!$PrepareOnly) {
        $env:DB_URL = 'jdbc:postgresql://127.0.0.1:55432/roster_local'
        $env:DB_USER = 'roster'
        $env:WORKER_ENABLED = 'true'
        $env:SERVER_ADDRESS = '127.0.0.1'
        $env:SERVER_PORT = '8080'
        Write-Host 'Starting API at http://localhost:8080. Press Ctrl+C to stop the API.'
        & "$env:JAVA_HOME/bin/java.exe" -Xmx256m -jar target/roster-sync-1.0.0.jar
    }
} finally {
    Pop-Location
}

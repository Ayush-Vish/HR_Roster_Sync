param(
    [Parameter(Mandatory=$true)][string]$Java,
    [string]$DatabaseUrl='jdbc:postgresql://127.0.0.1:55432/roster_failure',
    [int]$Port=8081
)
$ErrorActionPreference='Stop'
$env:DB_URL=$DatabaseUrl
$env:SERVER_PORT="$Port"
$env:APP_PASSWORD='local-demo-password'
$root=Split-Path $PSScriptRoot -Parent
$reportDir=Join-Path $root '.local'
New-Item -ItemType Directory -Force $reportDir | Out-Null
$base="http://127.0.0.1:$Port"
function Headers($role) {
    @{Authorization='Basic '+[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("acme-${role}:local-demo-password"))}
}
function Wait-Ready {
    for($i=0;$i -lt 60;$i++) {
        try { $null=Invoke-RestMethod "$base/actuator/health"; return } catch { Start-Sleep -Milliseconds 500 }
    }
    throw 'Application did not start'
}
function Launch($enabled,$suffix) {
    $env:WORKER_ENABLED=$enabled
    Start-Process -FilePath $Java -ArgumentList '-Xmx256m','-jar',"$root/target/roster-sync-1.0.0.jar" -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput "$reportDir/crash-$suffix.log" -RedirectStandardError "$reportDir/crash-$suffix-errors.log"
}
$process=$null
try {
    $process=Launch 'false' 'before'
    Wait-Ready
    $version=[DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
    $employee="CRASH$version"
    $body=@(@{employeeId=$employee;version=$version;type='CREATE';name='Synthetic';email='synthetic@example.test';phone='919000000001';office='BLR'})
    $null=Invoke-RestMethod "$base/api/events" -Method Post -Headers (Headers 'hr') -ContentType 'application/json' -Body (ConvertTo-Json -InputObject $body -Compress)
    $booking=Invoke-RestMethod "$base/api/bookings" -Method Post -Headers (Headers 'admin') -ContentType 'application/json' -Body (@{employeeId=$employee;startsAt=[DateTimeOffset]::UtcNow.AddDays(1).ToString('o')} | ConvertTo-Json)
    $body=@(@{employeeId=$employee;version=$version+1;type='DEACTIVATE'})
    $null=Invoke-RestMethod "$base/api/events" -Method Post -Headers (Headers 'hr') -ContentType 'application/json' -Body (ConvertTo-Json -InputObject $body -Compress)
    $events=Invoke-RestMethod "$base/api/outbox" -Headers (Headers 'operator')
    $pending=@($events | Where-Object {$_.employee_id -eq $employee -and $null -eq $_.delivered_at})
    if($pending.Count -ne 2) {throw 'Expected two durable pending events before kill'}
    Stop-Process -Id $process.Id -Force
    $process.WaitForExit()
    $process=Launch 'true' 'after'
    Wait-Ready
    $cancelled=$false
    for($i=0;$i -lt 40;$i++) {
        $rows=Invoke-RestMethod "$base/api/bookings" -Headers (Headers 'admin')
        $found=$rows | Where-Object {$_.id -eq $booking.id}
        if($found.status -eq 'CANCELLED' -and !$found.seat_reserved) {$cancelled=$true;break}
        Start-Sleep -Milliseconds 250
    }
    if(!$cancelled) {throw 'Booking was not cancelled after restart'}
    @{scenario='kill after commit before publication';passed=$true;pendingBeforeKill=$pending.Count;bookingCancelled=$cancelled} |
        ConvertTo-Json | Set-Content (Join-Path $root 'docs/crash-recovery-results.json')
    Write-Output 'PASS: durable outbox recovered after process kill; booking cancelled and seat freed.'
} finally {
    if($process -and !$process.HasExited) {Stop-Process -Id $process.Id -Force}
}

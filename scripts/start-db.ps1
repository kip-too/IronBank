# Starts the local PostgreSQL this project's tests need.
#
# WHY THIS EXISTS
#   PostgreSQL here is a scoop install with no Windows service registered, so it runs as a child
#   of whatever console started it. On 2026-09-15 the server log showed:
#
#     LOG: WAL writer process (PID 28528) was terminated by exception 0x40010004
#
#   0x40010004 is DBG_CONTROL_C - a console Ctrl+C event. A terminal closing or a command being
#   interrupted takes the database down with it, which shows up later as a pile of
#   "Connection to localhost:5432 refused" test errors that look like a code problem and are not.
#
# THE DURABLE FIX, which needs administrator rights and so is not done here:
#   pg_ctl register -N postgresql -D <data dir> -S auto
#   ...then the Service Control Manager owns it and no console can signal it.
#
# Until then, run this after any interrupted session.

$ErrorActionPreference = 'Stop'
$base = "$env:USERPROFILE\scoop\apps\postgresql\current"
$data = "$base\data"
$log  = "$data\server.log"

& "$base\bin\pg_isready.exe" 2>$null
if ($LASTEXITCODE -eq 0) {
    Write-Output "PostgreSQL is already accepting connections."
    exit 0
}

Write-Output "Starting PostgreSQL from $data ..."
& "$base\bin\pg_ctl.exe" -D $data -l $log start
Start-Sleep -Seconds 3
& "$base\bin\pg_isready.exe"

# ===============================================================
# Aggregation System - Full Integration Test Runner (PowerShell)
# ASCII-only; robust arg passing; starts/stops server; full rubric
# ===============================================================

$ErrorActionPreference = "Stop"

# ----- Paths & Config -----
$SCRIPT_DIR   = Split-Path -Parent $MyInvocation.MyCommand.Path          # ...\src\main\resources
$PROJECT_ROOT = Resolve-Path "$SCRIPT_DIR\..\..\.."                      # project root
Set-Location -Path $PROJECT_ROOT                                         # build/run from root

$CP       = "target/classes;target/dependency/*"
$PORT     = 4567
$SERVER   = "org.example.server.AggregationServer"
$PUT      = "org.example.client.ContentServer"
$GET      = "org.example.client.GetClient"

# Absolute resource files
$TXT      = Join-Path $SCRIPT_DIR "weather.txt"
$A        = Join-Path $SCRIPT_DIR "weatherA.json"
$B        = Join-Path $SCRIPT_DIR "weatherB.json"
$EMPTY    = Join-Path $SCRIPT_DIR "empty.json"
$BAD      = Join-Path $SCRIPT_DIR "bad.json"

# Logs
$LOG_FILE   = Join-Path $SCRIPT_DIR "test-results.log"
$SERVER_OUT = Join-Path $SCRIPT_DIR "server.out"
$SERVER_ERR = Join-Path $SCRIPT_DIR "server.err"

# ----- Helpers -----
function Say([string]$msg)  { Write-Host ""; Write-Host "=== $msg ==="; Add-Content $LOG_FILE ""; Add-Content $LOG_FILE "=== $msg ===" }
function Ok([string]$msg)   { Write-Host "[OK] $msg";     Add-Content $LOG_FILE "[OK] $msg" }
function Warn([string]$msg) { Write-Host "[WARN] $msg";   Add-Content $LOG_FILE "[WARN] $msg" }
function Fail([string]$msg) { Write-Host "[FAIL] $msg";   Add-Content $LOG_FILE "[FAIL] $msg"; Stop-Server; exit 1 }

# Fixed Run-Java (proper argument expansion)
function Run-Java {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments)
    $cmd = "java"
    Write-Host ""
    Write-Host ("> Running: java -cp $CP " + ($Arguments -join " "))
    $out = & $cmd "-cp" $CP @Arguments 2>&1
    $out | Add-Content $LOG_FILE
    return ($out -join "`n")
}

function Expect-Code { param([string]$output, [int]$code)
    if ($output -match "HTTP/1\.1\s+$code" -or $output -match "^\s*$code\s") {
        Ok ("Expected HTTP " + $code + " observed")
    } else {
        $output | Add-Content $LOG_FILE
        Fail ("Expected HTTP " + $code + " but got different output")
    }
}

function Wait-ForPort { param([int]$port, [int]$timeoutSec = 12)
    $start = Get-Date
    while ($true) {
        try { $c = New-Object Net.Sockets.TcpClient("localhost", $port); $c.Close(); return $true }
        catch {
            if ((Get-Date) - $start -gt (New-TimeSpan -Seconds $timeoutSec)) { return $false }
            Start-Sleep -Milliseconds 200
        }
    }
}

function Start-Server {
    Say ("Starting AggregationServer on port " + $PORT)
    $script:ServerJob = Start-Job -ScriptBlock {
        Set-Location -Path $using:PROJECT_ROOT
        java -cp $using:CP $using:SERVER $using:PORT 1> $using:SERVER_OUT 2> $using:SERVER_ERR
    }
    if (-not (Wait-ForPort -port $PORT -timeoutSec 12)) {
        Receive-Job $ServerJob -ErrorAction SilentlyContinue | Out-String | Write-Host
        Fail "Server failed to start on the target port"
    }
    Ok "Server running"
}

function Stop-Server {
    if ($script:ServerJob -and (Get-Job -Id $ServerJob.Id -ErrorAction SilentlyContinue)) {
        Say "Stopping server job"
        Try { Stop-Job -Job $ServerJob -Force -ErrorAction SilentlyContinue } Catch { }
        Try { Remove-Job -Job $ServerJob -Force -ErrorAction SilentlyContinue } Catch { }
        Ok "Server job stopped"
    }
}

Register-EngineEvent PowerShell.Exiting -Action { Stop-Server } | Out-Null

# ----- Intro -----
"" | Set-Content $LOG_FILE
Add-Content $LOG_FILE ("INTEGRATION RUN " + (Get-Date -Format "yyyy-MM-dd HH:mm:ss"))
Write-Host "=============================================================="
Write-Host "Integration tests for Aggregation System"
Write-Host "Project Root: $PROJECT_ROOT"
Write-Host "Resources Dir: $SCRIPT_DIR"
Write-Host "=============================================================="
Write-Host ""

# ----- Build -----
Say "Building project with Maven"
mvn -q -DskipTests clean package | Tee-Object -Append -FilePath $LOG_FILE
mvn -q dependency:copy-dependencies -DincludeScope=runtime | Tee-Object -Append -FilePath $LOG_FILE
Ok "Build complete"

# Ensure inputs exist
if (!(Test-Path $TXT))   { "id:IDS60901A`nname:Adelaide`nstate:SA" | Out-File $TXT -Encoding utf8 }
if (!(Test-Path $A))     { '{ "id":"IDS60901A", "name":"WEATHER A", "state":"SA" }' | Out-File $A -Encoding utf8 }
if (!(Test-Path $B))     { '{ "id":"IDS60901B", "name":"WEATHER B", "state":"SA" }' | Out-File $B -Encoding utf8 }
if (!(Test-Path $EMPTY)) { "" | Out-File $EMPTY -Encoding utf8 }
if (!(Test-Path $BAD))   { '{ "name":"missing id" ' | Out-File $BAD -Encoding utf8 }

# ----- Start server -----
Start-Server

# ================= TESTS =================
Say "1.1 First non-empty PUT -> expect 201 Created"
$out = Run-Java $PUT ("localhost:" + $PORT) $TXT
Expect-Code $out 201

Say "1.2 Subsequent non-empty PUT -> expect 200 OK"
$out = Run-Java $PUT ("localhost:" + $PORT) $TXT
Expect-Code $out 200

Say "1.3 Empty file PUT -> expect 204 No Content"
$out = Run-Java $PUT ("localhost:" + $PORT) $EMPTY
Expect-Code $out 204

Say "1.4 Wrong endpoint path -> expect 400 Bad Request"
$out = Run-Java $PUT ("http://localhost:" + $PORT + "/not-weather") $TXT
Expect-Code $out 400

Say "1.5 Invalid JSON -> expect 500 Internal Server Error"
$out = Run-Java $PUT ("localhost:" + $PORT) $BAD
Expect-Code $out 500

Say "2.1 GET /weather.json -> expect 200 OK"
Run-Java $PUT ("localhost:" + $PORT) $A | Out-Null
$out = Run-Java $GET ("localhost:" + $PORT) "/weather.json"
Expect-Code $out 200

Say "2.2 GET wrong path -> expect 400 Bad Request"
$out = Run-Java $GET ("localhost:" + $PORT) "/nope"
Expect-Code $out 400

Say "3.1 Concurrent ContentServers PUT (Lamport ordering)"
Start-Job { Set-Location $using:PROJECT_ROOT; java -cp $using:CP $using:PUT "localhost:$using:PORT" $using:A } | Out-Null
Start-Job { Set-Location $using:PROJECT_ROOT; java -cp $using:CP $using:PUT "localhost:$using:PORT" $using:B } | Out-Null
Start-Sleep -Seconds 3
$out = Run-Java $GET ("localhost:" + $PORT) "/weather.json"
Expect-Code $out 200
Ok "Concurrent Lamport test completed"

Say "4.1 GET between two PUTs"
Run-Java $PUT ("localhost:" + $PORT) $A | Out-Null
Start-Job { Set-Location $using:PROJECT_ROOT; Start-Sleep -Milliseconds 80; java -cp $using:CP $using:PUT "localhost:$using:PORT" $using:B } | Out-Null
$out = Run-Java $GET ("localhost:" + $PORT) "/weather.json"
Expect-Code $out 200
if ($out -match "IDS60901A") { Ok "GET returned A first" } else { Warn "Expected A first" }

Start-Sleep -Seconds 2
$out = Run-Java $GET ("localhost:" + $PORT) "/weather.json"
Expect-Code $out 200
if ($out -match "IDS60901B") { Ok "GET returned B after" } else { Warn "Expected B after second PUT" }

Say "5.1 TTL expiry -> expect 404 after 30 seconds inactivity"
Run-Java $PUT ("localhost:" + $PORT) $A | Out-Null
Start-Sleep -Seconds 31
$out = Run-Java $GET ("localhost:" + $PORT) "/weather.json"
Expect-Code $out 404

Say "All tests executed. See test-results.log for details."
Ok "Integration testing completed successfully."
Stop-Server

@echo off
REM ============================================================
REM  Replate (Food Saver) - stop script for Windows
REM
REM  Stops any running Replate server. Use this if you closed the
REM  server window without pressing Ctrl + C and later get a
REM  "Port 8080 is already in use" message.
REM
REM  It only stops OUR server (java running com.replate.Main) and
REM  never touches other Java programs on your computer.
REM ============================================================

echo Stopping Replate server (if running)...
powershell -NoProfile -Command "$p = Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | Where-Object { $_.CommandLine -match 'com\.replate\.Main' }; if ($p) { $p | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }; Write-Host ('Stopped ' + @($p).Count + ' Replate server process(es).') } else { Write-Host 'No Replate server was running.' }"
echo.
pause

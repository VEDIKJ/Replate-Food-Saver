@echo off
setlocal EnableDelayedExpansion
REM ============================================================
REM  Replate (Food Saver) - start script for Windows
REM
REM  This compiles the Java backend and starts the web server.
REM  You only need Java installed (JDK 11 or newer). No Maven,
REM  no separate Tomcat install - the server is embedded.
REM
REM  Just double-click this file, or run  run.bat  in a terminal.
REM ============================================================

REM Move to the folder this script is in (the project folder).
cd /d "%~dp0"

REM ------------------------------------------------------------
REM  Stop any Replate server that is already running.
REM
REM  Without this, closing the window without pressing Ctrl + C can
REM  leave an old server alive. The next run then reports
REM  "Port 8080 is already in use", and repeated attempts pile up
REM  several stuck Java processes that serve stale, inconsistent data.
REM  Killing only our own server (identified by com.replate.Main on its
REM  command line) guarantees every run starts from one clean instance
REM  and never touches other Java programs (editors, etc.).
REM ------------------------------------------------------------
echo Stopping any previous Replate server...
powershell -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | Where-Object { $_.CommandLine -match 'com\.replate\.Main' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }" >nul 2>&1

REM Replate now stores its data in MySQL. Remind the user to set up credentials
REM if neither db.properties nor the DB_* environment variables are present.
if not exist db.properties (
  if "%DB_USER%"=="" (
    echo.
    echo  NOTE: No db.properties found and DB_USER is not set.
    echo        Copy db.properties.example to db.properties and fill in your
    echo        MySQL user/password, and run database.sql once to create the
    echo        tables. See README.md for step-by-step instructions.
    echo.
  )
)

echo Compiling the Java backend...
if not exist out mkdir out

REM Java 26 can show an AccessDenied compiler message when it closes JAR files
REM directly from a OneDrive folder. Use temporary copies for compilation.
set "COMPILE_LIB=%TEMP%\replate-lib"
if not exist "%COMPILE_LIB%" mkdir "%COMPILE_LIB%"
copy /y lib\*.jar "%COMPILE_LIB%\" >nul

REM Build a quoted list of all Java files. Quoting each file keeps spaces in
REM the project folder (for example, "Replate 5.0") from breaking javac.
set "JAVA_FILES="
for /r src\main\java %%f in (*.java) do set "JAVA_FILES=!JAVA_FILES! "%%f""
set "JAVAC_LOG=%TEMP%\replate-javac.log"
javac -cp "%COMPILE_LIB%\*" -d out !JAVA_FILES! 2> "%JAVAC_LOG%"
if errorlevel 1 (
  type "%JAVAC_LOG%"
  echo.
  echo Build FAILED. Please check the error messages above.
  pause
  exit /b 1
)
del "%JAVAC_LOG%" >nul 2>&1
echo.
echo Starting Replate... open http://localhost:8080 in your browser.
echo Press Ctrl + C to stop the server.
echo.

REM Run the server. "out" has our compiled code; "lib/*" has the libraries.
java -cp "out;lib/*" com.replate.Main

pause

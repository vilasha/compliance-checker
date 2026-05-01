@echo off
echo ========================================
echo Compliance Checker - Environment Check
echo ========================================
echo.

set PASS=0
set FAIL=0

echo Docker containers:
docker ps | findstr compliance-postgres >nul 2>&1
if %errorlevel% equ 0 (
    echo   OK: PostgreSQL running
    set /a PASS+=1
) else (
    echo   FAIL: PostgreSQL not running
    set /a FAIL+=1
)

docker ps | findstr compliance-ollama >nul 2>&1
if %errorlevel% equ 0 (
    echo   OK: Ollama running
    set /a PASS+=1
) else (
    echo   FAIL: Ollama not running
    set /a FAIL+=1
)
echo.

echo PostgreSQL:
docker exec compliance-postgres pg_isready -U postgres >nul 2>&1
if %errorlevel% equ 0 (
    echo   OK: Connection healthy
    set /a PASS+=1
) else (
    echo   FAIL: Cannot connect
    set /a FAIL+=1
)
echo.

echo Ollama:
docker exec compliance-ollama ollama list >nul 2>&1
if %errorlevel% equ 0 (
    echo   OK: API responding
    set /a PASS+=1
) else (
    echo   FAIL: API not responding
    set /a FAIL+=1
)
echo.

echo Installed models:
docker exec compliance-ollama ollama list
echo.

echo ========================================
echo Results: %PASS% passed, %FAIL% failed
echo ========================================
echo.

if %FAIL% gtr 0 (
    echo Some checks failed. Run these commands to fix:
    echo   docker compose up -d
    echo   infra\setup-ollama-models.bat
)

pause
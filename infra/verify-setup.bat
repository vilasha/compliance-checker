@echo off
echo ========================================
echo Compliance Checker - Environment Check
echo ========================================
echo.

echo Checking Docker containers...
echo.
docker-compose ps
echo.

echo ========================================
echo Testing PostgreSQL connection...
echo ========================================
docker exec compliance-postgres psql -U postgres -d compliance_db -c "SELECT version();"
if %errorlevel% neq 0 (
    echo ERROR: Cannot connect to PostgreSQL
    pause
    exit /b 1
)
echo.

echo ========================================
echo Checking PGVector extension...
echo ========================================
docker exec compliance-postgres psql -U postgres -d compliance_db -c "SELECT * FROM pg_available_extensions WHERE name = 'vector';"
echo.

echo ========================================
echo Testing Ollama API...
echo ========================================
curl -s http://localhost:11434/api/tags
echo.
echo.

echo ========================================
echo Listing installed Ollama models...
echo ========================================
docker exec compliance-ollama ollama list
echo.

echo ========================================
echo Environment Check Complete!
echo ========================================
echo.
echo If you see models listed above, you're ready to run the application.
echo If not, run: setup-ollama-models.bat
echo.
pause
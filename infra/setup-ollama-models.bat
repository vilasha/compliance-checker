@echo off
echo ========================================
echo Compliance Checker - Ollama Model Setup
echo ========================================
echo.

echo Step 1: Checking if Ollama container is running...
docker ps | findstr compliance-ollama >nul
if %errorlevel% neq 0 (
    echo ERROR: Ollama container is not running!
    echo Please run: docker-compose up -d
    pause
    exit /b 1
)
echo OK: Ollama container is running.
echo.

echo Step 2: Pulling llama3.2:1b (Chat Model - ~1.3 GB)...
echo This may take 5-15 minutes depending on your internet speed...
docker exec compliance-ollama ollama pull llama3.2:1b
if %errorlevel% neq 0 (
    echo ERROR: Failed to pull llama3.2:1b
    pause
    exit /b 1
)
echo OK: llama3.2:1b downloaded successfully.
echo.

echo Step 3: Pulling bge-m3 (Embedding Model - ~2.4 GB)...
echo This may take 10-20 minutes depending on your internet speed...
docker exec compliance-ollama ollama pull bge-m3
if %errorlevel% neq 0 (
    echo ERROR: Failed to pull bge-m3
    pause
    exit /b 1
)
echo OK: bge-m3 downloaded successfully.
echo.

echo Step 4: Verifying installed models...
docker exec compliance-ollama ollama list
echo.

pause
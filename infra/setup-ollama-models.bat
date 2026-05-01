@echo off
echo ========================================
echo Compliance Checker - Ollama Model Setup
echo ========================================
echo.

REM Load defaults (override via .env or set before running)
if not defined OLLAMA_CHAT_MODEL set OLLAMA_CHAT_MODEL=llama3.1:8b
if not defined OLLAMA_EMBEDDING_MODEL set OLLAMA_EMBEDDING_MODEL=bge-m3

echo Step 1: Checking if Ollama container is running...
docker ps | findstr compliance-ollama >nul
if %errorlevel% neq 0 (
    echo ERROR: Ollama container is not running!
    echo Please run: docker compose up -d
    pause
    exit /b 1
)
echo OK: Ollama container is running.
echo.

echo Step 2: Pulling %OLLAMA_CHAT_MODEL% (Chat Model - ~4.7 GB)...
echo This may take 10-30 minutes on first download...
docker exec compliance-ollama ollama pull %OLLAMA_CHAT_MODEL%
if %errorlevel% neq 0 (
    echo ERROR: Failed to pull %OLLAMA_CHAT_MODEL%
    pause
    exit /b 1
)
echo OK: %OLLAMA_CHAT_MODEL% downloaded successfully.
echo.

echo Step 3: Pulling %OLLAMA_EMBEDDING_MODEL% (Embedding Model - ~2.4 GB)...
echo This may take 10-20 minutes on first download...
docker exec compliance-ollama ollama pull %OLLAMA_EMBEDDING_MODEL%
if %errorlevel% neq 0 (
    echo ERROR: Failed to pull %OLLAMA_EMBEDDING_MODEL%
    pause
    exit /b 1
)
echo OK: %OLLAMA_EMBEDDING_MODEL% downloaded successfully.
echo.

echo Step 4: Verifying installed models...
docker exec compliance-ollama ollama list
echo.

echo ========================================
echo All models installed successfully!
echo ========================================
echo.
pause
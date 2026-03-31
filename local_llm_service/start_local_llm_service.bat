@echo off
setlocal

set "ROOT=D:\Projects\MBSE\AiChallenge\Prompting"

REM --- Настройки (можно менять под себя) ---
set "OLLAMA_HOST=0.0.0.0:11434"
set "OLLAMA_URL=http://127.0.0.1:11434"
set "MAX_CONTEXT=4096"
set "RATE_LIMIT_PER_MINUTE=20"
set "API_HOST=0.0.0.0"
set "API_PORT=8080"

echo [1/2] Запуск Ollama в отдельном окне...
start "Ollama Serve" cmd /k "set OLLAMA_HOST=%OLLAMA_HOST% && ollama serve"

echo [2/2] Запуск FastAPI proxy в отдельном окне...
start "Local LLM API" cmd /k "cd /d %ROOT% && set OLLAMA_URL=%OLLAMA_URL% && set MAX_CONTEXT=%MAX_CONTEXT% && set RATE_LIMIT_PER_MINUTE=%RATE_LIMIT_PER_MINUTE% && python -m uvicorn local_llm_service.main:app --host %API_HOST% --port %API_PORT%"

echo.
echo Сервисы запускаются.
echo Web chat: http://127.0.0.1:%API_PORT%/
echo API:      http://127.0.0.1:%API_PORT%/chat
echo.
echo Для остановки используйте: local_llm_service\stop_local_llm_service.bat

endlocal

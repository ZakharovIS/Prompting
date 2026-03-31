@echo off
setlocal

echo Остановка API (порт 8080), если запущен...
for /f "tokens=5" %%a in ('netstat -ano ^| findstr :8080 ^| findstr LISTENING') do (
  taskkill /PID %%a /F >nul 2>&1
)

echo Остановка Ollama (порт 11434), если запущен...
for /f "tokens=5" %%b in ('netstat -ano ^| findstr :11434 ^| findstr LISTENING') do (
  taskkill /PID %%b /F >nul 2>&1
)

echo Готово. Сервисы остановлены (если были запущены).
endlocal

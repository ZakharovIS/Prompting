@echo off
setlocal

if not defined OLLAMA_URL set OLLAMA_URL=http://127.0.0.1:11434
if not defined DEFAULT_MODEL set DEFAULT_MODEL=llama3.1:8b
if not defined MAX_CONTEXT set MAX_CONTEXT=4096

echo Starting Support AI service on http://127.0.0.1:8001
python -m uvicorn support_service.main:app --host 127.0.0.1 --port 8001 --reload

endlocal
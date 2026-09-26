@echo off
REM Axiom GAIA benchmark - Windows.
REM Runs the real GAIA 2023 validation Level 1 set (53 tasks) with the
REM official GAIA quasi-exact-match scorer. Costs $0 (Gemini free tier).
REM
REM One-time setup:
REM   1. Install JDK 21: https://adoptium.net/temurin/releases/?version=21
REM      Check in a NEW terminal:  java -version   (must say 21)
REM   2. Get a FREE key at https://aistudio.google.com/apikey
REM      (no card required; if your old key stopped working, click
REM      "Create API key" and use the new one)
REM
REM To run: open a terminal in THIS folder (click the address bar, type
REM cmd, press Enter) and run:  run-gaia.bat

if not defined GEMINI_API_KEY set /p "GEMINI_API_KEY=Paste your Gemini API key: "
if not defined GEMINI_API_KEY (
  echo No key entered. Get a free one at https://aistudio.google.com/apikey and try again.
  pause
  exit /b 2
)
set AXIOM_BENCH_PROVIDER=gemini
java -cp "axiom-0.12.0.jar;lib/*" dev.axiom.bench.gaia.GaiaMain --live
echo.
echo Done. The receipt and report are in benchmarks\receipts\.
pause

#!/bin/bash
# Axiom GAIA benchmark - macOS/Linux.
# Runs the real GAIA 2023 validation Level 1 set (53 tasks) with the
# official GAIA quasi-exact-match scorer. Costs $0 (Gemini free tier).
#
# One-time setup:
#   1. Install JDK 21: https://adoptium.net/temurin/releases/?version=21
#      Check:  java -version   (must say 21)
#   2. Get a FREE key at https://aistudio.google.com/apikey (no card required)
#
# To run:  ./run-gaia.sh
# Takes roughly 30-45 minutes: 42 attempted tasks, ~8 model calls each,
# paced at 12 requests/minute to stay under the free-tier limit
# (15/min, 500/day on gemini-3.5-flash-lite). 11 tasks need attachment
# files that are access-gated and are recorded UNATTEMPTED, not failed.
# Writes a JSON receipt + a detailed Markdown report into
# benchmarks/receipts/. Send both files back so results can be recorded.
set -e
cd "$(dirname "$0")"
if [ -z "$GEMINI_API_KEY" ]; then
  printf 'Enter your Gemini API key (from https://aistudio.google.com/apikey): '
  read -r GEMINI_API_KEY
  export GEMINI_API_KEY
fi
if [ -z "$GEMINI_API_KEY" ]; then
  echo "No key entered. Get a free one at https://aistudio.google.com/apikey and try again."
  exit 2
fi
export AXIOM_BENCH_PROVIDER=gemini
java -cp "axiom-0.12.0.jar:lib/*" dev.axiom.bench.gaia.GaiaMain --live
echo
echo "Done. The receipt and report are in benchmarks/receipts/."

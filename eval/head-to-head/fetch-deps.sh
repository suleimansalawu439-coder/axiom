#!/bin/bash
# Fetches the pinned LangChain4j 1.20.0 artifacts (+sources) and the small
# set of transitive runtime jars the head-to-head harness needs.
# Repro: ./fetch-deps.sh   (needs curl + Maven Central reachability)
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p lib

M2="https://repo.maven.apache.org/maven2"
LC4J="$M2/dev/langchain4j"

fetch() { # group-path artifact version file
  local url="$M2/$1/$2/$3/$4"
  echo "GET $4"
  curl -sS --fail -m 120 -o "lib/$4" "$url"
}

# --- LangChain4j 1.20.0 (pinned; latest stable as of 2026-09-25) ---
for a in langchain4j langchain4j-core langchain4j-open-ai langchain4j-http-client langchain4j-http-client-jdk; do
  fetch "dev/langchain4j" "$a" "1.20.0" "$a-1.20.0.jar"
  fetch "dev/langchain4j" "$a" "1.20.0" "$a-1.20.0-sources.jar"
done
# reactive-streaming has its own beta train; 1.20.0-beta30 is what
# langchain4j-open-ai:1.20.0's pom depends on (needed by the streaming model).
fetch "dev/langchain4j" "langchain4j-reactive-streaming" "1.20.0-beta30" \
  "langchain4j-reactive-streaming-1.20.0-beta30.jar"

# --- Jackson (LangChain4j 1.20.0's pinned databind) ---
fetch "com/fasterxml/jackson/core" "jackson-databind" "2.22.1" "jackson-databind-2.22.1.jar"
fetch "com/fasterxml/jackson/core" "jackson-core" "2.22.1" "jackson-core-2.22.1.jar"
fetch "com/fasterxml/jackson/core" "jackson-annotations" "2.22" "jackson-annotations-2.22.jar"

# --- Token counting (used by the OpenAI module) ---
fetch "com/knuddels" "jtokkit" "1.1.0" "jtokkit-1.1.0.jar"

# --- Reactive streams plumbing ---
fetch "io/smallrye/reactive" "mutiny-zero" "1.3.1" "mutiny-zero-1.3.1.jar"

echo "OK: $(ls lib/*.jar | wc -l) jars in lib/"

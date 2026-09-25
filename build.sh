#!/bin/bash
# Axiom build script (no Maven required).
# Uses the JDK at ~/workspace/tools/jdk-21 and jars in ./lib.
set -e
cd "$(dirname "$0")"

JAVA_HOME="$HOME/workspace/tools/jdk-21"
export PATH="$JAVA_HOME/bin:$PATH"

# Bootstrap dependencies if missing (Maven Central is fetched with plain curl;
# no Maven installation or settings required).
if [ ! -f lib/jackson-databind-2.17.2.jar ] || [ ! -f lib/junit-platform-console-standalone-1.10.3.jar ]; then
  echo "==> Downloading dependencies from Maven Central..."
  mkdir -p lib
  M2="https://repo.maven.apache.org/maven2"
  curl -sSL -o lib/jackson-databind-2.17.2.jar          "$M2/com/fasterxml/jackson/core/jackson-databind/2.17.2/jackson-databind-2.17.2.jar" &
  curl -sSL -o lib/jackson-core-2.17.2.jar              "$M2/com/fasterxml/jackson/core/jackson-core/2.17.2/jackson-core-2.17.2.jar" &
  curl -sSL -o lib/jackson-annotations-2.17.2.jar       "$M2/com/fasterxml/jackson/core/jackson-annotations/2.17.2/jackson-annotations-2.17.2.jar" &
  curl -sSL -o lib/slf4j-api-2.0.13.jar                 "$M2/org/slf4j/slf4j-api/2.0.13/slf4j-api-2.0.13.jar" &
  curl -sSL -o lib/slf4j-simple-2.0.13.jar             "$M2/org/slf4j/slf4j-simple/2.0.13/slf4j-simple-2.0.13.jar" &
  curl -sSL -o lib/junit-platform-console-standalone-1.10.3.jar \
                                                       "$M2/org/junit/platform/junit-platform-console-standalone/1.10.3/junit-platform-console-standalone-1.10.3.jar" &
  wait
  echo "==> Dependencies ready."
fi

CP_MAIN="lib/jackson-databind-2.17.2.jar:lib/jackson-core-2.17.2.jar:lib/jackson-annotations-2.17.2.jar:lib/slf4j-api-2.0.13.jar:lib/slf4j-simple-2.0.13.jar"
CP_TEST="target/classes:$CP_MAIN:lib/junit-platform-console-standalone-1.10.3.jar"

rm -rf target/classes target/test-classes
mkdir -p target/classes target/test-classes

echo "==> Compiling main sources (Axiom ToolProcessor active)..."
javac -parameters -d target/classes -cp "$CP_MAIN" $(find src/main/java -name "*.java")
cp -r src/main/resources/* target/classes/

echo "==> Compiling tests..."
javac -parameters -d target/test-classes -cp "$CP_TEST" -processorpath target/classes \
  $(find src/test/java -name "*.java")

echo "==> Running tests..."
java -jar lib/junit-platform-console-standalone-1.10.3.jar execute \
  --class-path "$CP_TEST" \
  --select-class dev.axiom.tools.ToolRegistryTest \
  --select-class dev.axiom.agent.ReActAgentTest \
  --select-class dev.axiom.llm.OpenAiCompatibleClientTest 2>&1 | tail -8

echo "==> Packaging axiom-0.1.0.jar..."
jar --create --file target/axiom-0.1.0.jar -C target/classes .
echo "Done: target/axiom-0.1.0.jar"

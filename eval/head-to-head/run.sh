#!/bin/bash
# Head-to-head eval: Axiom vs LangChain4j 1.20.0 — failure-mode scenarios.
# Runs every LIVE scenario and prints labeled results. Pure evaluation:
# nothing under src/main is touched.
#
# Repro: ./run.sh   (needs: JDK 21 at ~/workspace/tools/jdk-21,
#                    jars in lib/ — see fetch-deps.sh)
set -u
cd "$(dirname "$0")"

export JAVA_HOME="$HOME/workspace/tools/jdk-21"
export PATH="$JAVA_HOME/bin:$PATH"
# localhost mock servers must bypass the sandbox egress proxy
unset HTTPS_PROXY HTTP_PROXY ALL_PROXY https_proxy http_proxy all_proxy

AX_LIB="$HOME/workspace/axiom/lib"
AXIOM_CP="axiom-classes:$AX_LIB/jackson-databind-2.17.2.jar:$AX_LIB/jackson-core-2.17.2.jar:$AX_LIB/jackson-annotations-2.17.2.jar:$AX_LIB/slf4j-api-2.0.13.jar:$AX_LIB/slf4j-simple-2.0.13.jar"
LC4J_CP="lib/langchain4j-1.20.0.jar:lib/langchain4j-core-1.20.0.jar:lib/langchain4j-open-ai-1.20.0.jar:lib/langchain4j-http-client-1.20.0.jar:lib/langchain4j-http-client-jdk-1.20.0.jar:lib/langchain4j-reactive-streaming-1.20.0-beta30.jar:lib/jackson-databind-2.22.1.jar:lib/jackson-core-2.22.1.jar:lib/jackson-annotations-2.22.jar:lib/jtokkit-1.1.0.jar:lib/mutiny-zero-1.3.1.jar:$AX_LIB/slf4j-api-2.0.13.jar:$AX_LIB/slf4j-simple-2.0.13.jar"

mkdir -p out
GF="grep -v --line-buffered ^SLF4J"
# Sandbox quirk (documented in the report): the JVM opens AF_INET6
# dual-stack sockets and dials ::ffff:127.0.0.1, which this sandbox RSTs
# against localhost listeners (curl/python use plain IPv4 and are fine).
# Forcing IPv4 makes the JVM behave like every other client.
JAVA_NET_OPTS="-Djava.net.preferIPv4Stack=true"

echo "=================================================================="
echo "SCENARIO (a) — duplicate tool names"
echo "------------------------------------------------------------------"
echo "--- Axiom: compile fixture with ToolProcessor (javac -proc:only) ---"
javac -parameters -proc:only -processorpath "$AXIOM_CP:processor-services" -cp "$AXIOM_CP" \
  -d out fixtures/axiom/DuplicateTools.java 2>&1 | head -8
echo "(javac exit code: ${PIPESTATUS[0]})"
echo ""
echo "--- LangChain4j: duplicate @Tool names at wiring time ---"
javac -d out -cp "$LC4J_CP" -sourcepath src src/ScenarioA_Lc4j.java 2>&1 | head -5
java $JAVA_NET_OPTS -cp "out:$LC4J_CP" ScenarioA_Lc4j 2>&1 | $GF

echo ""
echo "=================================================================="
echo "SCENARIO (e)+(f) — LangChain4j 1.20.0 vs mock OpenAI server"
echo "------------------------------------------------------------------"
javac -d out -cp "$LC4J_CP" -sourcepath src src/ScenarioEF_Lc4j.java 2>&1 | head -5
java $JAVA_NET_OPTS -cp "out:$LC4J_CP" ScenarioEF_Lc4j 2>&1 | $GF

echo ""
echo "=================================================================="
echo "SCENARIO (e)+(f) — Axiom vs mock OpenAI server"
echo "------------------------------------------------------------------"
javac -parameters -d out -cp "$AXIOM_CP" -sourcepath src src/dev/axiom/llm/ScenarioEF_Axiom.java 2>&1 | head -5
java $JAVA_NET_OPTS -cp "out:$AXIOM_CP" dev.axiom.llm.ScenarioEF_Axiom 2>&1 | $GF

echo ""
echo "=================================================================="
echo "DONE"

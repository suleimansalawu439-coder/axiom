import dev.axiom.Axiom;
import dev.axiom.agent.AgentResult;
import dev.axiom.llm.OpenAiCompatibleClient;
import dev.axiom.observe.JsonLinesExporter;
import dev.axiom.observe.MetricsReporter;
import dev.axiom.tools.Tool;
import dev.axiom.tools.ToolParam;

import java.nio.file.Path;

/**
 * Full observability: every agent event is appended as JSON lines (ship to
 * Loki/Elasticsearch/Datadog, or query with jq), while a metrics reporter
 * keeps live in-memory counters for dashboards.
 *
 * Run: javac -cp <axiom-jar>:<lib/*> ObservedAgent.java && java -cp .:<axiom-jar>:<lib/*> ObservedAgent
 * Requires OPENAI_API_KEY in the environment.
 */
public class ObservedAgent {

    public static class NotesTools {
        @Tool(description = "Save a note and return its id")
        public String saveNote(@ToolParam(description = "Note text") String text) {
            return "note-" + Math.abs(text.hashCode());
        }
    }

    public static void main(String[] args) {
        var metrics = new MetricsReporter();
        var exporter = new JsonLinesExporter(Path.of("logs", "axiom-events.jsonl"));

        var agent = Axiom.agent()
            .withClient(new OpenAiCompatibleClient("gpt-4o-mini"))
            .withTools(new NotesTools())
            .onEvent(metrics.asListener())
            .onEvent(exporter.asListener())
            .buildAgent();

        AgentResult result = agent.run("Save a note saying 'Axiom ships observability' and confirm.");
        String answer = result.output();
        System.out.println("Answer: " + answer);
        System.out.println(metrics.summary());
        System.out.println("Events written to logs/axiom-events.jsonl");
        System.out.println("Try: jq -c 'select(.type==\"ToolCallFinished\")' logs/axiom-events.jsonl");
    }
}

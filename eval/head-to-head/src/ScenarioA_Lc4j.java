import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.AiServices;

/**
 * Head-to-head scenario (a), LangChain4j side — LIVE.
 *
 * Two overloaded methods share the tool name "calculate". We measure WHEN
 * LangChain4j notices: at .tools(...) wiring time, at .build() time, at the
 * first chat call, or never.
 */
public class ScenarioA_Lc4j {

    public static class DupTools {
        @Tool("adds x to itself")
        public int calculate(@P("the number") int x) {
            return x + x;
        }

        @Tool("multiplies x by y")
        public int calculate(@P("the number") int x, @P("the multiplier") int y) {
            return x * y;
        }
    }

    interface Assistant {
        String chat(String message);
    }

    static class FakeModel implements ChatModel {
        @Override
        public ChatResponse doChat(ChatRequest chatRequest) {
            throw new UnsupportedOperationException("fake model is never called in this scenario");
        }
    }

    public static void main(String[] args) {
        long t0 = System.nanoTime();
        try {
            var builder = AiServices.builder(Assistant.class)
                    .chatModel(new FakeModel())
                    .tools(new DupTools()); // <-- does it throw HERE?
            System.out.println("[LC4J] no error when .tools() was called");
            Assistant assistant = builder.build(); // <-- or HERE?
            System.out.println("[LC4J] no error at .build() either; assistant=" + assistant);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            System.out.println("[LC4J] RESULT: duplicate tool names NOT detected during wiring (" + ms + " ms)");
        } catch (Exception e) {
            long ms = (System.nanoTime() - t0) / 1_000_000;
            System.out.println("[LC4J] RESULT: thrown after " + ms + " ms");
            System.out.println("[LC4J] exception: " + e.getClass().getName());
            System.out.println("[LC4J] message: " + e.getMessage());
            System.out.println("[LC4J] thrown-from: " + e.getStackTrace()[0]);
        }
    }
}

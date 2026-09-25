import dev.axiom.tools.Tool;
import dev.axiom.tools.ToolParam;

/**
 * Fixture for head-to-head scenario (a): two DIFFERENT methods declare the
 * same tool name "calculate" (classic overload accident). Axiom's
 * ToolProcessor must reject this at compile time.
 */
public class DuplicateTools {

    @Tool(description = "Adds x to itself")
    public int calculate(@ToolParam(description = "the number") int x) {
        return x + x;
    }

    @Tool(description = "Multiplies x by y")
    public int calculate(@ToolParam(description = "the number") int x,
                         @ToolParam(description = "the multiplier") int y) {
        return x * y;
    }
}

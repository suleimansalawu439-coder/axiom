package dev.axiom.tools;

import java.util.Map;

/**
 * How a {@link ToolDefinition} is actually executed. Annotation-based tools
 * get a reflective invoker (built by {@link ToolRegistry}); synthetic tools —
 * MCP server tools, supervisor delegate tools, sandboxed commands — supply
 * their own. Anything that can turn a JSON argument map into a result can be
 * a tool, while the compile-time schema contract stays intact.
 */
@FunctionalInterface
public interface ToolInvoker {
    /**
     * Invoke the tool with JSON-decoded arguments.
     *
     * @return the tool's result; {@code null} is allowed
     * @throws Exception any failure is wrapped as a {@link ToolInvocationException}
     *                   (or fed back to the LLM as an observation) by the caller
     */
    Object invoke(Map<String, Object> arguments) throws Exception;
}

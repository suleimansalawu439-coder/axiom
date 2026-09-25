package dev.axiom.tools;

import java.lang.annotation.*;

/**
 * Describes a single parameter of a {@link Tool} method. The annotation
 * processor turns these into the JSON Schema "properties" block, mapping
 * Java types to JSON types at compile time.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
@Documented
public @interface ToolParam {
    /** Description of the parameter, shown to the LLM. */
    String description();

    /** Whether the LLM must supply this argument. Defaults to true. */
    boolean required() default true;

    /** Optional example value to guide the LLM. */
    String example() default "";
}

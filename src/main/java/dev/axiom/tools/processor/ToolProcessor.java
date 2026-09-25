package dev.axiom.tools.processor;

import dev.axiom.tools.Tool;
import dev.axiom.tools.ToolParam;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import javax.tools.FileObject;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.io.Writer;
import java.util.*;

/**
 * Compile-time verification and schema generation for {@code @Tool} methods.
 *
 * <p>This is what makes Axiom different from every Python agent framework:
 * <ul>
 *   <li>Every {@code @Tool} method must be {@code public} and every parameter
 *       must carry {@code @ToolParam} — otherwise compilation <b>fails</b>.</li>
 *   <li>Parameter types must be mappable to JSON Schema — otherwise
 *       compilation <b>fails</b>.</li>
 *   <li>Parameter names must be real (compiled with {@code -parameters});
 *       synthetic names like {@code arg0} are a compile <b>error</b>, because
 *       the runtime registry matches LLM arguments by name.</li>
 *   <li>On success, a JSON schema document is generated to
 *       {@code META-INF/axiom/tools/&lt;ClassName&gt;.json} for tooling.</li>
 * </ul>
 */
@SupportedAnnotationTypes("dev.axiom.tools.Tool")
@SupportedSourceVersion(SourceVersion.RELEASE_21)
public class ToolProcessor extends AbstractProcessor {

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        Map<String, List<String>> schemasByClass = new LinkedHashMap<>();

        for (Element element : roundEnv.getElementsAnnotatedWith(Tool.class)) {
            if (element.getKind() != ElementKind.METHOD) {
                error(element, "@Tool must be applied to a method");
                continue;
            }
            ExecutableElement method = (ExecutableElement) element;
            Tool tool = method.getAnnotation(Tool.class);

            if (!method.getModifiers().contains(Modifier.PUBLIC)) {
                error(method, "@Tool method '%s' must be public".formatted(method.getSimpleName()));
            }

            String toolName = tool.name().isBlank()
                ? method.getSimpleName().toString() : tool.name();
            if (tool.description().isBlank()) {
                error(method, "@Tool '%s' must declare a non-blank description".formatted(toolName));
            }

            Map<String, Object> properties = new LinkedHashMap<>();
            List<String> required = new ArrayList<>();
            for (VariableElement param : method.getParameters()) {
                String paramName = param.getSimpleName().toString();
                if (paramName.matches("arg\\d+")) {
                    error(param, ("Parameter names are synthetic (arg0, arg1, ...). "
                        + "Compile with '-parameters' so Axiom can match LLM arguments by name. "
                        + "Add <parameters>true</parameters> to maven-compiler-plugin.").formatted());
                    continue;
                }
                ToolParam tp = param.getAnnotation(ToolParam.class);
                if (tp == null) {
                    error(param, ("Parameter '%s' of @Tool '%s' is missing @ToolParam. "
                        + "Every parameter must be documented for the LLM.").formatted(paramName, toolName));
                    continue;
                }
                String jsonType = jsonType(param.asType(), param);
                if (jsonType == null) continue; // error already reported
                Map<String, Object> prop = new LinkedHashMap<>();
                prop.put("type", jsonType);
                if (!tp.description().isBlank()) prop.put("description", tp.description());
                if (!tp.example().isBlank()) prop.put("example", tp.example());
                properties.put(paramName, prop);
                if (tp.required()) required.add(paramName);
            }

            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            schema.put("properties", properties);
            schema.put("required", required);
            schema.put("additionalProperties", false);

            Map<String, Object> functionDef = Map.of(
                "name", toolName,
                "description", tool.description(),
                "parameters", schema);

            String className = ((TypeElement) method.getEnclosingElement()).getQualifiedName().toString();
            schemasByClass.computeIfAbsent(className, k -> new ArrayList<>())
                .add(toJson(functionDef));
        }

        for (var entry : schemasByClass.entrySet()) {
            writeSchemaResource(entry.getKey(), entry.getValue());
        }
        return true;
    }

    // ------------------------------------------------------------------

    private String jsonType(TypeMirror type, Element element) {
        TypeKind kind = type.getKind();
        String name = type.toString();
        return switch (kind) {
            case BOOLEAN -> "boolean";
            case BYTE, SHORT, INT, LONG -> "integer";
            case FLOAT, DOUBLE -> "number";
            case CHAR -> "string";
            case DECLARED -> {
                if (name.equals("java.lang.String") || name.equals("java.lang.Character")) yield "string";
                if (name.equals("java.lang.Boolean")) yield "boolean";
                if (name.equals("java.lang.Integer") || name.equals("java.lang.Long")
                    || name.equals("java.lang.Short") || name.equals("java.lang.Byte")) yield "integer";
                if (name.equals("java.lang.Double") || name.equals("java.lang.Float")
                    || name.equals("java.math.BigDecimal")) yield "number";
                if (name.startsWith("java.util.List") || name.startsWith("java.util.Set")
                    || name.startsWith("java.util.Collection")) yield "array";
                if (name.startsWith("java.util.Map")) yield "object";
                // Any other declared type (POJO) maps to object via Jackson.
                yield "object";
            }
            case ARRAY -> "array";
            default -> {
                error(element, "Unsupported @Tool parameter type: " + name
                    + ". Use String, primitives/wrappers, List, arrays, Map, or POJOs.");
                yield null;
            }
        };
    }

    private void writeSchemaResource(String className, List<String> functionDefs) {
        String simpleName = className.substring(className.lastIndexOf('.') + 1);
        String resource = "META-INF/axiom/tools/" + simpleName + ".json";
        String json = "{\n  \"class\": \"" + className + "\",\n  \"tools\": [\n"
            + String.join(",\n", functionDefs) + "\n  ]\n}\n";
        try {
            FileObject file = processingEnv.getFiler()
                .createResource(StandardLocation.CLASS_OUTPUT, "", resource);
            try (Writer w = file.openWriter()) {
                w.write(json);
            }
            note("Axiom: generated " + resource);
        } catch (IOException e) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING,
                "Axiom: could not write " + resource + ": " + e.getMessage());
        }
    }

    private void error(Element e, String msg) {
        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "[Axiom] " + msg, e);
    }

    private void note(String msg) {
        processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE, "[Axiom] " + msg);
    }

    // Minimal JSON writer to avoid dependencies inside the processor.
    private static String toJson(Object o) {
        if (o instanceof Map<?, ?> m) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (var e : m.entrySet()) {
                if (!first) sb.append(",");
                sb.append(quote(e.getKey().toString())).append(":").append(toJson(e.getValue()));
                first = false;
            }
            return sb.append("}").toString();
        }
        if (o instanceof List<?> l) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < l.size(); i++) {
                if (i > 0) sb.append(",");
                sb.append(toJson(l.get(i)));
            }
            return sb.append("]").toString();
        }
        if (o instanceof String s) return quote(s);
        if (o instanceof Boolean || o instanceof Number) return o.toString();
        if (o == null) return "null";
        return quote(o.toString());
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\"";
    }
}

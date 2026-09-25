package dev.axiom.tools.processor;

import dev.axiom.capabilities.Capability;
import dev.axiom.capabilities.Ensures;
import dev.axiom.capabilities.Requires;
import dev.axiom.tools.Tool;
import dev.axiom.tools.ToolParam;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import javax.tools.FileObject;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.io.Writer;
import java.lang.annotation.Annotation;
import java.util.*;

/**
 * Compile-time verification and schema generation for {@code @Tool} methods.
 *
 * <p>This is what makes Axiom different from every Python agent framework:
 * <ul>
 *   <li>Every {@code @Tool} method must be {@code public} and every parameter
 *       must carry {@code @ToolParam} — otherwise compilation <b>fails</b>.</li>
 *   <li>Tool names must be unique across the compilation — otherwise
 *       compilation <b>fails</b>.</li>
 *   <li>Parameter types must be mappable to JSON Schema <em>and</em>
 *       Jackson-deserializable (record, accessible no-arg constructor, or
 *       {@code @JsonCreator}; no interfaces, abstract types, or non-static
 *       inner classes) — otherwise compilation <b>fails</b>. Return types get
 *       the same treatment, so observations can never be garbage.</li>
 *   <li>Parameter names must be real (compiled with {@code -parameters});
 *       synthetic names like {@code arg0} are a compile <b>error</b>, because
 *       the runtime registry matches LLM arguments by name.</li>
 *   <li>On success, a JSON schema document is generated to
 *       {@code META-INF/axiom/tools/<binary-name-with-/-separators>.json}
 *       (e.g. {@code dev/axiom/bench/BenchMain$CalcTools.json}) — and the
 *       runtime {@code ToolRegistry} reads that artifact as the single
 *       source of truth instead of re-deriving schemas by reflection.</li>
 * </ul>
 */
@SupportedAnnotationTypes("dev.axiom.tools.Tool")
@SupportedSourceVersion(SourceVersion.RELEASE_21)
public class ToolProcessor extends AbstractProcessor {

    /** Tool name -> qualified holder class, for cross-class duplicate detection. */
    private final Map<String, String> toolNames = new LinkedHashMap<>();

    /**
     * Capability policy state, accumulated across rounds for the
     * end-of-compilation satisfiability check ("no dead policies").
     */
    /** Tool name -> required session tokens. */
    private final Map<String, Set<Capability>> requiresByTool = new LinkedHashMap<>();
    /** Tool name -> the method element, for error blame. */
    private final Map<String, Element> toolElements = new LinkedHashMap<>();
    /** Session token -> tool names that @Ensures it. */
    private final Map<Capability, Set<String>> ensuredByToken = new LinkedHashMap<>();
    /** Policy resources already written this compilation (one per holder). */
    private final Set<String> writtenPolicyResources = new HashSet<>();

    /**
     * JDK types Jackson handles natively without bean introspection, so they
     * need no constructor/record/@JsonCreator check.
     */
    private static final Set<String> JACKSON_NATIVE = Set.of(
        "java.util.UUID", "java.net.URI", "java.net.URL",
        "java.math.BigInteger",
        "java.time.Instant", "java.time.LocalDate", "java.time.LocalDateTime",
        "java.time.OffsetDateTime", "java.time.ZonedDateTime", "java.time.Duration",
        "java.util.Date", "java.sql.Date", "java.sql.Timestamp");

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        Map<String, List<String>> schemasByClass = new LinkedHashMap<>();
        Map<String, List<String>> policiesByClass = new LinkedHashMap<>();

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
            String holderClass = processingEnv.getElementUtils()
                .getBinaryName((TypeElement) method.getEnclosingElement()).toString();
            String prevHolder = toolNames.putIfAbsent(toolName, holderClass);
            if (prevHolder != null) {
                error(method, ("@Tool name '%s' is already used by %s. Tool names "
                    + "must be unique across the compilation — the runtime registry "
                    + "is keyed by name.").formatted(toolName, prevHolder));
            }
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
                String jsonType = jsonType(param.asType(), param, "parameter");
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
                "parameters", schema,
                "idempotent", tool.idempotent());

            // Capability policy: @Requires/@Ensures declare STRIPS-style
            // preconditions/effects over session tokens; @Tool(capabilities)
            // declares effect capabilities. Validated here, emitted to the
            // runtime policy artifact, and satisfiability-checked at the end
            // of the compilation ("no dead policies").
            Set<Capability> requires = validateTokens(
                method.getAnnotation(Requires.class), method, toolName, "@Requires");
            Set<Capability> ensures = validateTokens(
                method.getAnnotation(Ensures.class), method, toolName, "@Ensures");
            requiresByTool.put(toolName, requires);
            toolElements.putIfAbsent(toolName, method);
            for (Capability token : ensures) {
                ensuredByToken.computeIfAbsent(token, k -> new LinkedHashSet<>()).add(toolName);
            }
            Map<String, Object> policyEntry = new LinkedHashMap<>();
            policyEntry.put("name", toolName);
            policyEntry.put("capabilities", namesOf(Arrays.asList(tool.capabilities())));
            policyEntry.put("requires", namesOf(requires));
            policyEntry.put("ensures", namesOf(ensures));
            policiesByClass.computeIfAbsent(holderClass, k -> new ArrayList<>())
                .add(toJson(policyEntry));

            // Return types are serialized to observations via Jackson: a
            // provably undeserializable/unserializable declared type fails the
            // build instead of producing garbage observations at runtime.
            TypeMirror ret = method.getReturnType();
            if (ret.getKind() == TypeKind.DECLARED) {
                jsonType(ret, method, "return type");
            }

            schemasByClass.computeIfAbsent(holderClass, k -> new ArrayList<>())
                .add(toJson(functionDef));
        }

        for (var entry : schemasByClass.entrySet()) {
            writeSchemaResource(entry.getKey(), entry.getValue());
        }
        for (var entry : policiesByClass.entrySet()) {
            writePolicyResource(entry.getKey(), entry.getValue());
        }
        if (roundEnv.processingOver()) {
            checkPolicySatisfiability();
        }
        return true;
    }

    // ------------------------------------------------------------------
    // Capability policy
    // ------------------------------------------------------------------

    /**
     * Validate a {@code @Requires}/{@code @Ensures} annotation: every member
     * must be a session token, not an effect capability. Effect capabilities
     * belong on {@code @Tool(capabilities = …)}; tokens are session facts.
     * Mixing them is almost always an authoring mistake, so it fails the
     * build rather than silently meaning nothing at runtime.
     */
    private Set<Capability> validateTokens(Annotation annotation, Element method,
                                           String toolName, String annotationName) {
        Set<Capability> tokens = new LinkedHashSet<>();
        if (annotation == null) return tokens;
        Capability[] declared = annotationName.equals("@Requires")
            ? ((Requires) annotation).value() : ((Ensures) annotation).value();
        for (Capability c : declared) {
            if (!c.isSessionToken()) {
                error(method, ("%s on @Tool '%s' references %s, which is an effect "
                    + "capability, not a session token. %s/@Ensures accept only session "
                    + "tokens (APPROVAL, BACKUP) — declare effects with "
                    + "@Tool(capabilities = …).").formatted(
                        annotationName, toolName, c, annotationName));
                continue;
            }
            tokens.add(c);
        }
        return tokens;
    }

    private static List<String> namesOf(Collection<Capability> caps) {
        List<String> names = new ArrayList<>(caps.size());
        for (Capability c : caps) names.add(c.name());
        return names;
    }

    /**
     * "No dead policies": every required session token must be ensurable by
     * some {@code @Tool} in this compilation. A tool whose precondition can
     * never be satisfied could never run — that is a policy authoring bug,
     * and it fails the build here instead of surfacing as a mysterious
     * runtime block at 2am.
     *
     * <p>Boundary, stated plainly: this sees one compilation. Tools from
     * already-compiled jars (or MCP-discovered at runtime) are invisible to
     * it — the runtime {@code CapabilityGuardrail} still enforces their
     * preconditions, fail-closed.
     */
    private void checkPolicySatisfiability() {
        for (var entry : requiresByTool.entrySet()) {
            String toolName = entry.getKey();
            for (Capability token : entry.getValue()) {
                Set<String> ensurers =
                    ensuredByToken.getOrDefault(token, Set.of());
                if (ensurers.isEmpty()) {
                    error(toolElements.get(toolName),
                        ("Policy unsatisfiable: tool '%s' requires session token %s, "
                            + "but no @Tool in this compilation ensures it. The tool could "
                            + "never run — either add a tool with @Ensures(%s) (e.g. a backup "
                            + "tool) or drop the @Requires.").formatted(
                                toolName, token, token));
                } else if (ensurers.size() == 1 && ensurers.contains(toolName)) {
                    processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING,
                        ("[Axiom] Tool '%s' is the only ensurer of session token %s, which "
                            + "it also requires — it can never establish its own precondition "
                            + "and so can never run.").formatted(toolName, token),
                        toolElements.get(toolName));
                }
            }
        }
    }

    private void writePolicyResource(String className, List<String> toolEntries) {
        // Same binary-name scheme as the tool schemas:
        // META-INF/axiom/policy/dev/axiom/bench/BenchMain$CalcTools.json
        String resource = "META-INF/axiom/policy/" + className.replace('.', '/') + ".json";
        if (!writtenPolicyResources.add(resource)) return; // one write per compilation
        String json = "{\n  \"class\": \"" + className + "\",\n  \"tools\": [\n"
            + String.join(",\n", toolEntries) + "\n  ]\n}\n";
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

    // ------------------------------------------------------------------

    /**
     * Map a Java type to its JSON Schema type, failing the build for anything
     * the runtime could not faithfully deserialize. This mapping is the
     * single source of truth: {@link dev.axiom.tools.ToolRegistry} reuses the
     * generated {@code META-INF/axiom/tools/*.json} artifact at runtime, and
     * {@code SchemaDriftTest} guards the reflective fallback against drift.
     */
    private String jsonType(TypeMirror type, Element element, String role) {
        TypeKind kind = type.getKind();
        String name = type.toString();
        return switch (kind) {
            case BOOLEAN -> "boolean";
            case BYTE, SHORT, INT, LONG -> "integer";
            case FLOAT, DOUBLE -> "number";
            case CHAR -> "string";
            case DECLARED -> {
                DeclaredType declared = (DeclaredType) type;
                Element el = declared.asElement();
                String qname = el instanceof TypeElement te
                    ? te.getQualifiedName().toString() : name;
                if (qname.equals("java.lang.String") || qname.equals("java.lang.Character")) yield "string";
                if (qname.equals("java.lang.Boolean")) yield "boolean";
                if (qname.equals("java.lang.Integer") || qname.equals("java.lang.Long")
                    || qname.equals("java.lang.Short") || qname.equals("java.lang.Byte")) yield "integer";
                if (qname.equals("java.lang.Double") || qname.equals("java.lang.Float")
                    || qname.equals("java.math.BigDecimal")) yield "number";
                if (qname.equals("java.lang.Object")) {
                    error(element, "@Tool %s of type Object is not allowed — the LLM needs a concrete type.".formatted(role));
                    yield null;
                }
                if (isSubtypeOf(type, "java.util.Collection")) yield "array";
                if (isSubtypeOf(type, "java.util.Map")) yield "object";
                // Any other declared type (POJO) maps to object via Jackson —
                // but only if Jackson can actually construct it.
                if (el instanceof TypeElement te && !checkReadable(te, element, role)) yield null;
                yield "object";
            }
            case ARRAY -> "array";
            default -> {
                error(element, "Unsupported @Tool %s type: %s. Use String, primitives/wrappers, List, arrays, Map, or POJOs.".formatted(role, name));
                yield null;
            }
        };
    }

    private boolean isSubtypeOf(TypeMirror type, String qualified) {
        TypeElement target = processingEnv.getElementUtils().getTypeElement(qualified);
        if (target == null) return false;
        return processingEnv.getTypeUtils().isAssignable(
            processingEnv.getTypeUtils().erasure(type), target.asType());
    }

    /**
     * Verify Jackson can construct the type from LLM JSON. Tool parameters
     * are <em>deserialized</em>; a type Jackson cannot instantiate passes no
     * silent runtime failure — it fails the build here instead.
     */
    private boolean checkReadable(TypeElement te, Element blame, String role) {
        String qname = te.getQualifiedName().toString();
        if (JACKSON_NATIVE.contains(qname)) return true;
        ElementKind kind = te.getKind();
        if (kind == ElementKind.ENUM || kind == ElementKind.RECORD) return true;
        if (kind == ElementKind.INTERFACE || kind == ElementKind.ANNOTATION_TYPE) {
            error(blame, ("@Tool %s type %s is an interface — Jackson cannot instantiate it. "
                + "Use a concrete class or record.").formatted(role, qname));
            return false;
        }
        if (te.getModifiers().contains(Modifier.ABSTRACT)) {
            error(blame, ("@Tool %s type %s is abstract — Jackson cannot instantiate it. "
                + "Use a concrete class or record.").formatted(role, qname));
            return false;
        }
        if (te.getNestingKind() == NestingKind.MEMBER
                && !te.getModifiers().contains(Modifier.STATIC)) {
            error(blame, ("@Tool %s type %s is a non-static inner class — Jackson cannot instantiate it. "
                + "Make it static or a top-level class.").formatted(role, qname));
            return false;
        }
        if (hasJsonCreator(te)) return true;
        if (hasAccessibleNoArgConstructor(te)) return true;
        error(blame, ("@Tool %s type %s is not Jackson-deserializable: no accessible no-arg constructor, "
            + "no @JsonCreator, and not a record. Add one of the three.").formatted(role, qname));
        return false;
    }

    private boolean hasJsonCreator(TypeElement te) {
        for (Element e : te.getEnclosedElements()) {
            ElementKind k = e.getKind();
            if (k != ElementKind.CONSTRUCTOR && k != ElementKind.METHOD) continue;
            for (AnnotationMirror am : e.getAnnotationMirrors()) {
                String annName = ((TypeElement) am.getAnnotationType().asElement())
                    .getQualifiedName().toString();
                if (annName.endsWith(".JsonCreator") || annName.equals("JsonCreator")) return true;
            }
        }
        return false;
    }

    private boolean hasAccessibleNoArgConstructor(TypeElement te) {
        boolean sawCtor = false;
        for (Element e : te.getEnclosedElements()) {
            if (e.getKind() != ElementKind.CONSTRUCTOR) continue;
            sawCtor = true;
            ExecutableElement ctor = (ExecutableElement) e;
            if (ctor.getParameters().isEmpty()
                    && !ctor.getModifiers().contains(Modifier.PRIVATE)) {
                return true;
            }
        }
        return !sawCtor; // implicit default constructor
    }

    private void writeSchemaResource(String className, List<String> functionDefs) {
        // Binary-name-based path: unique per class, including nested classes
        // (dev.axiom.bench.BenchMain$CalcTools -> dev/axiom/bench/BenchMain$CalcTools.json).
        String resource = "META-INF/axiom/tools/" + className.replace('.', '/') + ".json";
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

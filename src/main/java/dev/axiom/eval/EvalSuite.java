package dev.axiom.eval;

import java.util.List;

/** An ordered set of eval cases run together as one regression suite. */
public final class EvalSuite {
    private final String name;
    private final List<EvalCase<?>> cases;

    private EvalSuite(String name, List<EvalCase<?>> cases) {
        this.name = name;
        this.cases = List.copyOf(cases);
    }

    public static EvalSuite of(String name, EvalCase<?>... cases) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name is required");
        if (cases == null || cases.length == 0) throw new IllegalArgumentException("at least one case is required");
        return new EvalSuite(name, List.of(cases));
    }

    public static EvalSuite of(String name, List<EvalCase<?>> cases) {
        return of(name, cases.toArray(new EvalCase<?>[0]));
    }

    public String name() { return name; }
    public List<EvalCase<?>> cases() { return cases; }
}

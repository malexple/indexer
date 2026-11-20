package ru.mcs.indexer.lang.java;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.type.ArrayType;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.type.TypeParameter;
import com.github.javaparser.resolution.declarations.ResolvedAnnotationDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedConstructorDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedMethodLikeDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedParameterDeclaration;
import com.github.javaparser.resolution.types.ResolvedType;
import ru.mcs.indexer.lang.FileFacts;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-file facade over the symbol solver. Every resolver failure degrades to a best-effort name
 * (explicit imports) with resolved=false; it never throws. Two guards bound the cost of symbols the
 * solver cannot find: a relevance filter (see ProjectNames) and a per-file CPU-time budget.
 */
final class TypeResolver {

    static final long DEFAULT_BUDGET_MS = Long.getLong("indexer.resolve.budget.ms", 3000L);

    private static final ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();
    private static final boolean CPU_TIME = THREADS.isCurrentThreadCpuTimeSupported();
    private static final Map<String, Boolean> JAVA_LANG = new ConcurrentHashMap<>();

    /** qualified: name is a fully qualified name (solver or explicit import). resolved: solver confirmed it. */
    record Named(String name, boolean resolved, boolean qualified, boolean typeVariable) {
    }

    record Callee(String owner, String signature) {
    }

    private final boolean enabled;
    private final ProjectNames names;
    private final long budgetNanos;
    private final long startNanos = now();
    private boolean budgetExceeded;
    private final Map<String, String> imports = new HashMap<>();
    private final Deque<Set<String>> typeVars = new ArrayDeque<>();

    TypeResolver(boolean enabled, CompilationUnit unit) {
        this(enabled, unit, null, DEFAULT_BUDGET_MS);
    }

    /** names == null disables the relevance filter; budgetMillis <= 0 disables the budget. */
    TypeResolver(boolean enabled, CompilationUnit unit, ProjectNames names, long budgetMillis) {
        this.enabled = enabled;
        this.names = names;
        this.budgetNanos = budgetMillis <= 0 ? Long.MAX_VALUE : budgetMillis * 1_000_000L;
        for (ImportDeclaration declaration : unit.getImports()) {
            if (!declaration.isStatic() && !declaration.isAsterisk()) {
                String name = declaration.getNameAsString();
                imports.putIfAbsent(name.substring(name.lastIndexOf('.') + 1), name);
            }
        }
    }

    private static long now() {
        return CPU_TIME ? THREADS.getCurrentThreadCpuTime() : System.nanoTime();
    }

    boolean budgetExceeded() {
        return budgetExceeded;
    }

    long budgetMillis() {
        return budgetNanos == Long.MAX_VALUE ? 0 : budgetNanos / 1_000_000L;
    }

    private boolean active() {
        if (!enabled || budgetExceeded) {
            return false;
        }
        if (now() - startNanos > budgetNanos) {
            budgetExceeded = true;
            return false;
        }
        return true;
    }

    private static boolean isJavaLang(String simpleName) {
        return JAVA_LANG.computeIfAbsent(simpleName, name -> {
            try {
                Class.forName("java.lang." + name, false, ClassLoader.getPlatformClassLoader());
                return true;
            } catch (ClassNotFoundException | LinkageError e) {
                return false;
            }
        });
    }

    /** False when the name can only denote a library type that the solver is known not to see. */
    private boolean worthResolvingType(String written) {
        if (names == null) {
            return true;
        }
        int dot = written.indexOf('.');
        String head = dot < 0 ? written : written.substring(0, dot);
        if (Character.isLowerCase(head.charAt(0))) {
            return names.isInternalOrJdk(written);
        }
        String imported = imports.get(head);
        if (imported != null) {
            return names.isInternalOrJdk(imported);
        }
        return names.hasType(head) || isJavaLang(head);
    }

    private boolean worthResolvingCall(String methodName) {
        return names == null || names.hasMethod(methodName);
    }

    void pushTypeVars(List<TypeParameter> parameters) {
        Set<String> declared = new HashSet<>();
        for (TypeParameter parameter : parameters) {
            declared.add(parameter.getNameAsString());
        }
        typeVars.push(declared);
    }

    void popTypeVars() {
        typeVars.pop();
    }

    private boolean isTypeVariable(String name) {
        for (Set<String> declared : typeVars) {
            if (declared.contains(name)) {
                return true;
            }
        }
        return false;
    }

    static String written(ClassOrInterfaceType type) {
        return type.getScope().map(scope -> written(scope) + ".").orElse("") + type.getNameAsString();
    }

    static boolean isJavaLangTopLevel(String fqn) {
        return fqn.startsWith("java.lang.") && fqn.indexOf('.', "java.lang.".length()) < 0;
    }

    static void addDep(FileFacts out, int typeLocal, String selfFqn, String toFqn, String kind, boolean resolved) {
        if (toFqn == null || toFqn.isEmpty() || toFqn.equals(selfFqn) || isJavaLangTopLevel(toFqn)) {
            return;
        }
        out.addDep(typeLocal, toFqn, kind, resolved);
    }

    Named classType(ClassOrInterfaceType type) {
        String written = written(type);
        boolean scoped = type.getScope().isPresent();
        if (!scoped && isTypeVariable(written)) {
            return new Named(written, true, true, true);
        }
        if (active() && worthResolvingType(written)) {
            try {
                ResolvedType resolved = type.resolve();
                if (resolved.isReferenceType()) {
                    return new Named(resolved.asReferenceType().getQualifiedName(), true, true, false);
                }
                if (resolved.isTypeVariable()) {
                    return new Named(written, true, true, true);
                }
            } catch (RuntimeException | StackOverflowError e) {
                // fall back to the heuristics below
            }
        }
        if (!scoped) {
            String imported = imports.get(written);
            if (imported != null) {
                return new Named(imported, false, true, false);
            }
        } else if (Character.isLowerCase(written.charAt(0))) {
            return new Named(written, false, true, false);
        }
        return new Named(written, false, false, false);
    }

    String typeName(Type type) {
        if (type instanceof ArrayType array) {
            return typeName(array.getComponentType()) + "[]";
        }
        if (type instanceof ClassOrInterfaceType classType) {
            return classType(classType).name();
        }
        return type.asString();
    }

    Named annotation(AnnotationExpr annotation) {
        String written = annotation.getNameAsString();
        if (active() && worthResolvingType(written)) {
            try {
                ResolvedAnnotationDeclaration declaration = annotation.resolve();
                return new Named(declaration.getQualifiedName(), true, true, false);
            } catch (RuntimeException | StackOverflowError e) {
                // fall back
            }
        }
        String imported = written.indexOf('.') < 0 ? imports.get(written) : null;
        if (imported != null) {
            return new Named(imported, false, true, false);
        }
        return new Named(written, false, written.indexOf('.') >= 0, false);
    }

    Callee method(MethodCallExpr call) {
        if (!worthResolvingCall(call.getNameAsString()) || !active()) {
            return null;
        }
        try {
            return methodCallee(call.resolve());
        } catch (RuntimeException | StackOverflowError e) {
            return null;
        }
    }

    Callee methodRef(MethodReferenceExpr reference) {
        if (!worthResolvingCall(reference.getIdentifier()) || !active()) {
            return null;
        }
        try {
            return methodCallee(reference.resolve());
        } catch (RuntimeException | StackOverflowError e) {
            return null;
        }
    }

    Callee constructor(ObjectCreationExpr creation) {
        if (!worthResolvingType(written(creation.getType())) || !active()) {
            return null;
        }
        try {
            ResolvedConstructorDeclaration declaration = creation.resolve();
            String simple = declaration.declaringType().getName();
            return new Callee(declaration.declaringType().getQualifiedName(),
                    simple + "(" + parameters(declaration) + ")");
        } catch (RuntimeException | StackOverflowError e) {
            return null;
        }
    }

    /** Owner guess for an unresolved call: a scope that is a simple name matching an explicit import. */
    String ownerFromScope(Expression scope) {
        if (scope instanceof NameExpr name) {
            return imports.get(name.getNameAsString());
        }
        return null;
    }

    private Callee methodCallee(ResolvedMethodDeclaration declaration) {
        String signature = declaration.getName() + "(" + parameters(declaration) + "):"
                + resolvedName(declaration.getReturnType());
        return new Callee(declaration.declaringType().getQualifiedName(), signature);
    }

    private static String parameters(ResolvedMethodLikeDeclaration declaration) {
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < declaration.getNumberOfParams(); i++) {
            ResolvedParameterDeclaration parameter = declaration.getParam(i);
            String name = resolvedName(parameter.getType());
            if (parameter.isVariadic() && name.endsWith("[]")) {
                name = name.substring(0, name.length() - 2) + "...";
            }
            parts.add(name);
        }
        return String.join(",", parts);
    }

    private static String resolvedName(ResolvedType type) {
        if (type.isArray()) {
            return resolvedName(type.asArrayType().getComponentType()) + "[]";
        }
        if (type.isReferenceType()) {
            return type.asReferenceType().getQualifiedName();
        }
        if (type.isTypeVariable()) {
            return type.asTypeParameter().getName();
        }
        return type.describe();
    }
}
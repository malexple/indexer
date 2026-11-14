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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Per-file facade over the symbol solver. Every resolver failure degrades to a best-effort name
 * (explicit imports) with resolved=false; it never throws.
 */
final class TypeResolver {

    /** qualified: name is a fully qualified name (solver or explicit import). resolved: solver confirmed it. */
    record Named(String name, boolean resolved, boolean qualified, boolean typeVariable) {
    }

    record Callee(String owner, String signature) {
    }

    private final boolean enabled;
    private final Map<String, String> imports = new HashMap<>();
    private final Deque<Set<String>> typeVars = new ArrayDeque<>();

    TypeResolver(boolean enabled, CompilationUnit unit) {
        this.enabled = enabled;
        for (ImportDeclaration declaration : unit.getImports()) {
            if (!declaration.isStatic() && !declaration.isAsterisk()) {
                String name = declaration.getNameAsString();
                imports.putIfAbsent(name.substring(name.lastIndexOf('.') + 1), name);
            }
        }
    }

    void pushTypeVars(List<TypeParameter> parameters) {
        Set<String> names = new HashSet<>();
        for (TypeParameter parameter : parameters) {
            names.add(parameter.getNameAsString());
        }
        typeVars.push(names);
    }

    void popTypeVars() {
        typeVars.pop();
    }

    private boolean isTypeVariable(String name) {
        for (Set<String> names : typeVars) {
            if (names.contains(name)) {
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
        if (enabled) {
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
        if (enabled) {
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
        if (!enabled) {
            return null;
        }
        try {
            ResolvedMethodDeclaration declaration = call.resolve();
            return methodCallee(declaration);
        } catch (RuntimeException | StackOverflowError e) {
            return null;
        }
    }

    Callee methodRef(MethodReferenceExpr reference) {
        if (!enabled) {
            return null;
        }
        try {
            return methodCallee(reference.resolve());
        } catch (RuntimeException | StackOverflowError e) {
            return null;
        }
    }

    Callee constructor(ObjectCreationExpr creation) {
        if (!enabled) {
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
        List<String> names = new ArrayList<>();
        for (int i = 0; i < declaration.getNumberOfParams(); i++) {
            ResolvedParameterDeclaration parameter = declaration.getParam(i);
            String name = resolvedName(parameter.getType());
            if (parameter.isVariadic() && name.endsWith("[]")) {
                name = name.substring(0, name.length() - 2) + "...";
            }
            names.add(name);
        }
        return String.join(",", names);
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
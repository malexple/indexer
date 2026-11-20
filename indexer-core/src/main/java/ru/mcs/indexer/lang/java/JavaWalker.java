package ru.mcs.indexer.lang.java;

import com.github.javaparser.Range;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.AnnotationMemberDeclaration;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.CompactConstructorDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumConstantDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.InitializerDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.comments.Comment;
import com.github.javaparser.ast.comments.JavadocComment;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.type.TypeParameter;
import ru.mcs.indexer.lang.FileFacts;
import ru.mcs.indexer.lang.FileFacts.AnnotationRow;
import ru.mcs.indexer.lang.FileFacts.ImportRow;
import ru.mcs.indexer.lang.FileFacts.MemberRow;
import ru.mcs.indexer.lang.FileFacts.RelationRow;
import ru.mcs.indexer.lang.FileFacts.TypeRow;
import ru.mcs.indexer.lang.java.TypeResolver.Named;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/** Walks one CompilationUnit and fills FileFacts. Single-threaded; one instance per file. */
final class JavaWalker {

    private final CompilationUnit unit;
    private final String content;
    private final FileFacts out;
    private final TypeResolver resolver;
    private final CallCollector calls;
    private final int[] lineStarts;
    private final String packageName;
    private final List<Integer> topLevel = new ArrayList<>();

    JavaWalker(CompilationUnit unit, String content, FileFacts out, TypeResolver resolver) {
        this.unit = unit;
        this.content = content;
        this.out = out;
        this.resolver = resolver;
        this.calls = new CallCollector(out, resolver);
        this.lineStarts = computeLineStarts(content);
        this.packageName = unit.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
    }

    void run() {
        for (ImportDeclaration declaration : unit.getImports()) {
            String name = declaration.getNameAsString() + (declaration.isAsterisk() ? ".*" : "");
            out.addImport(new ImportRow(name, declaration.isStatic(), declaration.isAsterisk(),
                    beginLine(declaration)));
        }
        for (TypeDeclaration<?> type : unit.getTypes()) {
            visitType(type, -1, null, false);
        }
        importDeps();
    }

    private void importDeps() {
        for (int local : topLevel) {
            String self = out.types().get(local).fqn();
            for (ImportRow row : out.imports()) {
                String to = row.name();
                boolean resolved = true;
                if (row.isAsterisk()) {
                    to = to.substring(0, to.length() - 2);
                    resolved = false;
                } else if (row.isStatic() && to.lastIndexOf('.') > 0) {
                    to = to.substring(0, to.lastIndexOf('.'));
                }
                TypeResolver.addDep(out, local, self, to, "IMPORT", resolved);
            }
        }
    }

    private void visitType(TypeDeclaration<?> declaration, int parentLocal, String parentFqn,
                           boolean parentInterfaceLike) {
        String kind;
        NodeList<TypeParameter> typeParameters = new NodeList<>();
        if (declaration instanceof ClassOrInterfaceDeclaration c) {
            kind = c.isInterface() ? "INTERFACE" : "CLASS";
            typeParameters = c.getTypeParameters();
        } else if (declaration instanceof EnumDeclaration) {
            kind = "ENUM";
        } else if (declaration instanceof RecordDeclaration r) {
            kind = "RECORD";
            typeParameters = r.getTypeParameters();
        } else if (declaration instanceof AnnotationDeclaration) {
            kind = "ANNOTATION";
        } else {
            return;
        }
        String simpleName = declaration.getNameAsString();
        String fqn = parentFqn != null ? parentFqn + "." + simpleName
                : (packageName.isEmpty() ? simpleName : packageName + "." + simpleName);
        boolean explicitPublic = declaration.getModifiers().stream()
                .anyMatch(m -> m.getKeyword() == Modifier.Keyword.PUBLIC);
        int[] lines = lines(declaration);
        int local = out.addType(new TypeRow(parentLocal, fqn, simpleName, packageName, kind,
                modifiers(declaration.getModifiers()), explicitPublic || parentInterfaceLike,
                lines[0], lines[1], doc(declaration)));
        if (parentLocal < 0) {
            topLevel.add(local);
        }

        resolver.pushTypeVars(typeParameters);
        try {
            annotations(declaration.getAnnotations(), "TYPE", local, local, fqn);
            if (declaration instanceof ClassOrInterfaceDeclaration c) {
                relations(local, fqn, "EXTENDS", c.getExtendedTypes());
                relations(local, fqn, "IMPLEMENTS", c.getImplementedTypes());
                relations(local, fqn, "PERMITS", c.getPermittedTypes());
            } else if (declaration instanceof EnumDeclaration e) {
                relations(local, fqn, "IMPLEMENTS", e.getImplementedTypes());
            } else if (declaration instanceof RecordDeclaration r) {
                relations(local, fqn, "IMPLEMENTS", r.getImplementedTypes());
            }
            boolean interfaceLike = kind.equals("INTERFACE") || kind.equals("ANNOTATION");
            List<String> componentTypes = new ArrayList<>();

            if (declaration instanceof RecordDeclaration r) {
                for (Parameter parameter : r.getParameters()) {
                    String type = parameterType(parameter);
                    componentTypes.add(type);
                    int[] range = lines(parameter);
                    int member = out.addMember(new MemberRow(local, "RECORD_COMPONENT", parameter.getNameAsString(),
                            parameter.getNameAsString() + ":" + type, type, "", range[0], range[1], null));
                    annotations(parameter.getAnnotations(), "MEMBER", member, local, fqn);
                    typeDeps(local, fqn, parameter.getType(), "FIELD");
                }
            }
            if (declaration instanceof EnumDeclaration e) {
                for (EnumConstantDeclaration constant : e.getEntries()) {
                    int[] range = lines(constant);
                    int member = out.addMember(new MemberRow(local, "ENUM_CONSTANT", constant.getNameAsString(),
                            constant.getNameAsString(), null, "", range[0], range[1], doc(constant)));
                    annotations(constant.getAnnotations(), "MEMBER", member, local, fqn);
                    calls.collect(constant, member, local, fqn);
                }
            }
            for (BodyDeclaration<?> body : declaration.getMembers()) {
                if (body instanceof TypeDeclaration<?> nested) {
                    visitType(nested, local, fqn, interfaceLike);
                } else if (body instanceof MethodDeclaration m) {
                    visitMethod(local, fqn, m);
                } else if (body instanceof ConstructorDeclaration c) {
                    visitConstructor(local, fqn, simpleName, c);
                } else if (body instanceof CompactConstructorDeclaration c) {
                    int[] range = lines(c);
                    int member = out.addMember(new MemberRow(local, "CONSTRUCTOR", simpleName,
                            simpleName + "(" + String.join(",", componentTypes) + ")", null,
                            modifiers(c.getModifiers()), range[0], range[1], doc(c)));
                    annotations(c.getAnnotations(), "MEMBER", member, local, fqn);
                    calls.collect(c, member, local, fqn);
                } else if (body instanceof FieldDeclaration f) {
                    visitField(local, fqn, f);
                } else if (body instanceof InitializerDeclaration i) {
                    String name = i.isStatic() ? "<clinit>" : "<init>";
                    int[] range = lines(i);
                    int member = out.addMember(new MemberRow(local, "INITIALIZER", name, name + "()", null,
                            i.isStatic() ? "static" : "", range[0], range[1], doc(i)));
                    calls.collect(i, member, local, fqn);
                } else if (body instanceof AnnotationMemberDeclaration a) {
                    String type = resolver.typeName(a.getType());
                    int[] range = lines(a);
                    int member = out.addMember(new MemberRow(local, "METHOD", a.getNameAsString(),
                            a.getNameAsString() + "():" + type, type, modifiers(a.getModifiers()),
                            range[0], range[1], doc(a)));
                    annotations(a.getAnnotations(), "MEMBER", member, local, fqn);
                    typeDeps(local, fqn, a.getType(), "RETURN");
                }
            }
        } finally {
            resolver.popTypeVars();
        }
    }

    private void visitMethod(int typeLocal, String typeFqn, MethodDeclaration method) {
        resolver.pushTypeVars(method.getTypeParameters());
        try {
            String returnType = resolver.typeName(method.getType());
            String signature = method.getNameAsString() + "(" + parameterList(method.getParameters()) + "):"
                    + returnType;
            int[] range = lines(method);
            int member = out.addMember(new MemberRow(typeLocal, "METHOD", method.getNameAsString(), signature,
                    returnType, modifiers(method.getModifiers()), range[0], range[1], doc(method)));
            annotations(method.getAnnotations(), "MEMBER", member, typeLocal, typeFqn);
            typeDeps(typeLocal, typeFqn, method.getType(), "RETURN");
            for (Parameter parameter : method.getParameters()) {
                typeDeps(typeLocal, typeFqn, parameter.getType(), "PARAM");
            }
            calls.collect(method, member, typeLocal, typeFqn);
        } finally {
            resolver.popTypeVars();
        }
    }

    private void visitConstructor(int typeLocal, String typeFqn, String typeName, ConstructorDeclaration ctor) {
        resolver.pushTypeVars(ctor.getTypeParameters());
        try {
            int[] range = lines(ctor);
            int member = out.addMember(new MemberRow(typeLocal, "CONSTRUCTOR", typeName,
                    typeName + "(" + parameterList(ctor.getParameters()) + ")", null,
                    modifiers(ctor.getModifiers()), range[0], range[1], doc(ctor)));
            annotations(ctor.getAnnotations(), "MEMBER", member, typeLocal, typeFqn);
            for (Parameter parameter : ctor.getParameters()) {
                typeDeps(typeLocal, typeFqn, parameter.getType(), "PARAM");
            }
            calls.collect(ctor, member, typeLocal, typeFqn);
        } finally {
            resolver.popTypeVars();
        }
    }

    private void visitField(int typeLocal, String typeFqn, FieldDeclaration field) {
        int[] range = lines(field);
        String modifiers = modifiers(field.getModifiers());
        String doc = doc(field);
        for (VariableDeclarator variable : field.getVariables()) {
            String type = resolver.typeName(variable.getType());
            int member = out.addMember(new MemberRow(typeLocal, "FIELD", variable.getNameAsString(),
                    variable.getNameAsString() + ":" + type, type, modifiers, range[0], range[1], doc));
            annotations(field.getAnnotations(), "MEMBER", member, typeLocal, typeFqn);
            typeDeps(typeLocal, typeFqn, variable.getType(), "FIELD");
            variable.getInitializer().ifPresent(init -> calls.collect(init, member, typeLocal, typeFqn));
        }
    }

    private void relations(int typeLocal, String typeFqn, String relation, NodeList<ClassOrInterfaceType> types) {
        for (ClassOrInterfaceType type : types) {
            Named named = resolver.classType(type);
            out.addRelation(new RelationRow(typeLocal, relation, TypeResolver.written(type),
                    named.qualified() && !named.typeVariable() ? named.name() : null, named.resolved()));
            if (!relation.equals("PERMITS") && !named.typeVariable()) {
                TypeResolver.addDep(out, typeLocal, typeFqn, named.name(), relation, named.resolved());
            }
        }
    }

    private void typeDeps(int typeLocal, String typeFqn, Type type, String kind) {
        for (ClassOrInterfaceType node : type.findAll(ClassOrInterfaceType.class)) {
            boolean isScope = node.getParentNode()
                    .map(parent -> parent instanceof ClassOrInterfaceType p && p.getScope().orElse(null) == node)
                    .orElse(false);
            if (isScope) {
                continue;
            }
            Named named = resolver.classType(node);
            if (!named.typeVariable()) {
                TypeResolver.addDep(out, typeLocal, typeFqn, named.name(), kind, named.resolved());
            }
        }
    }

    private void annotations(NodeList<AnnotationExpr> annotations, String ownerKind, int ownerLocal,
                             int typeLocal, String typeFqn) {
        for (AnnotationExpr annotation : annotations) {
            out.addAnnotation(new AnnotationRow(ownerKind, ownerLocal, annotation.getNameAsString(),
                    annotationArgs(annotation), beginLine(annotation)));
            Named named = resolver.annotation(annotation);
            TypeResolver.addDep(out, typeLocal, typeFqn, named.name(), "ANNOTATION", named.resolved());
        }
    }

    private String annotationArgs(AnnotationExpr annotation) {
        String sliced = slice(annotation);
        if (sliced != null && sliced.startsWith("@")) {
            int open = sliced.indexOf('(');
            if (open < 0) {
                return null;
            }
            int close = sliced.lastIndexOf(')');
            if (close > open) {
                String args = sliced.substring(open + 1, close).strip();
                return args.isEmpty() ? null : args;
            }
        }
        if (annotation instanceof NormalAnnotationExpr normal) {
            String args = normal.getPairs().stream()
                    .map((MemberValuePair p) -> p.getNameAsString() + " = " + p.getValue())
                    .collect(Collectors.joining(", "));
            return args.isEmpty() ? null : args;
        }
        if (annotation instanceof SingleMemberAnnotationExpr single) {
            return single.getMemberValue().toString();
        }
        return null;
    }

    private String slice(Node node) {
        Optional<Range> range = node.getRange();
        if (range.isEmpty()) {
            return null;
        }
        Range r = range.get();
        int beginLine = r.begin.line - 1;
        int endLine = r.end.line - 1;
        if (beginLine < 0 || endLine >= lineStarts.length || endLine < beginLine) {
            return null;
        }
        int start = lineStarts[beginLine] + r.begin.column - 1;
        int end = lineStarts[endLine] + r.end.column;
        if (start < 0 || end > content.length() || start >= end) {
            return null;
        }
        return content.substring(start, end);
    }

    private String parameterList(NodeList<Parameter> parameters) {
        return parameters.stream().map(this::parameterType).collect(Collectors.joining(","));
    }

    private String parameterType(Parameter parameter) {
        return resolver.typeName(parameter.getType()) + (parameter.isVarArgs() ? "..." : "");
    }

    private static Optional<Comment> javadoc(Node node) {
        return node.getComment().filter(comment -> comment instanceof JavadocComment);
    }

    private static String doc(Node node) {
        return javadoc(node).map(c -> JavadocSummary.firstSentence(c.getContent())).orElse(null);
    }

    private static int beginLine(Node node) {
        return node.getRange().map(r -> r.begin.line).orElse(1);
    }

    private static int[] lines(Node node) {
        Optional<Range> range = node.getRange();
        if (range.isEmpty()) {
            return new int[]{1, 1};
        }
        int from = range.get().begin.line;
        Optional<Comment> doc = javadoc(node);
        if (doc.isPresent() && doc.get().getRange().isPresent()) {
            from = Math.min(from, doc.get().getRange().get().begin.line);
        }
        return new int[]{from, range.get().end.line};
    }

    private static String modifiers(NodeList<Modifier> modifiers) {
        return modifiers.stream().map(m -> m.getKeyword().asString()).distinct().sorted()
                .collect(Collectors.joining(" "));
    }

    private static int[] computeLineStarts(String text) {
        List<Integer> starts = new ArrayList<>();
        starts.add(0);
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                starts.add(i + 1);
            }
        }
        return starts.stream().mapToInt(Integer::intValue).toArray();
    }
}
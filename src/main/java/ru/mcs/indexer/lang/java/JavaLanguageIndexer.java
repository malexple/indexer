package ru.mcs.indexer.lang.java;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Problem;
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
import com.github.javaparser.ast.type.ArrayType;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import ru.mcs.indexer.lang.FileContext;
import ru.mcs.indexer.lang.FileFacts;
import ru.mcs.indexer.lang.FileFacts.AnnotationRow;
import ru.mcs.indexer.lang.FileFacts.ImportRow;
import ru.mcs.indexer.lang.FileFacts.MemberRow;
import ru.mcs.indexer.lang.FileFacts.RelationRow;
import ru.mcs.indexer.lang.FileFacts.TypeRow;
import ru.mcs.indexer.lang.LanguageIndexer;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public final class JavaLanguageIndexer implements LanguageIndexer {

    private static final int MAX_REPORTED_PROBLEMS = 3;

    // JavaParser and ParserConfiguration are not thread-safe: one instance per worker thread.
    private final ThreadLocal<JavaParser> parsers = ThreadLocal.withInitial(JavaLanguageIndexer::newParser);

    private static JavaParser newParser() {
        ParserConfiguration configuration = new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21)
                .setTabSize(1)
                .setDetectOriginalLineSeparator(false);
        return new JavaParser(configuration);
    }

    @Override
    public String language() {
        return "java";
    }

    @Override
    public Set<String> extensions() {
        return Set.of("java");
    }

    @Override
    public void index(FileContext ctx, FileFacts out) {
        ParseResult<CompilationUnit> result;
        try {
            result = parsers.get().parse(ctx.content());
        } catch (RuntimeException | StackOverflowError e) {
            out.addError("PARSE", "parser failed: " + e);
            return;
        }
        if (!result.isSuccessful()) {
            List<Problem> problems = result.getProblems();
            StringBuilder message = new StringBuilder(problems.size() + " syntax problem(s): ");
            int shown = Math.min(problems.size(), MAX_REPORTED_PROBLEMS);
            for (int i = 0; i < shown; i++) {
                if (i > 0) {
                    message.append(" | ");
                }
                message.append(problems.get(i).toString());
            }
            out.addError("PARSE", message.toString().replaceAll("\\s+", " "));
        }
        Optional<CompilationUnit> unit = result.getResult();
        if (unit.isEmpty()) {
            return;
        }
        try {
            new Walker(unit.get(), ctx.content(), out).run();
        } catch (RuntimeException e) {
            out.addError("PARSE", "AST walk failed: " + e);
        }
    }

    private static final class Walker {

        private final CompilationUnit unit;
        private final String content;
        private final FileFacts out;
        private final int[] lineStarts;
        private final String packageName;

        Walker(CompilationUnit unit, String content, FileFacts out) {
            this.unit = unit;
            this.content = content;
            this.out = out;
            this.lineStarts = computeLineStarts(content);
            this.packageName = unit.getPackageDeclaration()
                    .map(p -> p.getNameAsString())
                    .orElse("");
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
        }

        private void visitType(TypeDeclaration<?> declaration, int parentLocal, String parentFqn,
                               boolean parentInterfaceLike) {
            String kind;
            if (declaration instanceof ClassOrInterfaceDeclaration c) {
                kind = c.isInterface() ? "INTERFACE" : "CLASS";
            } else if (declaration instanceof EnumDeclaration) {
                kind = "ENUM";
            } else if (declaration instanceof RecordDeclaration) {
                kind = "RECORD";
            } else if (declaration instanceof AnnotationDeclaration) {
                kind = "ANNOTATION";
            } else {
                return;
            }
            String simpleName = declaration.getNameAsString();
            String fqn;
            if (parentFqn != null) {
                fqn = parentFqn + "." + simpleName;
            } else {
                fqn = packageName.isEmpty() ? simpleName : packageName + "." + simpleName;
            }
            String modifiers = modifiers(declaration.getModifiers());
            boolean explicitPublic = declaration.getModifiers().stream()
                    .anyMatch(m -> m.getKeyword() == Modifier.Keyword.PUBLIC);
            int[] lines = lines(declaration);
            int local = out.addType(new TypeRow(parentLocal, fqn, simpleName, packageName, kind, modifiers,
                    explicitPublic || parentInterfaceLike, lines[0], lines[1], doc(declaration)));
            annotations(declaration.getAnnotations(), "TYPE", local);

            if (declaration instanceof ClassOrInterfaceDeclaration c) {
                relations(local, "EXTENDS", c.getExtendedTypes());
                relations(local, "IMPLEMENTS", c.getImplementedTypes());
                relations(local, "PERMITS", c.getPermittedTypes());
            } else if (declaration instanceof EnumDeclaration e) {
                relations(local, "IMPLEMENTS", e.getImplementedTypes());
            } else if (declaration instanceof RecordDeclaration r) {
                relations(local, "IMPLEMENTS", r.getImplementedTypes());
            }

            boolean interfaceLike = kind.equals("INTERFACE") || kind.equals("ANNOTATION");
            List<String> recordComponentTypes = new ArrayList<>();

            if (declaration instanceof RecordDeclaration r) {
                for (Parameter parameter : r.getParameters()) {
                    String type = parameterType(parameter);
                    recordComponentTypes.add(type);
                    int[] range = lines(parameter);
                    int member = out.addMember(new MemberRow(local, "RECORD_COMPONENT",
                            parameter.getNameAsString(), parameter.getNameAsString() + ":" + type, type, "",
                            range[0], range[1], null));
                    annotations(parameter.getAnnotations(), "MEMBER", member);
                }
            }
            if (declaration instanceof EnumDeclaration e) {
                for (EnumConstantDeclaration constant : e.getEntries()) {
                    int[] range = lines(constant);
                    int member = out.addMember(new MemberRow(local, "ENUM_CONSTANT",
                            constant.getNameAsString(), constant.getNameAsString(), null, "",
                            range[0], range[1], doc(constant)));
                    annotations(constant.getAnnotations(), "MEMBER", member);
                }
            }

            for (BodyDeclaration<?> body : declaration.getMembers()) {
                if (body instanceof TypeDeclaration<?> nested) {
                    visitType(nested, local, fqn, interfaceLike);
                } else if (body instanceof MethodDeclaration m) {
                    visitMethod(local, m);
                } else if (body instanceof ConstructorDeclaration c) {
                    visitConstructor(local, simpleName, c);
                } else if (body instanceof CompactConstructorDeclaration c) {
                    visitCompactConstructor(local, simpleName, c, recordComponentTypes);
                } else if (body instanceof FieldDeclaration f) {
                    visitField(local, f);
                } else if (body instanceof InitializerDeclaration i) {
                    visitInitializer(local, i);
                } else if (body instanceof AnnotationMemberDeclaration a) {
                    visitAnnotationMember(local, a);
                }
            }
        }

        private void visitMethod(int typeLocal, MethodDeclaration method) {
            String returnType = typeName(method.getType());
            String signature = method.getNameAsString() + "(" + parameterList(method.getParameters()) + "):"
                    + returnType;
            int[] range = lines(method);
            int member = out.addMember(new MemberRow(typeLocal, "METHOD", method.getNameAsString(), signature,
                    returnType, modifiers(method.getModifiers()), range[0], range[1], doc(method)));
            annotations(method.getAnnotations(), "MEMBER", member);
        }

        private void visitConstructor(int typeLocal, String typeName, ConstructorDeclaration constructor) {
            String signature = typeName + "(" + parameterList(constructor.getParameters()) + ")";
            int[] range = lines(constructor);
            int member = out.addMember(new MemberRow(typeLocal, "CONSTRUCTOR", typeName, signature, null,
                    modifiers(constructor.getModifiers()), range[0], range[1], doc(constructor)));
            annotations(constructor.getAnnotations(), "MEMBER", member);
        }

        private void visitCompactConstructor(int typeLocal, String typeName,
                                             CompactConstructorDeclaration constructor,
                                             List<String> componentTypes) {
            String signature = typeName + "(" + String.join(",", componentTypes) + ")";
            int[] range = lines(constructor);
            int member = out.addMember(new MemberRow(typeLocal, "CONSTRUCTOR", typeName, signature, null,
                    modifiers(constructor.getModifiers()), range[0], range[1], doc(constructor)));
            annotations(constructor.getAnnotations(), "MEMBER", member);
        }

        private void visitField(int typeLocal, FieldDeclaration field) {
            int[] range = lines(field);
            String modifiers = modifiers(field.getModifiers());
            String doc = doc(field);
            for (VariableDeclarator variable : field.getVariables()) {
                String type = typeName(variable.getType());
                int member = out.addMember(new MemberRow(typeLocal, "FIELD", variable.getNameAsString(),
                        variable.getNameAsString() + ":" + type, type, modifiers, range[0], range[1], doc));
                annotations(field.getAnnotations(), "MEMBER", member);
            }
        }

        private void visitInitializer(int typeLocal, InitializerDeclaration initializer) {
            String name = initializer.isStatic() ? "<clinit>" : "<init>";
            int[] range = lines(initializer);
            out.addMember(new MemberRow(typeLocal, "INITIALIZER", name, name + "()", null,
                    initializer.isStatic() ? "static" : "", range[0], range[1], doc(initializer)));
        }

        private void visitAnnotationMember(int typeLocal, AnnotationMemberDeclaration declaration) {
            String returnType = typeName(declaration.getType());
            int[] range = lines(declaration);
            int member = out.addMember(new MemberRow(typeLocal, "METHOD", declaration.getNameAsString(),
                    declaration.getNameAsString() + "():" + returnType, returnType,
                    modifiers(declaration.getModifiers()), range[0], range[1], doc(declaration)));
            annotations(declaration.getAnnotations(), "MEMBER", member);
        }

        private void relations(int typeLocal, String relation, NodeList<ClassOrInterfaceType> types) {
            for (ClassOrInterfaceType type : types) {
                out.addRelation(new RelationRow(typeLocal, relation, classTypeName(type), null, false));
            }
        }

        private void annotations(NodeList<AnnotationExpr> annotations, String ownerKind, int ownerLocal) {
            for (AnnotationExpr annotation : annotations) {
                out.addAnnotation(new AnnotationRow(ownerKind, ownerLocal, annotation.getNameAsString(),
                        annotationArgs(annotation), beginLine(annotation)));
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
            return modifiers.stream()
                    .map(m -> m.getKeyword().asString())
                    .distinct()
                    .sorted()
                    .collect(Collectors.joining(" "));
        }

        private static String parameterList(NodeList<Parameter> parameters) {
            return parameters.stream()
                    .map(Walker::parameterType)
                    .collect(Collectors.joining(","));
        }

        private static String parameterType(Parameter parameter) {
            return typeName(parameter.getType()) + (parameter.isVarArgs() ? "..." : "");
        }

        static String typeName(Type type) {
            if (type instanceof ArrayType array) {
                return typeName(array.getComponentType()) + "[]";
            }
            if (type instanceof ClassOrInterfaceType classType) {
                return classTypeName(classType);
            }
            return type.asString();
        }

        static String classTypeName(ClassOrInterfaceType type) {
            return type.getScope().map(scope -> classTypeName(scope) + ".").orElse("") + type.getNameAsString();
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
}
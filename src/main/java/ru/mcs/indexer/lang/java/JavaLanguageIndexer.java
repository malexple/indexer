package ru.mcs.indexer.lang.java;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.Problem;
import com.github.javaparser.ast.CompilationUnit;
import ru.mcs.indexer.lang.FileContext;
import ru.mcs.indexer.lang.FileFacts;
import ru.mcs.indexer.lang.LanguageIndexer;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public final class JavaLanguageIndexer implements LanguageIndexer {

    private static final int MAX_REPORTED_PROBLEMS = 3;

    // JavaParser, ParserConfiguration and JavaSymbolSolver are not thread-safe: one stack per worker thread.
    private final ThreadLocal<JavaParser> parsers;
    private final boolean resolve;

    public JavaLanguageIndexer() {
        this(List.of(), false);
    }

    public JavaLanguageIndexer(List<Path> sourceRoots, boolean resolve) {
        List<Path> roots = List.copyOf(sourceRoots);
        this.resolve = resolve;
        this.parsers = ThreadLocal.withInitial(() -> ResolverFactory.newParser(roots, resolve));
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
            new JavaWalker(unit.get(), ctx.content(), out, new TypeResolver(resolve, unit.get())).run();
        } catch (RuntimeException | StackOverflowError e) {
            out.addError("PARSE", "AST walk failed: " + e);
        }
    }
}
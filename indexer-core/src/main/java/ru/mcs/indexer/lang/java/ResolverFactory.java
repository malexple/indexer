package ru.mcs.indexer.lang.java;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/** Builds a parser (and, optionally, its private symbol solver stack). Not shared between threads. */
final class ResolverFactory {

    private ResolverFactory() {
    }

    static JavaParser newParser(List<Path> sourceRoots, boolean resolve, Set<String> knownTypes) {
        ParserConfiguration config = baseConfiguration();
        if (resolve) {
            CombinedTypeSolver solver = new CombinedTypeSolver();
            solver.add(new ReflectionTypeSolver());
            for (Path root : sourceRoots) {
                solver.add(new PrunedJavaParserTypeSolver(root, baseConfiguration(), knownTypes));
            }
            config.setSymbolResolver(new JavaSymbolSolver(solver));
        }
        return new JavaParser(config);
    }

    private static ParserConfiguration baseConfiguration() {
        return new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21)
                .setTabSize(1)
                .setDetectOriginalLineSeparator(false);
    }
}
package ru.mcs.indexer.lang.java;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.resolution.model.SymbolReference;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;

import java.nio.file.Path;
import java.util.Set;

/**
 * JavaParserTypeSolver that answers "unsolved" from memory for names that cannot correspond to any
 * project source file, instead of probing the file system (which is slow on Windows and does not scale
 * across threads). Names that may match a project file are delegated unchanged.
 */
final class PrunedJavaParserTypeSolver extends JavaParserTypeSolver {

    private final Set<String> knownTypes;

    PrunedJavaParserTypeSolver(Path sourceRoot, ParserConfiguration configuration, Set<String> knownTypes) {
        super(sourceRoot, configuration);
        this.knownTypes = knownTypes;
    }

    @Override
    public SymbolReference<ResolvedReferenceTypeDeclaration> tryToSolveType(String name) {
        String prefix = name;
        while (true) {
            if (knownTypes.contains(prefix)) {
                return super.tryToSolveType(name);
            }
            int dot = prefix.lastIndexOf('.');
            if (dot < 0) {
                return SymbolReference.unsolved();
            }
            prefix = prefix.substring(0, dot);
        }
    }
}
package ru.mcs.indexer.lang.java;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.TypeExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import ru.mcs.indexer.lang.FileFacts;
import ru.mcs.indexer.lang.FileFacts.CallRow;
import ru.mcs.indexer.lang.java.TypeResolver.Callee;
import ru.mcs.indexer.lang.java.TypeResolver.Named;

/** Collects calls, instantiations and method references below one member, plus CALL/NEW type deps. */
final class CallCollector {

    private final FileFacts out;
    private final TypeResolver resolver;

    CallCollector(FileFacts out, TypeResolver resolver) {
        this.out = out;
        this.resolver = resolver;
    }

    void collect(Node member, int callerLocal, int typeLocal, String typeFqn) {
        for (MethodCallExpr call : member.findAll(MethodCallExpr.class)) {
            Callee callee = resolver.method(call);
            String owner = callee != null ? callee.owner()
                    : call.getScope().map(resolver::ownerFromScope).orElse(null);
            out.addCall(new CallRow(callerLocal, "METHOD", call.getNameAsString(), owner,
                    callee != null ? callee.signature() : null, callee != null, line(call)));
            TypeResolver.addDep(out, typeLocal, typeFqn, owner, "CALL", callee != null);
        }
        for (ObjectCreationExpr creation : member.findAll(ObjectCreationExpr.class)) {
            ClassOrInterfaceType type = creation.getType();
            Callee callee = resolver.constructor(creation);
            Named named = resolver.classType(type);
            String owner = callee != null ? callee.owner() : (named.qualified() && !named.typeVariable()
                    ? named.name() : null);
            boolean resolved = callee != null || (named.resolved() && !named.typeVariable());
            out.addCall(new CallRow(callerLocal, "NEW", type.getNameAsString(), owner,
                    callee != null ? callee.signature() : null, resolved, line(creation)));
            TypeResolver.addDep(out, typeLocal, typeFqn, owner, "NEW", resolved);
        }
        for (MethodReferenceExpr reference : member.findAll(MethodReferenceExpr.class)) {
            collectReference(reference, callerLocal, typeLocal, typeFqn);
        }
    }

    private void collectReference(MethodReferenceExpr reference, int callerLocal, int typeLocal, String typeFqn) {
        boolean constructorRef = reference.getIdentifier().equals("new");
        String name = reference.getIdentifier();
        String owner = null;
        String signature = null;
        boolean resolved = false;

        if (constructorRef) {
            if (reference.getScope() instanceof TypeExpr typeExpr
                    && typeExpr.getType() instanceof ClassOrInterfaceType type) {
                name = type.getNameAsString();
                Named named = resolver.classType(type);
                if (!named.typeVariable() && named.qualified()) {
                    owner = named.name();
                    resolved = named.resolved();
                }
            }
        } else {
            Callee callee = resolver.methodRef(reference);
            if (callee != null) {
                owner = callee.owner();
                signature = callee.signature();
                resolved = true;
            } else if (reference.getScope() instanceof TypeExpr typeExpr
                    && typeExpr.getType() instanceof ClassOrInterfaceType type) {
                Named named = resolver.classType(type);
                if (!named.typeVariable() && named.qualified()) {
                    owner = named.name();
                }
            } else if (reference.getScope() instanceof NameExpr) {
                owner = resolver.ownerFromScope(reference.getScope());
            }
        }
        out.addCall(new CallRow(callerLocal, constructorRef ? "CONSTRUCTOR_REF" : "METHOD_REF", name, owner,
                signature, resolved, line(reference)));
        TypeResolver.addDep(out, typeLocal, typeFqn, owner, constructorRef ? "NEW" : "CALL", resolved);
    }

    private static int line(Node node) {
        return node.getRange().map(r -> r.begin.line).orElse(1);
    }
}
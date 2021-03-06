package my.powerassert;

import com.sun.source.tree.AssertTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.StatementTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.comp.Attr;
import com.sun.tools.javac.comp.Enter;
import com.sun.tools.javac.processing.JavacProcessingEnvironment;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.JCTree.*;
import my.powerassert.javac.Replacements;

import javax.lang.model.element.Element;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class ClassMorpher {

    private final CompilerFacade compilerFacade;
    private final TreeFactory t;
    private final JavacProcessingEnvironment processingEnvironment;
    private final CompilationUnitTree compilationUnit;
    private final CharSequence source;
    private final ExpressionMorpher expressionMorpher;
    private final TreePath path;
    private boolean attributed;

    public ClassMorpher(Element element, CompilerFacade compilerFacade, JavacProcessingEnvironment processingEnvironment, CompilationUnitTree compilationUnit, TreeFactory treeFactory, TreePath path, ExpressionMorpher expressionMorpher) {
        this.compilerFacade = compilerFacade;
        this.processingEnvironment = processingEnvironment;
        this.compilationUnit = compilationUnit;
        this.t = treeFactory;
        this.expressionMorpher = expressionMorpher;
        this.path = path;
        try {
            source = ((Symbol.ClassSymbol) element).sourcefile.getCharContent(false);
        } catch (IOException e) {
            throw new RuntimeException("Cannot get char content of " + element, e);
        }
    }

    public void run(final Replacements replacements) {
        new TreePathScanner<Object, Object>() {
            @Override
            public Object visitAssert(AssertTree assertTree, Object o) {
                JCAssert jcAssert = (JCAssert) assertTree;
                attributeIfNeeded();
                List<JCStatement> statements = new ArrayList<JCStatement>();
                int basePosition = jcAssert.getCondition().getStartPosition();
                t.setPosition(basePosition);
                // generated: PowerAssert _powerassert = new PowerAssert(<message>, <expressionString>);
                JCExpression instantiation = t.new_("my.powerassert.PowerAssert"
                        , jcAssert.getDetail() != null ? jcAssert.getDetail() : t.literal("assertion failed")
                        , t.literal(sourceFor(jcAssert.getCondition())));
                JCVariableDecl declaration = t.var("_powerassert", "my.powerassert.PowerAssert", instantiation);
                statements.add(declaration);
                for (ExpressionPart<JCExpression> part : expressionMorpher.splitExpression(jcAssert.getCondition())) {
                    // generated: try {
                    //                _powerassert.part(<level>, <position>, <expression>);
                    //            } catch (java.lang.Throwable _powerassert_catch) {
                    //            }
                    JCMethodInvocation invocation = t.call("_powerassert", "part"
                            , t.literal(part.level), t.literal(part.position - basePosition)
                            , part.expression);
                    JCCatch catchBlock = t.catch_("_powerassert_catch", "java.lang.Throwable");
                    statements.add(t.tryCatch(catchBlock, t.exec(invocation)));
                }
                // generated: throw new java.lang.AssertionError(_powerassert.build());
                JCThrow throwStatement = t.throw_(t.new_("java.lang.AssertionError", t.call("_powerassert", "build")));
                statements.add(throwStatement);
                // generated: if (<expression>) {
                //                // empty
                //            } else {
                //                <statements>
                //            }
                StatementTree substitution = t.if_(jcAssert.getCondition(), t.block(), t.block(statements.toArray(new JCStatement[statements.size()])));
                replacements.add(getCurrentPath().getParentPath().getLeaf(), jcAssert, substitution);
                return null;
            }
        }.scan(path, null);
    }

    private String sourceFor(JCTree tree) {
        return source.subSequence(tree.getStartPosition(), compilerFacade.getEndPosition(compilationUnit, tree)).toString();
    }

    private void attributeIfNeeded() {
        if (!attributed) {
            try {
                Enter enter = Enter.instance(processingEnvironment.getContext());
                Attr.instance(processingEnvironment.getContext()).attribExpr((JCTree) path.getLeaf(), enter.getTopLevelEnv((JCCompilationUnit) compilationUnit), Type.noType);
                attributed = true;
            } catch (Exception e) {
                throw new RuntimeException("Cannot attribute: " + e.getMessage(), e);
            }
        }
    }
}

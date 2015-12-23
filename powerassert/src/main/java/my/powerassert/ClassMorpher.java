package my.powerassert;

import com.sun.source.tree.AssertTree;
import com.sun.source.tree.StatementTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.tools.javac.api.JavacTrees;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.TypeTag;
import com.sun.tools.javac.comp.Attr;
import com.sun.tools.javac.processing.JavacProcessingEnvironment;
import com.sun.tools.javac.tree.EndPosTable;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.JCTree.*;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.Name;
import com.sun.tools.javac.util.Names;
import my.powerassert.javac.Replacements;

import javax.lang.model.element.Element;
import java.io.IOException;
import java.lang.reflect.Array;
import java.util.ArrayList;

class ClassMorpher {

    private final JavacProcessingEnvironment processingEnvironment;
    private final CharSequence source;
    private final EndPosTable endPositions;
    private final Names names;
    private final Factory factory;
    private final ExpressionMorpher expressionMorpher;
    private final JavacTrees trees;
    private final TreePath path;
    private boolean attributed;

    ClassMorpher(Element element, JavacProcessingEnvironment processingEnvironment, EndPosTable endPositions, Names names, Factory factory, TreePath path, ExpressionMorpher expressionMorpher) {
        this.processingEnvironment = processingEnvironment;
        this.endPositions = endPositions;
        this.names = names;
        this.factory = factory;
        this.expressionMorpher = expressionMorpher;
        this.path = path;
        this.trees = JavacTrees.instance(processingEnvironment);
        try {
            source = ((Symbol.ClassSymbol) element).sourcefile.getCharContent(false);
        } catch (IOException e) {
            throw new RuntimeException("Cannot get char content of " + element, e);
        }
    }

    void run(final Replacements replacements) {
        new TreePathScanner<Object, Object>() {
            @Override
            public Object visitAssert(AssertTree assertTree, Object o) {
                attributeLazy();
                int basePosition = ((JCTree) assertTree.getCondition()).getStartPosition();
                Name variable = names.fromString("_powerassert");
                JCExpression powerAssertClass = fullyQualifiedName("my", "powerassert", "PowerAssert");
                JCExpression message = assertTree.getDetail() != null ? (JCExpression) assertTree.getDetail() : factory.Literal(TypeTag.CLASS, "assertion failed");
                JCExpression instantiation = factory.NewClass(null /* encl */
                        , null /* typeargs */
                        , powerAssertClass /* clazz */
                        , List.of(message, factory.Literal(TypeTag.CLASS, sourceFor(assertTree.getCondition()))) /* args */
                        , null /* def */);
                JCVariableDecl declaration = factory.VarDef(factory.Modifiers(0, List.<JCAnnotation>nil()) /* mods */
                        , variable /* name */
                        , powerAssertClass /* vartype */
                        , instantiation /* init */);
                declaration.setPos(basePosition);
                ArrayList<JCStatement> statements = new ArrayList<JCStatement>();
                statements.add(declaration);
                for (ExpressionMorpher.ExpressionPart part : expressionMorpher.splitExpression(assertTree.getCondition())) {
                    JCMethodInvocation invocation = factory.Apply(List.<JCExpression>nil() /* typeargs */
                            , factory.Select(factory.Ident(variable), names.fromString("part")) /* meth */
                            , List.of(factory.Literal(TypeTag.INT, part.level) /* args: level */
                                    , factory.Literal(TypeTag.INT, part.position - basePosition) /* args: position */
                                    , (JCExpression) part.expression /* args: value */
                            ) /* args */);
                    JCVariableDecl catchVariable = factory.VarDef(factory.Modifiers(0, List.<JCAnnotation>nil()) /* mods */
                            , names.fromString("_powerassert_catch") /* name */
                            , fullyQualifiedName("java", "lang", "Throwable") /* vartype */
                            , null /* init */);
                    catchVariable.setPos(basePosition);
                    JCCatch catchBlock = factory.Catch(catchVariable /* param */
                            , factory.Block(0, List.<JCStatement>nil()));
                    statements.add(factory.Try(factory.Block(0, List.<JCStatement>of(factory.Exec(invocation))) /* body */
                            , List.of(catchBlock) /* catchers */
                            , null /* finalizer */));
                }
                JCMethodInvocation buildInvocation = factory.Apply(List.<JCExpression>nil() /* typeargs */
                        , factory.Select(factory.Ident(variable), names.fromString("build")) /* meth */
                        , List.<JCExpression>nil() /* args */);
                buildInvocation.setPos(basePosition);
                JCThrow throwStatement = factory.Throw(factory.NewClass(null /* encl */
                        , null /* typeargs */
                        , fullyQualifiedName("java", "lang", "AssertionError") /* clazz */
                        , List.<JCExpression>of(buildInvocation) /* args */
                        , null /* def */));
                statements.add(throwStatement);
                StatementTree substituation = factory.If(factory.Parens((JCExpression) assertTree.getCondition()), factory.Block(0, List.<JCStatement>nil()), factory.Block(0, toJavacList(JCStatement.class, statements)));
                replacements.add(getCurrentPath().getParentPath().getLeaf(), assertTree, substituation);
                return super.visitAssert(assertTree, o);
            }
        }.scan(path, null);
    }

    private String sourceFor(Tree tree) {
        JCTree jctree = (JCTree) tree;
        return source.subSequence(jctree.getStartPosition(), jctree.getEndPosition(endPositions)).toString();
    }

    private JCExpression fullyQualifiedName(String firstPart, String... remainingParts) {
        JCExpression value = factory.Ident(names.fromString(firstPart));
        for (String remainingPart : remainingParts) {
            value = factory.Select(value, names.fromString(remainingPart));
        }
        return value;
    }

    private void attributeLazy() {
        if (!attributed) {
            Attr.instance(processingEnvironment.getContext()).attribExpr((JCTree) path.getLeaf(), trees.getScope(path).getEnv()); // attribute AST tree with symbolic information
            attributed = true;
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> List<T> toJavacList(Class<T> clazz, ArrayList<T> list) {
        switch (list.size()) {
            case 0: return List.nil();
            case 1: return List.of(list.get(0));
            case 2: return List.of(list.get(0), list.get(1));
            case 3: return List.of(list.get(0), list.get(1), list.get(2));
            default: {
                T[] array = list.toArray((T[]) Array.newInstance(clazz, list.size()));
                T[] remainder = (T[]) Array.newInstance(clazz, list.size() - 3);
                System.arraycopy(array, 3, remainder, 0, remainder.length);
                return List.of(array[0], array[1], array[2], remainder);
            }
        }
    }
}

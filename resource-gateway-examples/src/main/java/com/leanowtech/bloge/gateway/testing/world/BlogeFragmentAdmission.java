package com.leanowtech.bloge.gateway.testing.world;

import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.operator.DecisionTableOperator;
import com.leanowtech.bloge.core.operator.TransformOperator;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.dsl.ast.AstNode;
import com.leanowtech.bloge.dsl.ast.Expression;
import com.leanowtech.bloge.dsl.compiler.DslCompiler;
import com.leanowtech.bloge.dsl.lexer.Lexer;
import com.leanowtech.bloge.dsl.lexer.TokenType;
import com.leanowtech.bloge.gateway.testing.planning.InvocationInventory;
import com.leanowtech.bloge.gateway.testing.planning.InvocationInventoryBuilder;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Fail-closed admission for frozen world fragments using BLOGE's parser, compiler, and compiled
 * invocation inventory. Source text is never scanned with regular expressions.
 */
final class BlogeFragmentAdmission {
    private static final Set<String> PURE_OPERATOR_REFS = Set.of(
            DecisionTableOperator.OPERATOR_REF,
            TransformOperator.OPERATOR_REF);
    private static final Set<String> NON_DETERMINISTIC_FUNCTIONS = Set.of(
            "now", "currenttime", "currentdate", "uuid", "random", "rand");

    record Result(
            String fingerprint,
            int primitiveCount,
            int expressionDepth,
            String outputNodeId,
            List<String> findings
    ) {
        public Result {
            findings = List.copyOf(findings == null ? List.of() : findings);
        }
    }

    record Executable(
            Graph graph,
            DefaultOperatorRegistry registry,
            InvocationInventory inventory,
            Result result
    ) {
        DefaultOperatorRegistry isolatedRegistry() {
            DefaultOperatorRegistry isolated = new DefaultOperatorRegistry();
            isolated.registerRaw(DecisionTableOperator.OPERATOR_REF, DecisionTableOperator.INSTANCE);
            isolated.registerRaw(TransformOperator.OPERATOR_REF, TransformOperator.INSTANCE);
            return isolated;
        }
    }

    private record Inspection(int primitiveCount, int expressionDepth) {
    }

    private BlogeFragmentAdmission() {
    }

    static Result admit(BlogeFragmentRef fragment) {
        return compile(fragment).result();
    }

    static Executable compile(BlogeFragmentRef fragment) {
        if (fragment == null) {
            throw new WorldModelException(WorldModelException.Code.FRAGMENT_INVALID);
        }
        try {
            rejectWorldDelegationTokens(fragment.source());
            DefaultOperatorRegistry registry = new DefaultOperatorRegistry();
            registry.registerRaw(DecisionTableOperator.OPERATOR_REF, DecisionTableOperator.INSTANCE);
            registry.registerRaw(TransformOperator.OPERATOR_REF, TransformOperator.INSTANCE);
            DslCompiler compiler = new DslCompiler(registry);
            AstNode.GraphDef definition = compiler.parse(fragment.source());
            Inspection inspection = inspectGraph(definition);
            Graph graph = compiler.compile(definition);
            String outputNodeId = resolveOutputNode(fragment, graph);
            InvocationInventory inventory = new InvocationInventoryBuilder(registry)
                    .build(graph, fragment.fingerprint());
            proveCompiledInventory(graph, inventory);
            Result result = new Result(fragment.fingerprint(), inspection.primitiveCount(),
                    inspection.expressionDepth(), outputNodeId, List.of());
            return new Executable(graph, registry, inventory, result);
        } catch (WorldModelException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new WorldModelException(WorldModelException.Code.FRAGMENT_INVALID);
        }
    }

    private static void rejectWorldDelegationTokens(String source) {
        boolean importsWorld = new Lexer(source).tokenize().stream()
                .anyMatch(token -> token.type() == TokenType.IMPORT);
        if (importsWorld) {
            throw new WorldModelException(WorldModelException.Code.FRAGMENT_WORLD_DELEGATION_FORBIDDEN);
        }
    }

    private static Inspection inspectGraph(AstNode.GraphDef graph) {
        if (graph == null || graph.members() == null || graph.members().isEmpty()
                || graph.streamingOutputNodeId() != null || !graph.streamingInputs().isEmpty()) {
            throw new WorldModelException(WorldModelException.Code.FRAGMENT_NOT_PURE);
        }
        Set<String> primitiveIds = new LinkedHashSet<>();
        for (AstNode member : graph.members()) {
            if (member instanceof AstNode.DecisionTableDef decision) {
                if (!primitiveIds.add(decision.id())) {
                    throw new WorldModelException(WorldModelException.Code.FRAGMENT_INVALID);
                }
            } else if (member instanceof AstNode.TransformDef transform) {
                if (!primitiveIds.add(transform.id())) {
                    throw new WorldModelException(WorldModelException.Code.FRAGMENT_INVALID);
                }
            }
        }

        int count = 0;
        int maxDepth = 0;
        for (AstNode member : graph.members()) {
            if (member instanceof AstNode.CommentNode || member instanceof AstNode.SchemaDef) {
                continue;
            }
            if (member instanceof AstNode.DecisionTableDef table) {
                validateDecisionTable(table);
                for (AstNode.DecisionParam param : table.params()) {
                    maxDepth = Math.max(maxDepth, inspectExpression(param.binding(), primitiveIds, 1));
                }
                for (AstNode.DecisionRule rule : table.rules()) {
                    for (AstNode.DecisionCondition condition : rule.conditions()) {
                        maxDepth = Math.max(maxDepth,
                                inspectExpression(condition.predicate(), primitiveIds, 1));
                    }
                    if (rule.output() != null) {
                        maxDepth = Math.max(maxDepth,
                                inspectExpression(rule.output(), primitiveIds, 1));
                    }
                    for (Expression output : rule.namedOutputs().values()) {
                        maxDepth = Math.max(maxDepth, inspectExpression(output, primitiveIds, 1));
                    }
                }
                count++;
                continue;
            }
            if (member instanceof AstNode.TransformDef transform) {
                for (AstNode.LetBinding binding : transform.letBindings()) {
                    maxDepth = Math.max(maxDepth,
                            inspectExpression(binding.value(), primitiveIds, 1));
                }
                for (AstNode.TransformField field : transform.fields()) {
                    maxDepth = Math.max(maxDepth,
                            inspectExpression(field.value(), primitiveIds, 1));
                }
                count++;
                continue;
            }
            if (member instanceof AstNode.BranchDef branch) {
                maxDepth = Math.max(maxDepth,
                        inspectExpression(branch.condition(), primitiveIds, 1));
                for (AstNode.BranchCase branchCase : branch.cases()) {
                    maxDepth = Math.max(maxDepth,
                            inspectExpression(branchCase.value(), primitiveIds, 1));
                }
                count++;
                continue;
            }
            rejectMember(member);
        }
        if (count == 0) {
            throw new WorldModelException(WorldModelException.Code.FRAGMENT_NOT_PURE);
        }
        return new Inspection(count, maxDepth);
    }

    private static void validateDecisionTable(AstNode.DecisionTableDef table) {
        if (table.rules().isEmpty()
                || (table.hitPolicy() != AstNode.HitPolicyKind.FIRST
                && table.hitPolicy() != AstNode.HitPolicyKind.UNIQUE)) {
            throw new WorldModelException(WorldModelException.Code.FRAGMENT_NOT_PURE);
        }
        int fallbackCount = 0;
        for (int index = 0; index < table.rules().size(); index++) {
            if (table.rules().get(index).isOtherwise()) {
                fallbackCount++;
                if (index != table.rules().size() - 1) {
                    throw new WorldModelException(WorldModelException.Code.FRAGMENT_INVALID);
                }
            }
        }
        if (fallbackCount != 1) {
            throw new WorldModelException(WorldModelException.Code.FRAGMENT_INVALID);
        }
    }

    private static void rejectMember(AstNode member) {
        if (member instanceof AstNode.ImportDef) {
            throw new WorldModelException(WorldModelException.Code.FRAGMENT_WORLD_DELEGATION_FORBIDDEN);
        }
        if (member instanceof AstNode.NodeDef node) {
            String ref = normalizeOperatorRef(node.operatorRef());
            if (ref.equals("http") || ref.equals("network") || ref.equals("httpresource")) {
                throw new WorldModelException(WorldModelException.Code.FRAGMENT_NETWORK_FORBIDDEN);
            }
            if (ref.equals("resource")) {
                throw new WorldModelException(WorldModelException.Code.FRAGMENT_RESOURCE_FORBIDDEN);
            }
            if (ref.equals("file") || ref.equals("filesystem")
                    || ref.equals("readfile") || ref.equals("writefile")) {
                throw new WorldModelException(WorldModelException.Code.FRAGMENT_FILESYSTEM_FORBIDDEN);
            }
            if (ref.equals("world") || ref.equals("subgraph")) {
                throw new WorldModelException(WorldModelException.Code.FRAGMENT_WORLD_DELEGATION_FORBIDDEN);
            }
            throw new WorldModelException(WorldModelException.Code.FRAGMENT_UNRESOLVED_CAPABILITY);
        }
        if (member instanceof AstNode.ExtensionDef || member instanceof AstNode.ScriptDef) {
            throw new WorldModelException(WorldModelException.Code.FRAGMENT_UNRESOLVED_CAPABILITY);
        }
        throw new WorldModelException(WorldModelException.Code.FRAGMENT_NOT_PURE);
    }

    private static String normalizeOperatorRef(String operatorRef) {
        if (operatorRef == null) {
            return "";
        }
        String normalized = operatorRef.strip();
        if (normalized.length() >= 2) {
            char first = normalized.charAt(0);
            char last = normalized.charAt(normalized.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                normalized = normalized.substring(1, normalized.length() - 1).strip();
            }
        }
        return normalized.toLowerCase(Locale.ROOT);
    }

    private static int inspectExpression(Expression expression, Set<String> primitiveIds, int depth) {
        if (expression == null) {
            throw new WorldModelException(WorldModelException.Code.FRAGMENT_NOT_PURE);
        }
        if (expression instanceof Expression.ContextPath
                || expression instanceof Expression.StringLiteral
                || expression instanceof Expression.NumberLiteral
                || expression instanceof Expression.BooleanLiteral
                || expression instanceof Expression.DurationLiteral) {
            return depth;
        }
        if (expression instanceof Expression.NodeOutputPath path) {
            if (!primitiveIds.contains(path.nodeId())) {
                throw new WorldModelException(WorldModelException.Code.FRAGMENT_UNRESOLVED_CAPABILITY);
            }
            return depth;
        }
        if (expression instanceof Expression.FunctionCall call) {
            String name = call.name() == null ? "" : call.name().toLowerCase(Locale.ROOT);
            throw new WorldModelException(NON_DETERMINISTIC_FUNCTIONS.contains(name)
                    ? WorldModelException.Code.FRAGMENT_NONDETERMINISTIC
                    : WorldModelException.Code.FRAGMENT_UNRESOLVED_CAPABILITY);
        }
        if (expression instanceof Expression.MethodCallExpr) {
            throw new WorldModelException(WorldModelException.Code.FRAGMENT_UNRESOLVED_CAPABILITY);
        }
        if (expression instanceof Expression.NodeStreamPath
                || expression instanceof Expression.LoopPrevPath
                || expression instanceof Expression.LoopCarryPath
                || expression instanceof Expression.LoopIterationRef
                || expression instanceof Expression.ItemPath
                || expression instanceof Expression.ItemIndex
                || expression instanceof Expression.LambdaExpr) {
            throw new WorldModelException(WorldModelException.Code.FRAGMENT_NOT_PURE);
        }
        if (expression instanceof Expression.TransformFieldPath) {
            return depth;
        }
        int nestedDepth = depth + 1;
        if (expression instanceof Expression.ObjectLiteral object) {
            return max(nestedDepth, object.fields().values(), primitiveIds);
        }
        if (expression instanceof Expression.ArrayLiteral array) {
            return max(nestedDepth, array.elements(), primitiveIds);
        }
        if (expression instanceof Expression.BinaryOp binary) {
            return max(nestedDepth, List.of(binary.left(), binary.right()), primitiveIds);
        }
        if (expression instanceof Expression.ChainedComparisonExpr chained) {
            return max(nestedDepth,
                    List.of(chained.lower(), chained.value(), chained.upper()), primitiveIds);
        }
        if (expression instanceof Expression.InExpr in) {
            return max(nestedDepth, List.of(in.left(), in.right()), primitiveIds);
        }
        if (expression instanceof Expression.UnaryOp unary) {
            return inspectExpression(unary.operand(), primitiveIds, nestedDepth);
        }
        if (expression instanceof Expression.ConditionalExpr conditional) {
            return max(nestedDepth, List.of(conditional.condition(), conditional.thenBranch(),
                    conditional.elseBranch()), primitiveIds);
        }
        if (expression instanceof Expression.NullCoalesce coalesce) {
            return max(nestedDepth, List.of(coalesce.primary(), coalesce.fallback()), primitiveIds);
        }
        if (expression instanceof Expression.GroupExpr group) {
            return inspectExpression(group.inner(), primitiveIds, nestedDepth);
        }
        if (expression instanceof Expression.IndexExpr index) {
            return max(nestedDepth, List.of(index.receiver(), index.index()), primitiveIds);
        }
        if (expression instanceof Expression.MemberAccessExpr member) {
            return inspectExpression(member.receiver(), primitiveIds, nestedDepth);
        }
        if (expression instanceof Expression.StringInterpolation interpolation) {
            int max = nestedDepth;
            for (Expression.InterpolationPart part : interpolation.parts()) {
                if (part instanceof Expression.InterpolationPart.ExpressionPart embedded) {
                    max = Math.max(max,
                            inspectExpression(embedded.expression(), primitiveIds, nestedDepth));
                }
            }
            return max;
        }
        if (expression instanceof Expression.WhenExpr when) {
            int max = inspectExpression(when.subject(), primitiveIds, nestedDepth);
            for (Expression.WhenClause clause : when.clauses()) {
                max = Math.max(max, inspectExpression(clause.condition(), primitiveIds, nestedDepth));
                max = Math.max(max, inspectExpression(clause.result(), primitiveIds, nestedDepth));
            }
            return Math.max(max, inspectExpression(when.otherwise(), primitiveIds, nestedDepth));
        }
        throw new WorldModelException(WorldModelException.Code.FRAGMENT_NOT_PURE);
    }

    private static int max(int base, Iterable<Expression> expressions, Set<String> primitiveIds) {
        int max = base;
        for (Expression expression : expressions) {
            max = Math.max(max, inspectExpression(expression, primitiveIds, base));
        }
        return max;
    }

    private static String resolveOutputNode(BlogeFragmentRef fragment, Graph graph) {
        String requested = fragment.outputNodeId();
        if (!requested.isBlank()) {
            if (!graph.nodes().containsKey(requested) || !graph.terminalNodes().contains(requested)) {
                throw new WorldModelException(WorldModelException.Code.FRAGMENT_INVALID);
            }
            return requested;
        }
        if (graph.terminalNodes().size() != 1) {
            throw new WorldModelException(WorldModelException.Code.FRAGMENT_INVALID);
        }
        return graph.terminalNodes().iterator().next();
    }

    private static void proveCompiledInventory(Graph graph, InvocationInventory inventory) {
        if (inventory.entries().size() != graph.nodes().size()) {
            throw new WorldModelException(WorldModelException.Code.FRAGMENT_UNRESOLVED_CAPABILITY);
        }
        for (InvocationInventory.Entry entry : inventory.entries()) {
            if (!PURE_OPERATOR_REFS.contains(entry.node().operatorRef())) {
                throw new WorldModelException(WorldModelException.Code.FRAGMENT_UNRESOLVED_CAPABILITY);
            }
            Object operator = entry.frozenOperator();
            if (!(operator instanceof DecisionTableOperator) && !(operator instanceof TransformOperator)) {
                throw new WorldModelException(WorldModelException.Code.FRAGMENT_UNRESOLVED_CAPABILITY);
            }
        }
    }
}

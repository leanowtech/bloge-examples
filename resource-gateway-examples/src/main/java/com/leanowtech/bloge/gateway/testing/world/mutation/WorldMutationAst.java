package com.leanowtech.bloge.gateway.testing.world.mutation;

import com.leanowtech.bloge.dsl.ast.AstNode;
import com.leanowtech.bloge.dsl.ast.AstNode.DecisionCondition;
import com.leanowtech.bloge.dsl.ast.AstNode.DecisionRule;
import com.leanowtech.bloge.dsl.ast.AstNode.DecisionTableDef;
import com.leanowtech.bloge.dsl.ast.AstNode.GraphDef;
import com.leanowtech.bloge.dsl.ast.AstNode.TransformDef;
import com.leanowtech.bloge.dsl.ast.AstNode.TransformField;
import com.leanowtech.bloge.dsl.ast.Expression;
import com.leanowtech.bloge.dsl.codegen.DslCodeGenerator;
import com.leanowtech.bloge.dsl.compiler.DslCompiler;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/** AST-only world mutation support.  No source-text search or replacement is performed here. */
final class WorldMutationAst {
    private WorldMutationAst() {
    }

    record Site(WorldMutationPlan.MutationKind kind, String path, int line, int column) {
    }

    static GraphDef parse(DslCompiler compiler, String source) {
        return compiler.parse(Objects.requireNonNull(source, "source"));
    }

    static String generate(GraphDef graph) {
        return DslCodeGenerator.generate(Objects.requireNonNull(graph, "graph"));
    }

    static List<Site> sites(GraphDef graph, WorldMutationPlan.MutationKind kind) {
        List<Site> result = new ArrayList<>();
        List<AstNode> members = graph.members();
        for (int memberIndex = 0; memberIndex < members.size(); memberIndex++) {
            AstNode member = members.get(memberIndex);
            String memberPath = "/members/" + memberIndex;
            if (member instanceof DecisionTableDef table) {
                collectTableSites(result, table, memberPath, kind);
            } else if (member instanceof TransformDef transform) {
                collectTransformSites(result, transform, memberPath, kind);
            } else if (member instanceof AstNode.BranchDef branch) {
                collectBranchSites(result, branch, memberPath, kind);
            }
        }
        return List.copyOf(result);
    }

    private static void collectTableSites(List<Site> sites, DecisionTableDef table,
                                          String memberPath, WorldMutationPlan.MutationKind kind) {
        List<DecisionRule> rules = table.rules();
        switch (kind) {
            case RULE_DELETED -> {
                for (int ruleIndex = 0; ruleIndex < rules.size(); ruleIndex++) {
                    DecisionRule rule = rules.get(ruleIndex);
                    if (!rule.isOtherwise()) {
                        sites.add(site(kind, memberPath + "/rules/" + ruleIndex,
                                rule.line(), rule.column()));
                    }
                }
            }
            case DECISION_CONDITION_REVERSED -> {
                for (int ruleIndex = 0; ruleIndex < rules.size(); ruleIndex++) {
                    DecisionRule rule = rules.get(ruleIndex);
                    for (int conditionIndex = 0; conditionIndex < rule.conditions().size(); conditionIndex++) {
                        DecisionCondition condition = rule.conditions().get(conditionIndex);
                        sites.add(site(kind, memberPath + "/rules/" + ruleIndex
                                        + "/conditions/" + conditionIndex + "/predicate",
                                condition.line(), condition.column()));
                    }
                }
            }
            case BOUNDARY_VALUE_REPLACED -> {
                for (int ruleIndex = 0; ruleIndex < rules.size(); ruleIndex++) {
                    DecisionRule rule = rules.get(ruleIndex);
                    for (int conditionIndex = 0; conditionIndex < rule.conditions().size(); conditionIndex++) {
                        Expression predicate = rule.conditions().get(conditionIndex).predicate();
                        collectBoundarySites(sites, predicate, memberPath + "/rules/" + ruleIndex
                                + "/conditions/" + conditionIndex + "/predicate", kind);
                    }
                }
            }
            case RESULT_CHANGED -> {
                for (int ruleIndex = 0; ruleIndex + 1 < rules.size(); ruleIndex++) {
                    DecisionRule left = rules.get(ruleIndex);
                    DecisionRule right = rules.get(ruleIndex + 1);
                    if (sameOutputShape(left, right)) {
                        sites.add(site(kind, memberPath + "/rules/" + ruleIndex + "/output",
                                left.line(), left.column()));
                    }
                }
            }
            case STATE_WRITE_DROPPED -> {
                for (int ruleIndex = 0; ruleIndex < rules.size(); ruleIndex++) {
                    DecisionRule rule = rules.get(ruleIndex);
                    if (rule.output() != null) {
                        collectStateWriteSites(sites, rule.output(), memberPath + "/rules/"
                                + ruleIndex + "/output", kind);
                    }
                    List<String> names = new ArrayList<>(rule.namedOutputs().keySet());
                    for (int outputIndex = 0; outputIndex < names.size(); outputIndex++) {
                        collectStateWriteSites(sites, rule.namedOutputs().get(names.get(outputIndex)),
                                memberPath + "/rules/" + ruleIndex + "/namedOutputs/" + outputIndex,
                                kind);
                    }
                }
            }
            case DEFAULT_RULE_PRIORITY_CHANGED -> {
                if (table.hitPolicy() == AstNode.HitPolicyKind.FIRST) {
                    int defaultIndex = rules.size() - 1;
                    if (defaultIndex > 0 && rules.get(defaultIndex).isOtherwise()) {
                        int ordinaryIndex = defaultIndex - 1;
                        sites.add(site(kind, memberPath + "/rules/" + ordinaryIndex + "/defaultPriority",
                                rules.get(ordinaryIndex).line(), rules.get(ordinaryIndex).column()));
                    }
                }
            }
        }
    }

    private static void collectTransformSites(List<Site> sites, TransformDef transform,
                                              String memberPath, WorldMutationPlan.MutationKind kind) {
        if (kind == WorldMutationPlan.MutationKind.RESULT_CHANGED) {
            for (int index = 0; index + 1 < transform.fields().size(); index++) {
                TransformField field = transform.fields().get(index);
                sites.add(site(kind, memberPath + "/fields/" + index + "/value",
                        field.line(), field.column()));
            }
        }
        if (kind != WorldMutationPlan.MutationKind.STATE_WRITE_DROPPED) {
            return;
        }
        for (int index = 0; index < transform.letBindings().size(); index++) {
            var binding = transform.letBindings().get(index);
            collectStateWriteSites(sites, binding.value(), memberPath + "/letBindings/" + index + "/value", kind);
        }
        for (int index = 0; index < transform.fields().size(); index++) {
            TransformField field = transform.fields().get(index);
            String fieldPath = memberPath + "/fields/" + index + "/value";
            if ("stateWrites".equals(field.name()) && field.value() instanceof Expression.ObjectLiteral writes) {
                for (int writeIndex = 0; writeIndex < writes.fields().size(); writeIndex++) {
                    sites.add(site(kind, fieldPath + "/fields/" + writeIndex,
                            writes.line(), writes.column()));
                }
            } else {
                collectStateWriteSites(sites, field.value(), fieldPath, kind);
            }
        }
    }

    private static void collectBranchSites(List<Site> sites, AstNode.BranchDef branch,
                                           String memberPath, WorldMutationPlan.MutationKind kind) {
        if (kind == WorldMutationPlan.MutationKind.BOUNDARY_VALUE_REPLACED) {
            collectBoundarySites(sites, branch.condition(), memberPath + "/condition", kind);
            for (int index = 0; index < branch.cases().size(); index++) {
                collectBoundarySites(sites, branch.cases().get(index).value(),
                        memberPath + "/cases/" + index + "/value", kind);
            }
        }
        if (kind == WorldMutationPlan.MutationKind.RESULT_CHANGED) {
            for (int index = 0; index + 1 < branch.cases().size(); index++) {
                AstNode.BranchCase branchCase = branch.cases().get(index);
                sites.add(site(kind, memberPath + "/cases/" + index + "/target",
                        branch.line(), branch.column()));
            }
        }
    }

    private static boolean sameOutputShape(DecisionRule left, DecisionRule right) {
        if ((left.output() == null) != (right.output() == null)) {
            return false;
        }
        return left.output() != null
                || left.namedOutputs().keySet().equals(right.namedOutputs().keySet());
    }

    private static void collectBoundarySites(List<Site> sites, Expression expression,
                                             String path, WorldMutationPlan.MutationKind kind) {
        if (expression instanceof Expression.NumberLiteral number) {
            sites.add(site(kind, path, number.line(), number.column()));
        } else {
            expressionPaths(expression, path, true, sites, kind);
        }
    }

    private static void collectStateWriteSites(List<Site> sites, Expression expression,
                                               String path, WorldMutationPlan.MutationKind kind) {
        visitExpression(expression, path, (current, currentPath) -> {
            if (current instanceof Expression.ObjectLiteral object) {
                List<String> names = new ArrayList<>(object.fields().keySet());
                int stateIndex = names.indexOf("stateWrites");
                if (stateIndex >= 0 && object.fields().get("stateWrites")
                        instanceof Expression.ObjectLiteral writes && !writes.fields().isEmpty()) {
                    for (int writeIndex = 0; writeIndex < writes.fields().size(); writeIndex++) {
                        sites.add(site(kind, currentPath + "/fields/" + stateIndex
                                        + "/fields/" + writeIndex,
                                writes.line(), writes.column()));
                    }
                }
            }
        });
    }

    private static Site site(WorldMutationPlan.MutationKind kind, String path, int line, int column) {
        return new Site(kind, path, Math.max(1, line), Math.max(1, column));
    }

    /** Applies exactly one AST coordinate and returns a fresh graph tree. */
    static GraphDef mutate(GraphDef graph, WorldMutationPlan.MutationKind kind, String path) {
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(path, "path");
        String[] parts = path.split("/");
        if (parts.length < 3 || !"members".equals(parts[1])) {
            throw new IllegalArgumentException("Mutation path is not a graph AST coordinate");
        }
        int memberIndex = integer(parts[2]);
        if (memberIndex < 0 || memberIndex >= graph.members().size()) {
            throw new IllegalArgumentException("Mutation member coordinate is out of range");
        }
        List<AstNode> members = new ArrayList<>(graph.members());
        AstNode member = members.get(memberIndex);
        AstNode mutated = switch (kind) {
            case RULE_DELETED -> deleteRule(member, parts);
            case DEFAULT_RULE_PRIORITY_CHANGED -> swapPriority(member, parts);
            case DECISION_CONDITION_REVERSED -> reverseCondition(member, parts, path);
            case BOUNDARY_VALUE_REPLACED -> replaceBoundary(member, parts, path);
            case RESULT_CHANGED -> changeResult(member, parts);
            case STATE_WRITE_DROPPED -> dropStateWrite(member, parts, path);
        };
        members.set(memberIndex, mutated);
        return new GraphDef(graph.name(), members, graph.inputSchema(), graph.outputSchema(),
                graph.streamingOutputNodeId(), graph.streamingInputs(), graph.description(),
                graph.line(), graph.column());
    }

    private static AstNode deleteRule(AstNode member, String[] parts) {
        DecisionTableDef table = requireTable(member);
        int ruleIndex = at(parts, 3, "rules");
        List<DecisionRule> rules = new ArrayList<>(table.rules());
        DecisionRule removed = rules.remove(ruleIndex);
        if (removed.isOtherwise()) {
            throw new IllegalArgumentException("The default rule cannot be deleted");
        }
        return new DecisionTableDef(table.id(), table.params(), table.hitPolicy(),
                table.outputTypeAnnotation(), rules, table.description(), table.line(), table.column());
    }

    private static AstNode swapPriority(AstNode member, String[] parts) {
        DecisionTableDef table = requireTable(member);
        int ruleIndex = at(parts, 3, "rules");
        List<DecisionRule> rules = new ArrayList<>(table.rules());
        int defaultIndex = rules.size() - 1;
        if (ruleIndex != defaultIndex - 1 || defaultIndex < 1
                || !rules.get(defaultIndex).isOtherwise()
                || rules.get(ruleIndex).isOtherwise()
                || table.hitPolicy() != AstNode.HitPolicyKind.FIRST) {
            throw new IllegalArgumentException("No legal rule-priority mutation site");
        }
        DecisionRule first = rules.get(ruleIndex);
        rules.set(ruleIndex, rules.get(defaultIndex));
        rules.set(defaultIndex, first);
        return new DecisionTableDef(table.id(), table.params(), table.hitPolicy(),
                table.outputTypeAnnotation(), rules, table.description(), table.line(), table.column());
    }

    private static AstNode reverseCondition(AstNode member, String[] parts, String path) {
        DecisionTableDef table = requireTable(member);
        int ruleIndex = at(parts, 3, "rules");
        int conditionIndex = at(parts, 5, "conditions");
        DecisionRule rule = table.rules().get(ruleIndex);
        List<DecisionCondition> conditions = new ArrayList<>(rule.conditions());
        DecisionCondition condition = conditions.get(conditionIndex);
        Expression predicate = new Expression.UnaryOp(Expression.UnaryOperator.NOT,
                condition.predicate(), condition.predicate().line(), condition.predicate().column());
        conditions.set(conditionIndex, new DecisionCondition(condition.paramName(), predicate,
                condition.line(), condition.column()));
        List<DecisionRule> rules = new ArrayList<>(table.rules());
        rules.set(ruleIndex, ruleWithConditions(rule, conditions));
        return new DecisionTableDef(table.id(), table.params(), table.hitPolicy(),
                table.outputTypeAnnotation(), rules, table.description(), table.line(), table.column());
    }

    private static AstNode replaceBoundary(AstNode member, String[] parts, String path) {
        if (member instanceof DecisionTableDef table) {
            int ruleIndex = at(parts, 3, "rules");
            int conditionIndex = at(parts, 5, "conditions");
            DecisionRule rule = table.rules().get(ruleIndex);
            DecisionCondition condition = rule.conditions().get(conditionIndex);
            Expression changed = rewriteExpression(condition.predicate(),
                    prefix(path, 8), path, WorldMutationAst::nextBoundary);
            List<DecisionCondition> conditions = new ArrayList<>(rule.conditions());
            conditions.set(conditionIndex, new DecisionCondition(condition.paramName(), changed,
                    condition.line(), condition.column()));
            List<DecisionRule> rules = new ArrayList<>(table.rules());
            rules.set(ruleIndex, ruleWithConditions(rule, conditions));
            return new DecisionTableDef(table.id(), table.params(), table.hitPolicy(),
                    table.outputTypeAnnotation(), rules, table.description(), table.line(), table.column());
        }
        if (member instanceof AstNode.BranchDef branch) {
            Expression condition = branch.condition();
            if (parts.length > 3 && "condition".equals(parts[3])) {
                condition = rewriteExpression(condition, "/members/" + parts[2] + "/condition",
                        path, WorldMutationAst::nextBoundary);
            }
            List<AstNode.BranchCase> cases = new ArrayList<>(branch.cases());
            if (parts.length > 4 && "cases".equals(parts[3])) {
                int index = integer(parts[4]);
                var branchCase = cases.get(index);
                Expression value = rewriteExpression(branchCase.value(),
                        "/members/" + parts[2] + "/cases/" + index + "/value", path,
                        WorldMutationAst::nextBoundary);
                cases.set(index, new AstNode.BranchCase(value, branchCase.target(), branchCase.description()));
            }
            return new AstNode.BranchDef(condition, cases, branch.otherwise(), branch.inclusive(),
                    branch.description(), branch.line(), branch.column());
        }
        throw new IllegalArgumentException("Boundary mutation requires a decision or branch AST");
    }

    private static AstNode changeResult(AstNode member, String[] parts) {
        if (member instanceof DecisionTableDef table) {
            int ruleIndex = at(parts, 3, "rules");
            List<DecisionRule> rules = new ArrayList<>(table.rules());
            if (ruleIndex + 1 >= rules.size() || !sameOutputShape(rules.get(ruleIndex), rules.get(ruleIndex + 1))) {
                throw new IllegalArgumentException("No legal result mutation site");
            }
            DecisionRule left = rules.get(ruleIndex);
            DecisionRule right = rules.get(ruleIndex + 1);
            rules.set(ruleIndex, swappedOutput(left, right));
            rules.set(ruleIndex + 1, swappedOutput(right, left));
            return new DecisionTableDef(table.id(), table.params(), table.hitPolicy(),
                    table.outputTypeAnnotation(), rules, table.description(), table.line(), table.column());
        }
        if (member instanceof TransformDef transform) {
            int index = at(parts, 3, "fields");
            List<TransformField> fields = new ArrayList<>(transform.fields());
            if (index + 1 >= fields.size()) {
                throw new IllegalArgumentException("No legal transform result mutation site");
            }
            TransformField left = fields.get(index);
            TransformField right = fields.get(index + 1);
            fields.set(index, new TransformField(left.name(), left.typeAnnotation(), right.value(),
                    left.description(), left.line(), left.column()));
            fields.set(index + 1, new TransformField(right.name(), right.typeAnnotation(), left.value(),
                    right.description(), right.line(), right.column()));
            return new TransformDef(transform.id(), transform.letBindings(), fields,
                    transform.description(), transform.line(), transform.column());
        }
        if (member instanceof AstNode.BranchDef branch) {
            int index = at(parts, 3, "cases");
            List<AstNode.BranchCase> cases = new ArrayList<>(branch.cases());
            if (index + 1 >= cases.size()) {
                throw new IllegalArgumentException("No legal branch result mutation site");
            }
            var left = cases.get(index);
            var right = cases.get(index + 1);
            cases.set(index, new AstNode.BranchCase(left.value(), right.target(), left.description()));
            cases.set(index + 1, new AstNode.BranchCase(right.value(), left.target(), right.description()));
            return new AstNode.BranchDef(branch.condition(), cases, branch.otherwise(), branch.inclusive(),
                    branch.description(), branch.line(), branch.column());
        }
        throw new IllegalArgumentException("Result mutation requires a supported AST member");
    }

    private static AstNode dropStateWrite(AstNode member, String[] parts, String path) {
        int fieldIndex = integer(parts[parts.length - 1]);
        String objectPath = prefix(path, parts.length - 2);
        if (member instanceof DecisionTableDef table) {
            int ruleIndex = at(parts, 3, "rules");
            DecisionRule rule = table.rules().get(ruleIndex);
            List<DecisionRule> rules = new ArrayList<>(table.rules());
            Expression output = null;
            String rootPath = null;
            if (rule.output() != null && objectPath.startsWith("/members/" + parts[2]
                    + "/rules/" + ruleIndex + "/output")) {
                rootPath = "/members/" + parts[2] + "/rules/" + ruleIndex + "/output";
                output = rewriteExpression(rule.output(), rootPath, objectPath,
                        expression -> removeField(expression, fieldIndex));
                rules.set(ruleIndex, new DecisionRule(rule.description(), rule.conditions(), output,
                        Map.of(), rule.isOtherwise(), rule.line(), rule.column()));
            } else if (parts.length >= 7 && "namedOutputs".equals(parts[5])) {
                int outputIndex = integer(parts[6]);
                List<String> names = new ArrayList<>(rule.namedOutputs().keySet());
                if (outputIndex >= names.size()) throw new IllegalArgumentException("Named output coordinate is out of range");
                rootPath = "/members/" + parts[2] + "/rules/" + ruleIndex + "/namedOutputs/" + outputIndex;
                output = rewriteExpression(rule.namedOutputs().get(names.get(outputIndex)), rootPath,
                        objectPath, expression -> removeField(expression, fieldIndex));
                Map<String, Expression> named = new LinkedHashMap<>(rule.namedOutputs());
                named.put(names.get(outputIndex), output);
                rules.set(ruleIndex, new DecisionRule(rule.description(), rule.conditions(), null,
                        named, rule.isOtherwise(), rule.line(), rule.column()));
            } else {
                throw new IllegalArgumentException("State-write mutation target is not a scalar output");
            }
            return new DecisionTableDef(table.id(), table.params(), table.hitPolicy(),
                    table.outputTypeAnnotation(), rules, table.description(), table.line(), table.column());
        }
        if (member instanceof TransformDef transform) {
            int fieldIndexInTransform;
            boolean letBinding = parts.length > 3 && "letBindings".equals(parts[3]);
            if (letBinding) {
                fieldIndexInTransform = at(parts, 3, "letBindings");
            } else {
                fieldIndexInTransform = at(parts, 3, "fields");
            }
            String rootPath = "/members/" + parts[2] + "/"
                    + (letBinding ? "letBindings" : "fields") + "/" + fieldIndexInTransform + "/value";
            if (letBinding) {
                var binding = transform.letBindings().get(fieldIndexInTransform);
                Expression value = rewriteExpression(binding.value(), rootPath, objectPath,
                        expression -> removeField(expression, fieldIndex));
                List<AstNode.LetBinding> bindings = new ArrayList<>(transform.letBindings());
                bindings.set(fieldIndexInTransform, new AstNode.LetBinding(binding.name(), value,
                        binding.line(), binding.column()));
                return new TransformDef(transform.id(), bindings, transform.fields(),
                        transform.description(), transform.line(), transform.column());
            }
            TransformField field = transform.fields().get(fieldIndexInTransform);
            Expression value = rewriteExpression(field.value(), rootPath, objectPath,
                    expression -> removeField(expression, fieldIndex));
            List<TransformField> fields = new ArrayList<>(transform.fields());
            fields.set(fieldIndexInTransform, new TransformField(field.name(), field.typeAnnotation(), value,
                    field.description(), field.line(), field.column()));
            return new TransformDef(transform.id(), transform.letBindings(), fields,
                    transform.description(), transform.line(), transform.column());
        }
        throw new IllegalArgumentException("State-write mutation requires a transform or scalar decision output");
    }

    private static Expression removeField(Expression expression, int index) {
        if (!(expression instanceof Expression.ObjectLiteral object)) {
            throw new IllegalArgumentException("State-write target is not an object literal");
        }
        List<String> keys = new ArrayList<>(object.fields().keySet());
        if (index < 0 || index >= keys.size()) {
            throw new IllegalArgumentException("State-write field coordinate is out of range");
        }
        Map<String, Expression> fields = new LinkedHashMap<>(object.fields());
        fields.remove(keys.get(index));
        return new Expression.ObjectLiteral(fields, object.line(), object.column());
    }

    private static Expression nextBoundary(Expression expression) {
        if (!(expression instanceof Expression.NumberLiteral number)
                || !Double.isFinite(number.value())) {
            throw new IllegalArgumentException("Boundary mutation requires a finite numeric literal");
        }
        double value = number.value();
        double changed = value >= 0 && value <= Double.MAX_VALUE - 1 ? value + 1 : value - 1;
        if (!Double.isFinite(changed) || changed == value) {
            throw new IllegalArgumentException("Numeric boundary cannot be changed safely");
        }
        return new Expression.NumberLiteral(changed, number.line(), number.column());
    }

    private static Expression rewriteExpression(Expression expression, String basePath,
                                                String targetPath,
                                                Function<Expression, Expression> replacement) {
        if (basePath.equals(targetPath)) {
            return replacement.apply(expression);
        }
        return mapExpression(expression, basePath, targetPath, replacement);
    }

    private static Expression mapExpression(Expression expression, String basePath, String targetPath,
                                            Function<Expression, Expression> replacement) {
        if (expression == null) return null;
        if (expression instanceof Expression.ObjectLiteral object) {
            Map<String, Expression> values = new LinkedHashMap<>();
            List<String> keys = new ArrayList<>(object.fields().keySet());
            for (int i = 0; i < keys.size(); i++) {
                String key = keys.get(i);
                values.put(key, child(object.fields().get(key), basePath + "/fields/" + i,
                        targetPath, replacement));
            }
            return new Expression.ObjectLiteral(values, object.line(), object.column());
        }
        if (expression instanceof Expression.ArrayLiteral array) {
            List<Expression> values = new ArrayList<>();
            for (int i = 0; i < array.elements().size(); i++) {
                values.add(child(array.elements().get(i), basePath + "/elements/" + i,
                        targetPath, replacement));
            }
            return new Expression.ArrayLiteral(values, array.line(), array.column());
        }
        if (expression instanceof Expression.BinaryOp binary) {
            return new Expression.BinaryOp(child(binary.left(), basePath + "/left", targetPath, replacement),
                    binary.op(), child(binary.right(), basePath + "/right", targetPath, replacement),
                    binary.line(), binary.column());
        }
        if (expression instanceof Expression.ChainedComparisonExpr chained) {
            return new Expression.ChainedComparisonExpr(
                    child(chained.lower(), basePath + "/lower", targetPath, replacement), chained.lowerOperator(),
                    child(chained.value(), basePath + "/value", targetPath, replacement), chained.upperOperator(),
                    child(chained.upper(), basePath + "/upper", targetPath, replacement),
                    chained.line(), chained.column());
        }
        if (expression instanceof Expression.InExpr in) {
            return new Expression.InExpr(child(in.left(), basePath + "/left", targetPath, replacement),
                    child(in.right(), basePath + "/right", targetPath, replacement), in.line(), in.column());
        }
        if (expression instanceof Expression.UnaryOp unary) {
            return new Expression.UnaryOp(unary.op(), child(unary.operand(), basePath + "/operand",
                    targetPath, replacement), unary.line(), unary.column());
        }
        if (expression instanceof Expression.ConditionalExpr conditional) {
            return new Expression.ConditionalExpr(
                    child(conditional.condition(), basePath + "/condition", targetPath, replacement),
                    child(conditional.thenBranch(), basePath + "/then", targetPath, replacement),
                    child(conditional.elseBranch(), basePath + "/else", targetPath, replacement),
                    conditional.line(), conditional.column());
        }
        if (expression instanceof Expression.NullCoalesce coalesce) {
            return new Expression.NullCoalesce(child(coalesce.primary(), basePath + "/primary", targetPath, replacement),
                    child(coalesce.fallback(), basePath + "/fallback", targetPath, replacement),
                    coalesce.line(), coalesce.column());
        }
        if (expression instanceof Expression.FunctionCall call) {
            List<Expression> args = new ArrayList<>();
            for (int i = 0; i < call.args().size(); i++) {
                args.add(child(call.args().get(i), basePath + "/args/" + i, targetPath, replacement));
            }
            return new Expression.FunctionCall(call.name(), args, call.line(), call.column());
        }
        if (expression instanceof Expression.WhenExpr when) {
            List<Expression.WhenClause> clauses = new ArrayList<>();
            for (int i = 0; i < when.clauses().size(); i++) {
                var clause = when.clauses().get(i);
                clauses.add(new Expression.WhenClause(
                        child(clause.condition(), basePath + "/clauses/" + i + "/condition", targetPath, replacement),
                        child(clause.result(), basePath + "/clauses/" + i + "/result", targetPath, replacement)));
            }
            return new Expression.WhenExpr(child(when.subject(), basePath + "/subject", targetPath, replacement),
                    clauses, child(when.otherwise(), basePath + "/otherwise", targetPath, replacement),
                    when.line(), when.column());
        }
        if (expression instanceof Expression.GroupExpr group) {
            return new Expression.GroupExpr(child(group.inner(), basePath + "/inner", targetPath, replacement),
                    group.line(), group.column());
        }
        if (expression instanceof Expression.LambdaExpr lambda) {
            return new Expression.LambdaExpr(lambda.params(), child(lambda.body(), basePath + "/body",
                    targetPath, replacement), lambda.line(), lambda.column());
        }
        if (expression instanceof Expression.MethodCallExpr call) {
            List<Expression> args = new ArrayList<>();
            for (int i = 0; i < call.args().size(); i++) {
                args.add(child(call.args().get(i), basePath + "/args/" + i, targetPath, replacement));
            }
            return new Expression.MethodCallExpr(child(call.receiver(), basePath + "/receiver", targetPath, replacement),
                    call.method(), args, call.safeNavigation(), call.line(), call.column());
        }
        if (expression instanceof Expression.IndexExpr index) {
            return new Expression.IndexExpr(child(index.receiver(), basePath + "/receiver", targetPath, replacement),
                    child(index.index(), basePath + "/index", targetPath, replacement), index.safeNavigation(),
                    index.line(), index.column());
        }
        if (expression instanceof Expression.MemberAccessExpr access) {
            return new Expression.MemberAccessExpr(child(access.receiver(), basePath + "/receiver", targetPath, replacement),
                    access.name(), access.safeNavigation(), access.line(), access.column());
        }
        if (expression instanceof Expression.StringInterpolation interpolation) {
            List<Expression.InterpolationPart> parts = new ArrayList<>();
            for (int i = 0; i < interpolation.parts().size(); i++) {
                var part = interpolation.parts().get(i);
                if (part instanceof Expression.InterpolationPart.ExpressionPart embedded) {
                    parts.add(new Expression.InterpolationPart.ExpressionPart(child(embedded.expression(),
                            basePath + "/parts/" + i + "/expression", targetPath, replacement)));
                } else {
                    parts.add(part);
                }
            }
            return new Expression.StringInterpolation(parts, interpolation.line(), interpolation.column());
        }
        return expression;
    }

    private static Expression child(Expression child, String childPath, String targetPath,
                                    Function<Expression, Expression> replacement) {
        return childPath.equals(targetPath) ? replacement.apply(child)
                : mapExpression(child, childPath, targetPath, replacement);
    }

    private static void visitExpression(Expression expression, String path,
                                        java.util.function.BiConsumer<Expression, String> visitor) {
        if (expression == null) return;
        visitor.accept(expression, path);
        if (expression instanceof Expression.ObjectLiteral object) {
            List<String> keys = new ArrayList<>(object.fields().keySet());
            for (int i = 0; i < keys.size(); i++) visitExpression(object.fields().get(keys.get(i)), path + "/fields/" + i, visitor);
        } else if (expression instanceof Expression.ArrayLiteral array) {
            for (int i = 0; i < array.elements().size(); i++) visitExpression(array.elements().get(i), path + "/elements/" + i, visitor);
        } else if (expression instanceof Expression.BinaryOp binary) {
            visitExpression(binary.left(), path + "/left", visitor); visitExpression(binary.right(), path + "/right", visitor);
        } else if (expression instanceof Expression.ChainedComparisonExpr chained) {
            visitExpression(chained.lower(), path + "/lower", visitor); visitExpression(chained.value(), path + "/value", visitor); visitExpression(chained.upper(), path + "/upper", visitor);
        } else if (expression instanceof Expression.InExpr in) {
            visitExpression(in.left(), path + "/left", visitor); visitExpression(in.right(), path + "/right", visitor);
        } else if (expression instanceof Expression.UnaryOp unary) {
            visitExpression(unary.operand(), path + "/operand", visitor);
        } else if (expression instanceof Expression.ConditionalExpr conditional) {
            visitExpression(conditional.condition(), path + "/condition", visitor); visitExpression(conditional.thenBranch(), path + "/then", visitor); visitExpression(conditional.elseBranch(), path + "/else", visitor);
        } else if (expression instanceof Expression.NullCoalesce coalesce) {
            visitExpression(coalesce.primary(), path + "/primary", visitor); visitExpression(coalesce.fallback(), path + "/fallback", visitor);
        } else if (expression instanceof Expression.FunctionCall call) {
            for (int i = 0; i < call.args().size(); i++) visitExpression(call.args().get(i), path + "/args/" + i, visitor);
        } else if (expression instanceof Expression.WhenExpr when) {
            visitExpression(when.subject(), path + "/subject", visitor);
            for (int i = 0; i < when.clauses().size(); i++) { var clause = when.clauses().get(i); visitExpression(clause.condition(), path + "/clauses/" + i + "/condition", visitor); visitExpression(clause.result(), path + "/clauses/" + i + "/result", visitor); }
            visitExpression(when.otherwise(), path + "/otherwise", visitor);
        } else if (expression instanceof Expression.GroupExpr group) {
            visitExpression(group.inner(), path + "/inner", visitor);
        } else if (expression instanceof Expression.LambdaExpr lambda) {
            visitExpression(lambda.body(), path + "/body", visitor);
        } else if (expression instanceof Expression.MethodCallExpr call) {
            visitExpression(call.receiver(), path + "/receiver", visitor); for (int i = 0; i < call.args().size(); i++) visitExpression(call.args().get(i), path + "/args/" + i, visitor);
        } else if (expression instanceof Expression.IndexExpr index) {
            visitExpression(index.receiver(), path + "/receiver", visitor); visitExpression(index.index(), path + "/index", visitor);
        } else if (expression instanceof Expression.MemberAccessExpr access) {
            visitExpression(access.receiver(), path + "/receiver", visitor);
        } else if (expression instanceof Expression.StringInterpolation interpolation) {
            for (int i = 0; i < interpolation.parts().size(); i++) if (interpolation.parts().get(i) instanceof Expression.InterpolationPart.ExpressionPart embedded) visitExpression(embedded.expression(), path + "/parts/" + i + "/expression", visitor);
        }
    }

    private static void expressionPaths(Expression expression, String path,
                                        boolean boundary, List<Site> sites,
                                        WorldMutationPlan.MutationKind kind) {
        visitExpression(expression, path, (current, currentPath) -> {
            if (!boundary) return;
            if (current instanceof Expression.BinaryOp binary && isRelational(binary.op())) {
                if (binary.left() instanceof Expression.NumberLiteral left) sites.add(site(kind, currentPath + "/left", left.line(), left.column()));
                if (binary.right() instanceof Expression.NumberLiteral right) sites.add(site(kind, currentPath + "/right", right.line(), right.column()));
            }
            if (current instanceof Expression.ChainedComparisonExpr chained) {
                if (chained.lower() instanceof Expression.NumberLiteral value) sites.add(site(kind, currentPath + "/lower", value.line(), value.column()));
                if (chained.value() instanceof Expression.NumberLiteral value) sites.add(site(kind, currentPath + "/value", value.line(), value.column()));
                if (chained.upper() instanceof Expression.NumberLiteral value) sites.add(site(kind, currentPath + "/upper", value.line(), value.column()));
            }
        });
    }

    private static boolean isRelational(Expression.BinaryOperator operator) {
        return operator == Expression.BinaryOperator.GT || operator == Expression.BinaryOperator.LT
                || operator == Expression.BinaryOperator.GT_EQ || operator == Expression.BinaryOperator.LT_EQ;
    }

    private static DecisionRule ruleWithConditions(DecisionRule rule, List<DecisionCondition> conditions) {
        return new DecisionRule(rule.description(), conditions, rule.output(), rule.namedOutputs(),
                rule.isOtherwise(), rule.line(), rule.column());
    }

    private static DecisionRule swappedOutput(DecisionRule receiver, DecisionRule source) {
        if (receiver.output() != null) {
            return new DecisionRule(receiver.description(), receiver.conditions(), source.output(), Map.of(),
                    receiver.isOtherwise(), receiver.line(), receiver.column());
        }
        return new DecisionRule(receiver.description(), receiver.conditions(), null, source.namedOutputs(),
                receiver.isOtherwise(), receiver.line(), receiver.column());
    }

    private static DecisionTableDef requireTable(AstNode member) {
        if (!(member instanceof DecisionTableDef table)) {
            throw new IllegalArgumentException("Mutation coordinate requires a decision table");
        }
        return table;
    }

    private static int at(String[] parts, int index, String expected) {
        if (index >= parts.length || !expected.equals(parts[index])) {
            throw new IllegalArgumentException("Mutation path segment is invalid");
        }
        return integer(parts[index + 1]);
    }

    private static int integer(String value) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 0) throw new IllegalArgumentException("Negative AST coordinate");
            return parsed;
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("AST coordinate is not an integer");
        }
    }

    private static String prefix(String path, int segmentCount) {
        String[] parts = path.split("/");
        if (segmentCount < 1 || segmentCount > parts.length) {
            throw new IllegalArgumentException("AST path prefix is invalid");
        }
        StringBuilder result = new StringBuilder();
        for (int i = 1; i < segmentCount; i++) result.append('/').append(parts[i]);
        return result.toString();
    }
}

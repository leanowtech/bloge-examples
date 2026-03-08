package com.leanowtech.bloge.examples.ivr;

import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.context.NodeResults;
import com.leanowtech.bloge.core.engine.GraphEngine;
import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.model.NodeStatus;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.engine.operators.SubGraphOperator;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.dsl.compiler.GraphLoader;
import com.leanowtech.bloge.examples.common.LoggingListener;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Comprehensive IVR customer service (DSL + SubGraph composition),
 * combining telecom, banking, insurance, and e-commerce support in one menu tree.
 *
 * <p>IVR menu tree:
 * <pre>
 * mainMenu
 * ├─ 1 billing → billingDispute → duplicateCharge
 * ├─ 2 techSupport → networkIssues → cannotConnect
 * ├─ 3 accountInsurance → warrantyClaims → submitClaim
 * ├─ 4 ordersLogistics
 * ├─ 5 complaints
 * └─ 0 liveAgent
 * </pre>
 *
 * <p>Compared with the flat-graph variants, this class keeps each menu level modular
 * by wiring separately built sub-graphs into the main DSL graph.
 *
 * @see IvrCustomerServiceExample
 * @see IvrCustomerServiceFlatExample
 * @see IvrCustomerServiceFlatDslExample
 */
@SuppressWarnings({"unchecked", "preview"})
public class IvrCustomerServiceDslExample {

    // ── Why SubGraph? ──────────────────────────────────────────────
    // Traditional IVR systems define menu trees in XML/config (Avaya, Genesys, Cisco).
    // Graph engine replaces this with composable sub-graphs:
    //   1. Each menu level is a self-contained Graph — testable in isolation
    //   2. Sub-graphs can be reused across different IVR flows
    //   3. Built-in retry/timeout/fallback replaces IVR platform-specific error handling
    //   4. Observable via GraphListener — replaces proprietary IVR reporting
    //   5. DSL provides a readable alternative to IVR XML configuration

    // ── Common map operators ──────────────────────────────────────────────────

    static final Operator<Map<String, Object>, Map<String, Object>> GREETING = (input, ctx) -> {
        simulateLatency();
        return Map.of(
                "callId", input.getOrDefault("callId", ""),
                "text", input.getOrDefault("text", ""),
                "played", true);
    };

    static final Operator<Map<String, Object>, Map<String, Object>> IDENTIFY_CUSTOMER = (input, ctx) -> {
        simulateLatency();
        return Map.of(
                "id", input.getOrDefault("customerId", ""),
                "name", "Customer-" + input.getOrDefault("customerId", ""),
                "tier", "gold",
                "phone", input.getOrDefault("callerPhone", "+86-138-0000-0000"));
    };

    static final Operator<Map<String, Object>, Map<String, Object>> COLLECT_DTMF = (input, ctx) -> {
        simulateLatency();
        String menuId = String.valueOf(input.getOrDefault("menuId", "main-menu"));
        Map<String, String> simulated = simulatedKeys(ctx.graphContext());
        return Map.of(
                "key", simulated.getOrDefault(menuId, "0"),
                "menuId", menuId);
    };

    static final Operator<Map<String, Object>, Map<String, Object>> TRANSFER_AGENT = (input, ctx) -> {
        simulateLatency();
        return Map.of(
                "callId", input.getOrDefault("callId", ""),
                "queue", "live-agent",
                "agentId", "AGENT-001");
    };

    static final Operator<Map<String, Object>, Map<String, Object>> SATISFACTION_SURVEY = (input, ctx) -> {
        simulateLatency();
        String key = String.valueOf(input.getOrDefault("selectedKey", "0"));
        int score = ("5".equals(key) || "0".equals(key)) ? 3 : 5;
        return Map.of(
                "callId", input.getOrDefault("callId", ""),
                "score", score,
                "comment", "Survey completed for main key: " + key);
    };

    static final Operator<Map<String, Object>, Map<String, Object>> CALL_SUMMARY_BUILDER = (input, ctx) -> {
        simulateLatency();
        String selectedKey = String.valueOf(input.getOrDefault("selectedKey", "0"));
        int surveyScore = ((Number) input.getOrDefault("surveyScore", 0)).intValue();
        List<String> menuPath = buildPathFromContext(ctx.graphContext(), selectedKey);
        return Map.of(
                "callId", input.getOrDefault("callId", ""),
                "customerId", input.getOrDefault("customerId", ""),
                "menuPath", menuPath,
                "resolution", "key=" + selectedKey + ", survey=" + surveyScore);
    };

    static final Operator<Map<String, Object>, Map<String, Object>> SAVE_CALL_RECORD = (input, ctx) -> {
        simulateLatency();
        String callId = String.valueOf(input.getOrDefault("callId", ""));
        return Map.of(
                "callId", callId,
                "recordId", "IVR-" + callId,
                "persisted", true);
    };

    static final Operator<Map<String, Object>, Map<String, Object>> MENU_RESULT = (input, ctx) -> {
        simulateLatency();
        return new LinkedHashMap<>(input);
    };

    static final Operator<Map<String, Object>, Map<String, Object>> RETURN_PREVIOUS_MENU =
            actionOperator("return-menu", "Returning to previous menu");

    // ── Billing chain operators ───────────────────────────────────────────────

    static final Operator<Map<String, Object>, Map<String, Object>> VIEW_BILL =
            actionOperator("view-bill", "Latest bill amount: CNY 228.40");
    static final Operator<Map<String, Object>, Map<String, Object>> TOP_UP_PAYMENT =
            actionOperator("top-up-payment", "Top-up payment accepted and receipt sent");
    static final Operator<Map<String, Object>, Map<String, Object>> PLAN_PRICING =
            actionOperator("plan-pricing", "Current plan monthly fee: CNY 129.00");
    static final Operator<Map<String, Object>, Map<String, Object>> FEE_BREAKDOWN =
            actionOperator("fee-breakdown", "Fee details sent to SMS");
    static final Operator<Map<String, Object>, Map<String, Object>> PROCESS_UNAUTHORIZED_CHARGE =
            actionOperator("unauthorized-charge", "Unauthorized charge dispute submitted");
    static final Operator<Map<String, Object>, Map<String, Object>> PROCESS_WRONG_AMOUNT =
            actionOperator("wrong-amount", "Wrong amount dispute recorded");
    static final Operator<Map<String, Object>, Map<String, Object>> QUERY_REFUND_STATUS =
            actionOperator("refund-status", "Refund is being processed");
    static final Operator<Map<String, Object>, Map<String, Object>> PROCESS_OTHER_DISPUTE =
            actionOperator("other-dispute", "Other dispute ticket created");
    static final Operator<Map<String, Object>, Map<String, Object>> AUTO_REFUND =
            actionOperator("auto-refund", "Duplicate charge refunded automatically");
    static final Operator<Map<String, Object>, Map<String, Object>> MANUAL_REVIEW =
            actionOperator("manual-review", "Case sent to manual billing review");
    static final Operator<Map<String, Object>, Map<String, Object>> VIEW_CHARGE_HISTORY =
            actionOperator("charge-history", "Charge history displayed in app");
    static final Operator<Map<String, Object>, Map<String, Object>> DOWNLOAD_STATEMENT =
            actionOperator("download-statement", "Statement download link sent");
    static final Operator<Map<String, Object>, Map<String, Object>> TRANSFER_SUPERVISOR =
            actionOperator("transfer-supervisor", "Transferred to billing supervisor queue");

    // ── Tech support chain operators ──────────────────────────────────────────

    static final Operator<Map<String, Object>, Map<String, Object>> DEVICE_ISSUE =
            actionOperator("device-issue", "Device troubleshooting checklist sent");
    static final Operator<Map<String, Object>, Map<String, Object>> SOFTWARE_HELP =
            actionOperator("software-help", "App support article delivered");
    static final Operator<Map<String, Object>, Map<String, Object>> SCHEDULE_INSTALL =
            actionOperator("schedule-install", "Installation slot reserved");
    static final Operator<Map<String, Object>, Map<String, Object>> RUN_SPEED_TEST =
            actionOperator("run-speed-test", "Speed test started and report sent");
    static final Operator<Map<String, Object>, Map<String, Object>> INTERMITTENT_FIX =
            actionOperator("intermittent-fix", "Stability profile applied");
    static final Operator<Map<String, Object>, Map<String, Object>> SLOW_SPEED_FIX =
            actionOperator("slow-speed-fix", "Bandwidth optimization applied");
    static final Operator<Map<String, Object>, Map<String, Object>> DNS_FIX =
            actionOperator("dns-fix", "DNS reset completed");
    static final Operator<Map<String, Object>, Map<String, Object>> VPN_FIX =
            actionOperator("vpn-fix", "VPN profile refreshed");
    static final Operator<Map<String, Object>, Map<String, Object>> RESTART_DEVICE =
            actionOperator("restart-device", "Device restart guidance completed");
    static final Operator<Map<String, Object>, Map<String, Object>> CHECK_LINE =
            actionOperator("check-line", "Line quality check passed");
    static final Operator<Map<String, Object>, Map<String, Object>> REMOTE_DIAGNOSTIC =
            actionOperator("remote-diagnostic", "Remote diagnostic started");
    static final Operator<Map<String, Object>, Map<String, Object>> SCHEDULE_ONSITE =
            actionOperator("schedule-onsite", "Onsite technician appointment booked");
    static final Operator<Map<String, Object>, Map<String, Object>> DEVICE_REPLACEMENT =
            actionOperator("device-replacement", "Replacement request created");

    // ── Account / insurance chain operators ───────────────────────────────────

    static final Operator<Map<String, Object>, Map<String, Object>> CHANGE_PASSWORD =
            actionOperator("change-password", "Password reset link sent");
    static final Operator<Map<String, Object>, Map<String, Object>> CHANGE_PLAN =
            actionOperator("change-plan", "Plan change request submitted");
    static final Operator<Map<String, Object>, Map<String, Object>> ACTIVATE_SERVICE =
            actionOperator("activate-service", "Service activation completed");
    static final Operator<Map<String, Object>, Map<String, Object>> CLOSE_ACCOUNT =
            actionOperator("close-account", "Account closure workflow started");
    static final Operator<Map<String, Object>, Map<String, Object>> WARRANTY_INQUIRY =
            actionOperator("warranty-inquiry", "Warranty details displayed");
    static final Operator<Map<String, Object>, Map<String, Object>> CLAIM_STATUS =
            actionOperator("claim-status", "Claim status updated");
    static final Operator<Map<String, Object>, Map<String, Object>> EXTENDED_WARRANTY =
            actionOperator("extended-warranty", "Extended warranty plans sent");
    static final Operator<Map<String, Object>, Map<String, Object>> WARRANTY_POLICY =
            actionOperator("warranty-policy", "Warranty policy terms read out");
    static final Operator<Map<String, Object>, Map<String, Object>> CLAIM_DEVICE_DAMAGE =
            actionOperator("claim-device-damage", "Device damage claim submitted");
    static final Operator<Map<String, Object>, Map<String, Object>> CLAIM_ACCIDENTAL_LOSS =
            actionOperator("claim-accidental-loss", "Accidental loss claim submitted");
    static final Operator<Map<String, Object>, Map<String, Object>> CLAIM_BATTERY =
            actionOperator("claim-battery", "Battery claim submitted");
    static final Operator<Map<String, Object>, Map<String, Object>> CLAIM_SCREEN =
            actionOperator("claim-screen", "Screen repair claim submitted");
    static final Operator<Map<String, Object>, Map<String, Object>> CLAIM_OTHER =
            actionOperator("claim-other", "Other claim submitted");

    // ── Orders / complaints chain operators ───────────────────────────────────

    static final Operator<Map<String, Object>, Map<String, Object>> VIEW_ORDERS =
            actionOperator("view-orders", "Latest order details read out");
    static final Operator<Map<String, Object>, Map<String, Object>> RETURNS_EXCHANGES =
            actionOperator("returns-exchanges", "Return/exchange request opened");
    static final Operator<Map<String, Object>, Map<String, Object>> SHIPPING_STATUS =
            actionOperator("shipping-status", "Shipment status pushed to app");
    static final Operator<Map<String, Object>, Map<String, Object>> CONFIRM_RECEIPT =
            actionOperator("confirm-receipt", "Receipt confirmation completed");
    static final Operator<Map<String, Object>, Map<String, Object>> INVOICE_SERVICE =
            actionOperator("invoice-service", "Invoice service ticket created");

    static final Operator<Map<String, Object>, Map<String, Object>> SERVICE_QUALITY_COMPLAINT =
            actionOperator("service-quality-complaint", "Service quality complaint ticket created");
    static final Operator<Map<String, Object>, Map<String, Object>> BILLING_COMPLAINT =
            actionOperator("billing-complaint", "Billing complaint ticket created");
    static final Operator<Map<String, Object>, Map<String, Object>> NETWORK_COMPLAINT =
            actionOperator("network-complaint", "Network complaint ticket created");
    static final Operator<Map<String, Object>, Map<String, Object>> STAFF_COMPLAINT =
            actionOperator("staff-complaint", "Staff behavior complaint ticket created");
    static final Operator<Map<String, Object>, Map<String, Object>> OTHER_COMPLAINT =
            actionOperator("other-complaint", "Other complaint ticket created");

    // ── Sub-graph builders (Map I/O) ──────────────────────────────────────────

    public static Graph buildDuplicateChargeSubGraph() {
        return Graph.builder("duplicate-charge-menu")
                .node("duplicateChargePrompt", GREETING)
                    .input((results, ctx) -> promptInput(ctx, "重复扣费处理：1自动退款 2人工审核 3扣费记录 4下载账单 5转接主管"))
                .node("collectDuplicateChargeInput", COLLECT_DTMF)
                    .dependsOn("duplicateChargePrompt")
                    .input((results, ctx) -> menuInput(ctx, "duplicate-charge-menu"))
                .node("autoRefund", AUTO_REFUND)
                    .dependsOn("collectDuplicateChargeInput")
                    .input((results, ctx) -> menuInput(ctx, "duplicate-charge-menu"))
                .node("manualReview", MANUAL_REVIEW)
                    .dependsOn("collectDuplicateChargeInput")
                    .input((results, ctx) -> menuInput(ctx, "duplicate-charge-menu"))
                .node("viewChargeHistory", VIEW_CHARGE_HISTORY)
                    .dependsOn("collectDuplicateChargeInput")
                    .input((results, ctx) -> menuInput(ctx, "duplicate-charge-menu"))
                .node("downloadStatement", DOWNLOAD_STATEMENT)
                    .dependsOn("collectDuplicateChargeInput")
                    .input((results, ctx) -> menuInput(ctx, "duplicate-charge-menu"))
                .node("transferSupervisor", TRANSFER_SUPERVISOR)
                    .dependsOn("collectDuplicateChargeInput")
                    .input((results, ctx) -> menuInput(ctx, "duplicate-charge-menu"))
                .branch("collectDuplicateChargeInput")
                    .on("key")
                    .when(v -> "1".equals(v), "autoRefund")
                    .when(v -> "2".equals(v), "manualReview")
                    .when(v -> "3".equals(v), "viewChargeHistory")
                    .when(v -> "4".equals(v), "downloadStatement")
                    .otherwise("transferSupervisor")
                .node("duplicateChargeResult", MENU_RESULT)
                    .dependsOn("autoRefund", "manualReview", "viewChargeHistory", "downloadStatement", "transferSupervisor")
                    .input((results, ctx) -> {
                        String selected = firstCompleted(results,
                                "autoRefund", "manualReview", "viewChargeHistory", "downloadStatement", "transferSupervisor");
                        Map<String, Object> action = results.get(selected, Map.class);
                        return menuResult("duplicate-charge-menu",
                                List.of("duplicateCharge", selected),
                                stringValue(action, "action", selected),
                                stringValue(action, "detail", selected));
                    })
                .build();
    }

    public static Graph buildBillingDisputeSubGraph(SubGraphOperator duplicateChargeFlow) {
        return Graph.builder("billing-dispute-menu")
                .node("billingDisputePrompt", GREETING)
                    .input((results, ctx) -> promptInput(ctx, "账单争议：1重复扣费 2未授权扣费 3金额错误 4退款进度 5其他争议"))
                .node("collectBillingDisputeInput", COLLECT_DTMF)
                    .dependsOn("billingDisputePrompt")
                    .input((results, ctx) -> menuInput(ctx, "billing-dispute-menu"))
                .node("duplicateChargeFlow", duplicateChargeFlow)
                    .dependsOn("collectBillingDisputeInput")
                    .input((results, ctx) -> baseCallInput(ctx))
                .node("processUnauthorizedCharge", PROCESS_UNAUTHORIZED_CHARGE)
                    .dependsOn("collectBillingDisputeInput")
                    .input((results, ctx) -> menuInput(ctx, "billing-dispute-menu"))
                .node("processWrongAmount", PROCESS_WRONG_AMOUNT)
                    .dependsOn("collectBillingDisputeInput")
                    .input((results, ctx) -> menuInput(ctx, "billing-dispute-menu"))
                .node("queryRefundStatus", QUERY_REFUND_STATUS)
                    .dependsOn("collectBillingDisputeInput")
                    .input((results, ctx) -> menuInput(ctx, "billing-dispute-menu"))
                .node("processOtherDispute", PROCESS_OTHER_DISPUTE)
                    .dependsOn("collectBillingDisputeInput")
                    .input((results, ctx) -> menuInput(ctx, "billing-dispute-menu"))
                .branch("collectBillingDisputeInput")
                    .on("key")
                    .when(v -> "1".equals(v), "duplicateChargeFlow")
                    .when(v -> "2".equals(v), "processUnauthorizedCharge")
                    .when(v -> "3".equals(v), "processWrongAmount")
                    .when(v -> "4".equals(v), "queryRefundStatus")
                    .otherwise("processOtherDispute")
                .node("billingDisputeResult", MENU_RESULT)
                    .dependsOn("duplicateChargeFlow", "processUnauthorizedCharge",
                            "processWrongAmount", "queryRefundStatus", "processOtherDispute")
                    .input((results, ctx) -> {
                        if (results.hasResult("duplicateChargeFlow")) {
                            Map<String, Object> nested = unwrapSubGraphResult(
                                    results.getRaw("duplicateChargeFlow"), "duplicateChargeResult");
                            List<String> path = new ArrayList<>(List.of("billingDispute"));
                            path.addAll(pathOf(nested));
                            return menuResult("billing-dispute-menu", path,
                                    stringValue(nested, "action", "duplicate-charge"),
                                    stringValue(nested, "detail", "duplicate charge handled"));
                        }
                        String selected = firstCompleted(results,
                                "processUnauthorizedCharge", "processWrongAmount", "queryRefundStatus", "processOtherDispute");
                        Map<String, Object> action = results.get(selected, Map.class);
                        return menuResult("billing-dispute-menu",
                                List.of("billingDispute", selected),
                                stringValue(action, "action", selected),
                                stringValue(action, "detail", selected));
                    })
                .build();
    }

    public static Graph buildBillingSubGraph(SubGraphOperator billingDisputeFlow) {
        return Graph.builder("billing-menu")
                .node("billingPrompt", GREETING)
                    .input((results, ctx) -> promptInput(ctx, "话费账单：1查看账单 2账单争议 3充值缴费 4套餐费用 5费用明细 6返回"))
                .node("collectBillingInput", COLLECT_DTMF)
                    .dependsOn("billingPrompt")
                    .input((results, ctx) -> menuInput(ctx, "billing-menu"))
                .node("viewBill", VIEW_BILL)
                    .dependsOn("collectBillingInput")
                    .input((results, ctx) -> menuInput(ctx, "billing-menu"))
                .node("billingDisputeFlow", billingDisputeFlow)
                    .dependsOn("collectBillingInput")
                    .input((results, ctx) -> baseCallInput(ctx))
                .node("topUpPayment", TOP_UP_PAYMENT)
                    .dependsOn("collectBillingInput")
                    .input((results, ctx) -> menuInput(ctx, "billing-menu"))
                .node("planPricing", PLAN_PRICING)
                    .dependsOn("collectBillingInput")
                    .input((results, ctx) -> menuInput(ctx, "billing-menu"))
                .node("feeBreakdown", FEE_BREAKDOWN)
                    .dependsOn("collectBillingInput")
                    .input((results, ctx) -> menuInput(ctx, "billing-menu"))
                .node("billingReturn", RETURN_PREVIOUS_MENU)
                    .dependsOn("collectBillingInput")
                    .input((results, ctx) -> menuInput(ctx, "billing-menu"))
                .branch("collectBillingInput")
                    .on("key")
                    .when(v -> "1".equals(v), "viewBill")
                    .when(v -> "2".equals(v), "billingDisputeFlow")
                    .when(v -> "3".equals(v), "topUpPayment")
                    .when(v -> "4".equals(v), "planPricing")
                    .when(v -> "5".equals(v), "feeBreakdown")
                    .otherwise("billingReturn")
                .node("billingResult", MENU_RESULT)
                    .dependsOn("viewBill", "billingDisputeFlow", "topUpPayment", "planPricing", "feeBreakdown", "billingReturn")
                    .input((results, ctx) -> {
                        if (results.hasResult("billingDisputeFlow")) {
                            Map<String, Object> nested = unwrapSubGraphResult(
                                    results.getRaw("billingDisputeFlow"), "billingDisputeResult");
                            List<String> path = new ArrayList<>(List.of("billing"));
                            path.addAll(pathOf(nested));
                            return menuResult("billing-menu", path,
                                    stringValue(nested, "action", "billing-dispute"),
                                    stringValue(nested, "detail", "billing dispute handled"));
                        }
                        String selected = firstCompleted(results,
                                "viewBill", "topUpPayment", "planPricing", "feeBreakdown", "billingReturn");
                        Map<String, Object> action = results.get(selected, Map.class);
                        return menuResult("billing-menu",
                                List.of("billing", selected),
                                stringValue(action, "action", selected),
                                stringValue(action, "detail", selected));
                    })
                .build();
    }

    public static Graph buildCannotConnectSubGraph() {
        return Graph.builder("cannot-connect-menu")
                .node("cannotConnectPrompt", GREETING)
                    .input((results, ctx) -> promptInput(ctx, "无法连接诊断：1重启设备 2检查线路 3远程诊断 4预约上门 5更换设备"))
                .node("collectCannotConnectInput", COLLECT_DTMF)
                    .dependsOn("cannotConnectPrompt")
                    .input((results, ctx) -> menuInput(ctx, "cannot-connect-menu"))
                .node("restartDevice", RESTART_DEVICE)
                    .dependsOn("collectCannotConnectInput")
                    .input((results, ctx) -> menuInput(ctx, "cannot-connect-menu"))
                .node("checkLine", CHECK_LINE)
                    .dependsOn("collectCannotConnectInput")
                    .input((results, ctx) -> menuInput(ctx, "cannot-connect-menu"))
                .node("remoteDiagnostic", REMOTE_DIAGNOSTIC)
                    .dependsOn("collectCannotConnectInput")
                    .input((results, ctx) -> menuInput(ctx, "cannot-connect-menu"))
                .node("scheduleOnsite", SCHEDULE_ONSITE)
                    .dependsOn("collectCannotConnectInput")
                    .input((results, ctx) -> menuInput(ctx, "cannot-connect-menu"))
                .node("deviceReplacement", DEVICE_REPLACEMENT)
                    .dependsOn("collectCannotConnectInput")
                    .input((results, ctx) -> menuInput(ctx, "cannot-connect-menu"))
                .branch("collectCannotConnectInput")
                    .on("key")
                    .when(v -> "1".equals(v), "restartDevice")
                    .when(v -> "2".equals(v), "checkLine")
                    .when(v -> "3".equals(v), "remoteDiagnostic")
                    .when(v -> "4".equals(v), "scheduleOnsite")
                    .otherwise("deviceReplacement")
                .node("cannotConnectResult", MENU_RESULT)
                    .dependsOn("restartDevice", "checkLine", "remoteDiagnostic", "scheduleOnsite", "deviceReplacement")
                    .input((results, ctx) -> {
                        String selected = firstCompleted(results,
                                "restartDevice", "checkLine", "remoteDiagnostic", "scheduleOnsite", "deviceReplacement");
                        Map<String, Object> action = results.get(selected, Map.class);
                        return menuResult("cannot-connect-menu",
                                List.of("cannotConnect", selected),
                                stringValue(action, "action", selected),
                                stringValue(action, "detail", selected));
                    })
                .build();
    }

    public static Graph buildNetworkIssueSubGraph(SubGraphOperator cannotConnectFlow) {
        return Graph.builder("network-issue-menu")
                .node("networkIssuePrompt", GREETING)
                    .input((results, ctx) -> promptInput(ctx, "网络故障：1无法连接 2连接不稳 3网速慢 4DNS错误 5VPN问题"))
                .node("collectNetworkIssueInput", COLLECT_DTMF)
                    .dependsOn("networkIssuePrompt")
                    .input((results, ctx) -> menuInput(ctx, "network-issue-menu"))
                .node("cannotConnectFlow", cannotConnectFlow)
                    .dependsOn("collectNetworkIssueInput")
                    .input((results, ctx) -> baseCallInput(ctx))
                .node("intermittentFix", INTERMITTENT_FIX)
                    .dependsOn("collectNetworkIssueInput")
                    .input((results, ctx) -> menuInput(ctx, "network-issue-menu"))
                .node("slowSpeedFix", SLOW_SPEED_FIX)
                    .dependsOn("collectNetworkIssueInput")
                    .input((results, ctx) -> menuInput(ctx, "network-issue-menu"))
                .node("dnsFix", DNS_FIX)
                    .dependsOn("collectNetworkIssueInput")
                    .input((results, ctx) -> menuInput(ctx, "network-issue-menu"))
                .node("vpnFix", VPN_FIX)
                    .dependsOn("collectNetworkIssueInput")
                    .input((results, ctx) -> menuInput(ctx, "network-issue-menu"))
                .branch("collectNetworkIssueInput")
                    .on("key")
                    .when(v -> "1".equals(v), "cannotConnectFlow")
                    .when(v -> "2".equals(v), "intermittentFix")
                    .when(v -> "3".equals(v), "slowSpeedFix")
                    .when(v -> "4".equals(v), "dnsFix")
                    .otherwise("vpnFix")
                .node("networkIssueResult", MENU_RESULT)
                    .dependsOn("cannotConnectFlow", "intermittentFix", "slowSpeedFix", "dnsFix", "vpnFix")
                    .input((results, ctx) -> {
                        if (results.hasResult("cannotConnectFlow")) {
                            Map<String, Object> nested = unwrapSubGraphResult(
                                    results.getRaw("cannotConnectFlow"), "cannotConnectResult");
                            List<String> path = new ArrayList<>(List.of("networkIssues"));
                            path.addAll(pathOf(nested));
                            return menuResult("network-issue-menu", path,
                                    stringValue(nested, "action", "cannot-connect"),
                                    stringValue(nested, "detail", "network diagnostic complete"));
                        }
                        String selected = firstCompleted(results, "intermittentFix", "slowSpeedFix", "dnsFix", "vpnFix");
                        Map<String, Object> action = results.get(selected, Map.class);
                        return menuResult("network-issue-menu",
                                List.of("networkIssues", selected),
                                stringValue(action, "action", selected),
                                stringValue(action, "detail", selected));
                    })
                .build();
    }

    public static Graph buildTechSupportSubGraph(SubGraphOperator networkIssueFlow) {
        return Graph.builder("tech-support-menu")
                .node("techSupportPrompt", GREETING)
                    .input((results, ctx) -> promptInput(ctx, "技术支持：1网络故障 2设备问题 3应用故障 4安装服务 5网速测试 6返回"))
                .node("collectTechSupportInput", COLLECT_DTMF)
                    .dependsOn("techSupportPrompt")
                    .input((results, ctx) -> menuInput(ctx, "tech-support-menu"))
                .node("networkIssueFlow", networkIssueFlow)
                    .dependsOn("collectTechSupportInput")
                    .input((results, ctx) -> baseCallInput(ctx))
                .node("deviceIssue", DEVICE_ISSUE)
                    .dependsOn("collectTechSupportInput")
                    .input((results, ctx) -> menuInput(ctx, "tech-support-menu"))
                .node("softwareHelp", SOFTWARE_HELP)
                    .dependsOn("collectTechSupportInput")
                    .input((results, ctx) -> menuInput(ctx, "tech-support-menu"))
                .node("scheduleInstall", SCHEDULE_INSTALL)
                    .dependsOn("collectTechSupportInput")
                    .input((results, ctx) -> menuInput(ctx, "tech-support-menu"))
                .node("runSpeedTest", RUN_SPEED_TEST)
                    .dependsOn("collectTechSupportInput")
                    .input((results, ctx) -> menuInput(ctx, "tech-support-menu"))
                .node("techReturn", RETURN_PREVIOUS_MENU)
                    .dependsOn("collectTechSupportInput")
                    .input((results, ctx) -> menuInput(ctx, "tech-support-menu"))
                .branch("collectTechSupportInput")
                    .on("key")
                    .when(v -> "1".equals(v), "networkIssueFlow")
                    .when(v -> "2".equals(v), "deviceIssue")
                    .when(v -> "3".equals(v), "softwareHelp")
                    .when(v -> "4".equals(v), "scheduleInstall")
                    .when(v -> "5".equals(v), "runSpeedTest")
                    .otherwise("techReturn")
                .node("techSupportResult", MENU_RESULT)
                    .dependsOn("networkIssueFlow", "deviceIssue", "softwareHelp", "scheduleInstall", "runSpeedTest", "techReturn")
                    .input((results, ctx) -> {
                        if (results.hasResult("networkIssueFlow")) {
                            Map<String, Object> nested = unwrapSubGraphResult(
                                    results.getRaw("networkIssueFlow"), "networkIssueResult");
                            List<String> path = new ArrayList<>(List.of("techSupport"));
                            path.addAll(pathOf(nested));
                            return menuResult("tech-support-menu", path,
                                    stringValue(nested, "action", "network-issues"),
                                    stringValue(nested, "detail", "tech support completed"));
                        }
                        String selected = firstCompleted(results,
                                "deviceIssue", "softwareHelp", "scheduleInstall", "runSpeedTest", "techReturn");
                        Map<String, Object> action = results.get(selected, Map.class);
                        return menuResult("tech-support-menu",
                                List.of("techSupport", selected),
                                stringValue(action, "action", selected),
                                stringValue(action, "detail", selected));
                    })
                .build();
    }

    public static Graph buildSubmitClaimSubGraph() {
        return Graph.builder("submit-claim-menu")
                .node("submitClaimPrompt", GREETING)
                    .input((results, ctx) -> promptInput(ctx, "提交理赔：1设备损坏 2意外丢失 3电池故障 4屏幕维修 5其他故障"))
                .node("collectSubmitClaimInput", COLLECT_DTMF)
                    .dependsOn("submitClaimPrompt")
                    .input((results, ctx) -> menuInput(ctx, "submit-claim-menu"))
                .node("claimDeviceDamage", CLAIM_DEVICE_DAMAGE)
                    .dependsOn("collectSubmitClaimInput")
                    .input((results, ctx) -> menuInput(ctx, "submit-claim-menu"))
                .node("claimAccidentalLoss", CLAIM_ACCIDENTAL_LOSS)
                    .dependsOn("collectSubmitClaimInput")
                    .input((results, ctx) -> menuInput(ctx, "submit-claim-menu"))
                .node("claimBattery", CLAIM_BATTERY)
                    .dependsOn("collectSubmitClaimInput")
                    .input((results, ctx) -> menuInput(ctx, "submit-claim-menu"))
                .node("claimScreen", CLAIM_SCREEN)
                    .dependsOn("collectSubmitClaimInput")
                    .input((results, ctx) -> menuInput(ctx, "submit-claim-menu"))
                .node("claimOther", CLAIM_OTHER)
                    .dependsOn("collectSubmitClaimInput")
                    .input((results, ctx) -> menuInput(ctx, "submit-claim-menu"))
                .branch("collectSubmitClaimInput")
                    .on("key")
                    .when(v -> "1".equals(v), "claimDeviceDamage")
                    .when(v -> "2".equals(v), "claimAccidentalLoss")
                    .when(v -> "3".equals(v), "claimBattery")
                    .when(v -> "4".equals(v), "claimScreen")
                    .otherwise("claimOther")
                .node("submitClaimResult", MENU_RESULT)
                    .dependsOn("claimDeviceDamage", "claimAccidentalLoss", "claimBattery", "claimScreen", "claimOther")
                    .input((results, ctx) -> {
                        String selected = firstCompleted(results,
                                "claimDeviceDamage", "claimAccidentalLoss", "claimBattery", "claimScreen", "claimOther");
                        Map<String, Object> action = results.get(selected, Map.class);
                        return menuResult("submit-claim-menu",
                                List.of("submitClaim", selected),
                                stringValue(action, "action", selected),
                                stringValue(action, "detail", selected));
                    })
                .build();
    }

    public static Graph buildWarrantyClaimsSubGraph(SubGraphOperator submitClaimFlow) {
        return Graph.builder("warranty-claims-menu")
                .node("warrantyClaimsPrompt", GREETING)
                    .input((results, ctx) -> promptInput(ctx, "保修理赔：1保修查询 2提交理赔 3理赔进度 4延保服务 5保修政策"))
                .node("collectWarrantyClaimsInput", COLLECT_DTMF)
                    .dependsOn("warrantyClaimsPrompt")
                    .input((results, ctx) -> menuInput(ctx, "warranty-claims-menu"))
                .node("warrantyInquiry", WARRANTY_INQUIRY)
                    .dependsOn("collectWarrantyClaimsInput")
                    .input((results, ctx) -> menuInput(ctx, "warranty-claims-menu"))
                .node("submitClaimFlow", submitClaimFlow)
                    .dependsOn("collectWarrantyClaimsInput")
                    .input((results, ctx) -> baseCallInput(ctx))
                .node("claimStatus", CLAIM_STATUS)
                    .dependsOn("collectWarrantyClaimsInput")
                    .input((results, ctx) -> menuInput(ctx, "warranty-claims-menu"))
                .node("extendedWarranty", EXTENDED_WARRANTY)
                    .dependsOn("collectWarrantyClaimsInput")
                    .input((results, ctx) -> menuInput(ctx, "warranty-claims-menu"))
                .node("warrantyPolicy", WARRANTY_POLICY)
                    .dependsOn("collectWarrantyClaimsInput")
                    .input((results, ctx) -> menuInput(ctx, "warranty-claims-menu"))
                .branch("collectWarrantyClaimsInput")
                    .on("key")
                    .when(v -> "1".equals(v), "warrantyInquiry")
                    .when(v -> "2".equals(v), "submitClaimFlow")
                    .when(v -> "3".equals(v), "claimStatus")
                    .when(v -> "4".equals(v), "extendedWarranty")
                    .otherwise("warrantyPolicy")
                .node("warrantyClaimsResult", MENU_RESULT)
                    .dependsOn("warrantyInquiry", "submitClaimFlow", "claimStatus", "extendedWarranty", "warrantyPolicy")
                    .input((results, ctx) -> {
                        if (results.hasResult("submitClaimFlow")) {
                            Map<String, Object> nested = unwrapSubGraphResult(
                                    results.getRaw("submitClaimFlow"), "submitClaimResult");
                            List<String> path = new ArrayList<>(List.of("warrantyClaims"));
                            path.addAll(pathOf(nested));
                            return menuResult("warranty-claims-menu", path,
                                    stringValue(nested, "action", "submit-claim"),
                                    stringValue(nested, "detail", "claim submitted"));
                        }
                        String selected = firstCompleted(results,
                                "warrantyInquiry", "claimStatus", "extendedWarranty", "warrantyPolicy");
                        Map<String, Object> action = results.get(selected, Map.class);
                        return menuResult("warranty-claims-menu",
                                List.of("warrantyClaims", selected),
                                stringValue(action, "action", selected),
                                stringValue(action, "detail", selected));
                    })
                .build();
    }

    public static Graph buildAccountInsuranceSubGraph(SubGraphOperator warrantyClaimsFlow) {
        return Graph.builder("account-insurance-menu")
                .node("accountInsurancePrompt", GREETING)
                    .input((results, ctx) -> promptInput(ctx, "账户保障：1修改密码 2套餐变更 3保修理赔 4开通服务 5账户注销 6返回"))
                .node("collectAccountInsuranceInput", COLLECT_DTMF)
                    .dependsOn("accountInsurancePrompt")
                    .input((results, ctx) -> menuInput(ctx, "account-insurance-menu"))
                .node("changePassword", CHANGE_PASSWORD)
                    .dependsOn("collectAccountInsuranceInput")
                    .input((results, ctx) -> menuInput(ctx, "account-insurance-menu"))
                .node("changePlan", CHANGE_PLAN)
                    .dependsOn("collectAccountInsuranceInput")
                    .input((results, ctx) -> menuInput(ctx, "account-insurance-menu"))
                .node("warrantyClaimsFlow", warrantyClaimsFlow)
                    .dependsOn("collectAccountInsuranceInput")
                    .input((results, ctx) -> baseCallInput(ctx))
                .node("activateService", ACTIVATE_SERVICE)
                    .dependsOn("collectAccountInsuranceInput")
                    .input((results, ctx) -> menuInput(ctx, "account-insurance-menu"))
                .node("closeAccount", CLOSE_ACCOUNT)
                    .dependsOn("collectAccountInsuranceInput")
                    .input((results, ctx) -> menuInput(ctx, "account-insurance-menu"))
                .node("accountReturn", RETURN_PREVIOUS_MENU)
                    .dependsOn("collectAccountInsuranceInput")
                    .input((results, ctx) -> menuInput(ctx, "account-insurance-menu"))
                .branch("collectAccountInsuranceInput")
                    .on("key")
                    .when(v -> "1".equals(v), "changePassword")
                    .when(v -> "2".equals(v), "changePlan")
                    .when(v -> "3".equals(v), "warrantyClaimsFlow")
                    .when(v -> "4".equals(v), "activateService")
                    .when(v -> "5".equals(v), "closeAccount")
                    .otherwise("accountReturn")
                .node("accountInsuranceResult", MENU_RESULT)
                    .dependsOn("changePassword", "changePlan", "warrantyClaimsFlow",
                            "activateService", "closeAccount", "accountReturn")
                    .input((results, ctx) -> {
                        if (results.hasResult("warrantyClaimsFlow")) {
                            Map<String, Object> nested = unwrapSubGraphResult(
                                    results.getRaw("warrantyClaimsFlow"), "warrantyClaimsResult");
                            List<String> path = new ArrayList<>(List.of("accountInsurance"));
                            path.addAll(pathOf(nested));
                            return menuResult("account-insurance-menu", path,
                                    stringValue(nested, "action", "warranty-claims"),
                                    stringValue(nested, "detail", "warranty claim processed"));
                        }
                        String selected = firstCompleted(results,
                                "changePassword", "changePlan", "activateService", "closeAccount", "accountReturn");
                        Map<String, Object> action = results.get(selected, Map.class);
                        return menuResult("account-insurance-menu",
                                List.of("accountInsurance", selected),
                                stringValue(action, "action", selected),
                                stringValue(action, "detail", selected));
                    })
                .build();
    }

    public static Graph buildOrdersSubGraph() {
        return Graph.builder("orders-menu")
                .node("ordersPrompt", GREETING)
                    .input((results, ctx) -> promptInput(ctx, "订单物流：1查看订单 2退换货 3物流查询 4确认收货 5发票服务 6返回"))
                .node("collectOrdersInput", COLLECT_DTMF)
                    .dependsOn("ordersPrompt")
                    .input((results, ctx) -> menuInput(ctx, "orders-menu"))
                .node("viewOrders", VIEW_ORDERS)
                    .dependsOn("collectOrdersInput")
                    .input((results, ctx) -> menuInput(ctx, "orders-menu"))
                .node("returnsExchanges", RETURNS_EXCHANGES)
                    .dependsOn("collectOrdersInput")
                    .input((results, ctx) -> menuInput(ctx, "orders-menu"))
                .node("shippingStatus", SHIPPING_STATUS)
                    .dependsOn("collectOrdersInput")
                    .input((results, ctx) -> menuInput(ctx, "orders-menu"))
                .node("confirmReceipt", CONFIRM_RECEIPT)
                    .dependsOn("collectOrdersInput")
                    .input((results, ctx) -> menuInput(ctx, "orders-menu"))
                .node("invoiceService", INVOICE_SERVICE)
                    .dependsOn("collectOrdersInput")
                    .input((results, ctx) -> menuInput(ctx, "orders-menu"))
                .node("ordersReturn", RETURN_PREVIOUS_MENU)
                    .dependsOn("collectOrdersInput")
                    .input((results, ctx) -> menuInput(ctx, "orders-menu"))
                .branch("collectOrdersInput")
                    .on("key")
                    .when(v -> "1".equals(v), "viewOrders")
                    .when(v -> "2".equals(v), "returnsExchanges")
                    .when(v -> "3".equals(v), "shippingStatus")
                    .when(v -> "4".equals(v), "confirmReceipt")
                    .when(v -> "5".equals(v), "invoiceService")
                    .otherwise("ordersReturn")
                .node("ordersResult", MENU_RESULT)
                    .dependsOn("viewOrders", "returnsExchanges", "shippingStatus", "confirmReceipt", "invoiceService", "ordersReturn")
                    .input((results, ctx) -> {
                        String selected = firstCompleted(results,
                                "viewOrders", "returnsExchanges", "shippingStatus",
                                "confirmReceipt", "invoiceService", "ordersReturn");
                        Map<String, Object> action = results.get(selected, Map.class);
                        return menuResult("orders-menu",
                                List.of("ordersLogistics", selected),
                                stringValue(action, "action", selected),
                                stringValue(action, "detail", selected));
                    })
                .build();
    }

    public static Graph buildComplaintsSubGraph() {
        return Graph.builder("complaints-menu")
                .node("complaintsPrompt", GREETING)
                    .input((results, ctx) -> promptInput(ctx, "投诉建议：1服务质量 2计费问题 3网络质量 4员工态度 5其他投诉 6返回"))
                .node("collectComplaintsInput", COLLECT_DTMF)
                    .dependsOn("complaintsPrompt")
                    .input((results, ctx) -> menuInput(ctx, "complaints-menu"))
                .node("serviceQualityComplaint", SERVICE_QUALITY_COMPLAINT)
                    .dependsOn("collectComplaintsInput")
                    .input((results, ctx) -> menuInput(ctx, "complaints-menu"))
                .node("billingComplaint", BILLING_COMPLAINT)
                    .dependsOn("collectComplaintsInput")
                    .input((results, ctx) -> menuInput(ctx, "complaints-menu"))
                .node("networkComplaint", NETWORK_COMPLAINT)
                    .dependsOn("collectComplaintsInput")
                    .input((results, ctx) -> menuInput(ctx, "complaints-menu"))
                .node("staffComplaint", STAFF_COMPLAINT)
                    .dependsOn("collectComplaintsInput")
                    .input((results, ctx) -> menuInput(ctx, "complaints-menu"))
                .node("otherComplaint", OTHER_COMPLAINT)
                    .dependsOn("collectComplaintsInput")
                    .input((results, ctx) -> menuInput(ctx, "complaints-menu"))
                .node("complaintsReturn", RETURN_PREVIOUS_MENU)
                    .dependsOn("collectComplaintsInput")
                    .input((results, ctx) -> menuInput(ctx, "complaints-menu"))
                .branch("collectComplaintsInput")
                    .on("key")
                    .when(v -> "1".equals(v), "serviceQualityComplaint")
                    .when(v -> "2".equals(v), "billingComplaint")
                    .when(v -> "3".equals(v), "networkComplaint")
                    .when(v -> "4".equals(v), "staffComplaint")
                    .when(v -> "5".equals(v), "otherComplaint")
                    .otherwise("complaintsReturn")
                .node("complaintsResult", MENU_RESULT)
                    .dependsOn("serviceQualityComplaint", "billingComplaint",
                            "networkComplaint", "staffComplaint", "otherComplaint", "complaintsReturn")
                    .input((results, ctx) -> {
                        String selected = firstCompleted(results,
                                "serviceQualityComplaint", "billingComplaint", "networkComplaint",
                                "staffComplaint", "otherComplaint", "complaintsReturn");
                        Map<String, Object> action = results.get(selected, Map.class);
                        return menuResult("complaints-menu",
                                List.of("complaints", selected),
                                stringValue(action, "action", selected),
                                stringValue(action, "detail", selected) + " + transferred to complaint specialist");
                    })
                .build();
    }

    // ── Entry point ───────────────────────────────────────────────────────────

    public static void main(String[] args) {
        var registry = new DefaultOperatorRegistry();
        registerPascalCaseOperators(registry);
        registerNodeAliases(registry);

        Graph duplicateChargeGraph = buildDuplicateChargeSubGraph();
        var duplicateChargeFlow = new SubGraphOperator(duplicateChargeGraph, registry);
        registry.registerRaw("duplicateChargeFlow", duplicateChargeFlow);

        Graph billingDisputeGraph = buildBillingDisputeSubGraph(duplicateChargeFlow);
        var billingDisputeFlow = new SubGraphOperator(billingDisputeGraph, registry);
        registry.registerRaw("billingDisputeFlow", billingDisputeFlow);

        Graph billingGraph = buildBillingSubGraph(billingDisputeFlow);
        var billingFlow = new SubGraphOperator(billingGraph, registry);
        registry.registerRaw("billingFlow", billingFlow);

        Graph cannotConnectGraph = buildCannotConnectSubGraph();
        var cannotConnectFlow = new SubGraphOperator(cannotConnectGraph, registry);
        registry.registerRaw("cannotConnectFlow", cannotConnectFlow);

        Graph networkIssueGraph = buildNetworkIssueSubGraph(cannotConnectFlow);
        var networkIssueFlow = new SubGraphOperator(networkIssueGraph, registry);
        registry.registerRaw("networkIssueFlow", networkIssueFlow);

        Graph techGraph = buildTechSupportSubGraph(networkIssueFlow);
        var techFlow = new SubGraphOperator(techGraph, registry);
        registry.registerRaw("techFlow", techFlow);

        Graph submitClaimGraph = buildSubmitClaimSubGraph();
        var submitClaimFlow = new SubGraphOperator(submitClaimGraph, registry);
        registry.registerRaw("submitClaimFlow", submitClaimFlow);

        Graph warrantyClaimsGraph = buildWarrantyClaimsSubGraph(submitClaimFlow);
        var warrantyClaimsFlow = new SubGraphOperator(warrantyClaimsGraph, registry);
        registry.registerRaw("warrantyClaimsFlow", warrantyClaimsFlow);

        Graph accountGraph = buildAccountInsuranceSubGraph(warrantyClaimsFlow);
        var accountFlow = new SubGraphOperator(accountGraph, registry);
        registry.registerRaw("accountFlow", accountFlow);

        Graph ordersGraph = buildOrdersSubGraph();
        var ordersFlow = new SubGraphOperator(ordersGraph, registry);
        registry.registerRaw("ordersFlow", ordersFlow);

        Graph complaintsGraph = buildComplaintsSubGraph();
        var complaintsFlow = new SubGraphOperator(complaintsGraph, registry);
        registry.registerRaw("complaintsFlow", complaintsFlow);

        var loader = new GraphLoader(registry);
        loader.compiler().registerSubGraph("duplicate-charge-menu", duplicateChargeGraph);
        loader.compiler().registerSubGraph("billing-dispute-menu", billingDisputeGraph);
        loader.compiler().registerSubGraph("billing-menu", billingGraph);
        loader.compiler().registerSubGraph("cannot-connect-menu", cannotConnectGraph);
        loader.compiler().registerSubGraph("network-issue-menu", networkIssueGraph);
        loader.compiler().registerSubGraph("tech-support-menu", techGraph);
        loader.compiler().registerSubGraph("submit-claim-menu", submitClaimGraph);
        loader.compiler().registerSubGraph("warranty-claims-menu", warrantyClaimsGraph);
        loader.compiler().registerSubGraph("account-insurance-menu", accountGraph);
        loader.compiler().registerSubGraph("orders-menu", ordersGraph);
        loader.compiler().registerSubGraph("complaints-menu", complaintsGraph);

        String dsl = loadResource("/bloge/ivr-customer-service.bloge");
        Graph graph = loader.load(dsl);

        var engine = GraphEngine.builder()
                .registry(registry)
                .interceptors(List.of())
                .listeners(List.of(new LoggingListener()))
                .build();
        var ctx = new GraphContext(Map.of(
                "callId", "CALL-IVR-DSL-001",
                "customerId", "CUST-9001",
                "callerPhone", "+86-139-8888-1001",
                "simulatedKeys", Map.of(
                        "main-menu", "1",
                        "billing-menu", "2",
                        "billing-dispute-menu", "1",
                        "duplicate-charge-menu", "1"
                )
        ));

        GraphResult result = engine.execute(graph, ctx);

        System.out.println("\n═══════════════════════════════════════════════");
        System.out.println("  IVR Customer Service Result (DSL/SubGraph)");
        System.out.println("═══════════════════════════════════════════════");
        System.out.println("  Success : " + result.isSuccess());
        System.out.println("  Elapsed : " + result.elapsed().toMillis() + " ms");
        System.out.println();
        for (var entry : result.statusMap().entrySet()) {
            System.out.printf("  %-28s → %s%n", entry.getKey(), entry.getValue());
        }
        System.out.println();

        if (result.getStatus("callSummary") == NodeStatus.COMPLETED) {
            System.out.println("  Call summary : " + result.results().getRaw("callSummary"));
        }
        if (result.getStatus("satisfactionSurvey") == NodeStatus.COMPLETED) {
            System.out.println("  Survey       : " + result.results().getRaw("satisfactionSurvey"));
        }
        if (result.getStatus("saveCallRecord") == NodeStatus.COMPLETED) {
            System.out.println("  Record saved : " + result.results().getRaw("saveCallRecord"));
        }
        System.out.println("═══════════════════════════════════════════════");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static void simulateLatency() throws InterruptedException {
        Thread.sleep(ThreadLocalRandom.current().nextInt(20, 51));
    }

    private static Operator<Map<String, Object>, Map<String, Object>> actionOperator(String action, String detail) {
        return (input, ctx) -> {
            simulateLatency();
            return Map.of(
                    "callId", input.getOrDefault("callId", ""),
                    "action", action,
                    "detail", detail,
                    "success", true);
        };
    }

    private static Map<String, Object> promptInput(GraphContext ctx, String text) {
        return Map.of("callId", ctx.get("callId", String.class), "text", text);
    }

    private static Map<String, Object> baseCallInput(GraphContext ctx) {
        return Map.of(
                "callId", ctx.get("callId", String.class),
                "customerId", ctx.get("customerId", String.class));
    }

    private static Map<String, Object> menuInput(GraphContext ctx, String menuId) {
        return Map.of(
                "callId", ctx.get("callId", String.class),
                "customerId", ctx.get("customerId", String.class),
                "menuId", menuId);
    }

    private static Map<String, String> simulatedKeys(GraphContext ctx) {
        Object raw = ctx.get("simulatedKeys");
        if (!(raw instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (var entry : map.entrySet()) {
            result.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
        }
        return result;
    }

    private static Map<String, Object> menuResult(String menuId, List<String> menuPath, String action, String detail) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("menuId", menuId);
        result.put("menuPath", List.copyOf(menuPath));
        result.put("action", action);
        result.put("detail", detail);
        return result;
    }

    private static String firstCompleted(NodeResults results, String... nodeIds) {
        for (String nodeId : nodeIds) {
            if (results.hasResult(nodeId)) {
                return nodeId;
            }
        }
        return nodeIds[nodeIds.length - 1];
    }

    private static Map<String, Object> unwrapSubGraphResult(Object raw, String terminalId) {
        if (raw instanceof Map<?, ?> m) {
            Object nested = m.get(terminalId);
            if (nested instanceof Map<?, ?> nestedMap) {
                Map<String, Object> result = new LinkedHashMap<>();
                for (var entry : nestedMap.entrySet()) {
                    result.put(String.valueOf(entry.getKey()), entry.getValue());
                }
                return result;
            }
        }
        return menuResult("unknown", List.of("unknown"), "unknown", "unknown");
    }

    private static List<String> pathOf(Map<String, Object> result) {
        Object raw = result.get("menuPath");
        if (raw instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of("unknown");
    }

    private static String stringValue(Map<String, Object> map, String key, String fallback) {
        if (map == null || !map.containsKey(key)) {
            return fallback;
        }
        Object value = map.get(key);
        return value == null ? fallback : String.valueOf(value);
    }

    private static String menuValue(Map<String, String> simulated, String menuId, String fallback) {
        return simulated.getOrDefault(menuId, fallback);
    }

    private static List<String> buildPathFromContext(GraphContext ctx, String selectedKey) {
        Map<String, String> simulated = simulatedKeys(ctx);
        List<String> path = new ArrayList<>(List.of("mainMenu"));
        switch (selectedKey) {
            case "1" -> {
                path.add("billing");
                String billing = menuValue(simulated, "billing-menu", "1");
                if ("2".equals(billing)) {
                    path.add("billingDispute");
                    String dispute = menuValue(simulated, "billing-dispute-menu", "1");
                    if ("1".equals(dispute)) {
                        path.add("duplicateCharge");
                        path.add(switch (menuValue(simulated, "duplicate-charge-menu", "1")) {
                            case "2" -> "manualReview";
                            case "3" -> "viewChargeHistory";
                            case "4" -> "downloadStatement";
                            case "5" -> "transferSupervisor";
                            default -> "autoRefund";
                        });
                    } else {
                        path.add(switch (dispute) {
                            case "2" -> "processUnauthorizedCharge";
                            case "3" -> "processWrongAmount";
                            case "4" -> "queryRefundStatus";
                            default -> "processOtherDispute";
                        });
                    }
                } else {
                    path.add(switch (billing) {
                        case "3" -> "topUpPayment";
                        case "4" -> "planPricing";
                        case "5" -> "feeBreakdown";
                        case "6" -> "billingReturn";
                        default -> "viewBill";
                    });
                }
            }
            case "2" -> {
                path.add("techSupport");
                String tech = menuValue(simulated, "tech-support-menu", "1");
                if ("1".equals(tech)) {
                    path.add("networkIssues");
                    String network = menuValue(simulated, "network-issue-menu", "1");
                    if ("1".equals(network)) {
                        path.add("cannotConnect");
                        path.add(switch (menuValue(simulated, "cannot-connect-menu", "1")) {
                            case "2" -> "checkLine";
                            case "3" -> "remoteDiagnostic";
                            case "4" -> "scheduleOnsite";
                            case "5" -> "deviceReplacement";
                            default -> "restartDevice";
                        });
                    } else {
                        path.add(switch (network) {
                            case "2" -> "intermittentFix";
                            case "3" -> "slowSpeedFix";
                            case "4" -> "dnsFix";
                            default -> "vpnFix";
                        });
                    }
                } else {
                    path.add(switch (tech) {
                        case "2" -> "deviceIssue";
                        case "3" -> "softwareHelp";
                        case "4" -> "scheduleInstall";
                        case "5" -> "runSpeedTest";
                        default -> "techReturn";
                    });
                }
            }
            case "3" -> {
                path.add("accountInsurance");
                String account = menuValue(simulated, "account-insurance-menu", "1");
                if ("3".equals(account)) {
                    path.add("warrantyClaims");
                    String warranty = menuValue(simulated, "warranty-claims-menu", "2");
                    if ("2".equals(warranty)) {
                        path.add("submitClaim");
                        path.add(switch (menuValue(simulated, "submit-claim-menu", "1")) {
                            case "2" -> "claimAccidentalLoss";
                            case "3" -> "claimBattery";
                            case "4" -> "claimScreen";
                            case "5" -> "claimOther";
                            default -> "claimDeviceDamage";
                        });
                    } else {
                        path.add(switch (warranty) {
                            case "1" -> "warrantyInquiry";
                            case "3" -> "claimStatus";
                            case "4" -> "extendedWarranty";
                            default -> "warrantyPolicy";
                        });
                    }
                } else {
                    path.add(switch (account) {
                        case "2" -> "changePlan";
                        case "4" -> "activateService";
                        case "5" -> "closeAccount";
                        case "6" -> "accountReturn";
                        default -> "changePassword";
                    });
                }
            }
            case "4" -> {
                path.add("ordersLogistics");
                path.add(switch (menuValue(simulated, "orders-menu", "1")) {
                    case "2" -> "returnsExchanges";
                    case "3" -> "shippingStatus";
                    case "4" -> "confirmReceipt";
                    case "5" -> "invoiceService";
                    case "6" -> "ordersReturn";
                    default -> "viewOrders";
                });
            }
            case "5" -> {
                path.add("complaints");
                path.add(switch (menuValue(simulated, "complaints-menu", "1")) {
                    case "2" -> "billingComplaint";
                    case "3" -> "networkComplaint";
                    case "4" -> "staffComplaint";
                    case "5" -> "otherComplaint";
                    case "6" -> "complaintsReturn";
                    default -> "serviceQualityComplaint";
                });
            }
            default -> path.add("liveAgent");
        }
        return List.copyOf(path);
    }

    private static void registerPascalCaseOperators(DefaultOperatorRegistry registry) {
        registry.register("Greeting", GREETING);
        registry.register("IdentifyCustomer", IDENTIFY_CUSTOMER);
        registry.register("CollectDtmf", COLLECT_DTMF);
        registry.register("TransferAgent", TRANSFER_AGENT);
        registry.register("SatisfactionSurvey", SATISFACTION_SURVEY);
        registry.register("CallSummaryBuilder", CALL_SUMMARY_BUILDER);
        registry.register("SaveCallRecord", SAVE_CALL_RECORD);

        registry.register("ViewBill", VIEW_BILL);
        registry.register("TopUpPayment", TOP_UP_PAYMENT);
        registry.register("PlanPricing", PLAN_PRICING);
        registry.register("FeeBreakdown", FEE_BREAKDOWN);
        registry.register("ProcessUnauthorizedCharge", PROCESS_UNAUTHORIZED_CHARGE);
        registry.register("ProcessWrongAmount", PROCESS_WRONG_AMOUNT);
        registry.register("QueryRefundStatus", QUERY_REFUND_STATUS);
        registry.register("ProcessOtherDispute", PROCESS_OTHER_DISPUTE);
        registry.register("AutoRefund", AUTO_REFUND);
        registry.register("ManualReview", MANUAL_REVIEW);
        registry.register("ViewChargeHistory", VIEW_CHARGE_HISTORY);
        registry.register("DownloadStatement", DOWNLOAD_STATEMENT);
        registry.register("TransferSupervisor", TRANSFER_SUPERVISOR);

        registry.register("DeviceIssue", DEVICE_ISSUE);
        registry.register("SoftwareHelp", SOFTWARE_HELP);
        registry.register("ScheduleInstall", SCHEDULE_INSTALL);
        registry.register("RunSpeedTest", RUN_SPEED_TEST);
        registry.register("IntermittentFix", INTERMITTENT_FIX);
        registry.register("SlowSpeedFix", SLOW_SPEED_FIX);
        registry.register("DnsFix", DNS_FIX);
        registry.register("VpnFix", VPN_FIX);
        registry.register("RestartDevice", RESTART_DEVICE);
        registry.register("CheckLine", CHECK_LINE);
        registry.register("RemoteDiagnostic", REMOTE_DIAGNOSTIC);
        registry.register("ScheduleOnsite", SCHEDULE_ONSITE);
        registry.register("DeviceReplacement", DEVICE_REPLACEMENT);

        registry.register("ChangePassword", CHANGE_PASSWORD);
        registry.register("ChangePlan", CHANGE_PLAN);
        registry.register("ActivateService", ACTIVATE_SERVICE);
        registry.register("CloseAccount", CLOSE_ACCOUNT);
        registry.register("WarrantyInquiry", WARRANTY_INQUIRY);
        registry.register("ClaimStatus", CLAIM_STATUS);
        registry.register("ExtendedWarranty", EXTENDED_WARRANTY);
        registry.register("WarrantyPolicy", WARRANTY_POLICY);
        registry.register("ClaimDeviceDamage", CLAIM_DEVICE_DAMAGE);
        registry.register("ClaimAccidentalLoss", CLAIM_ACCIDENTAL_LOSS);
        registry.register("ClaimBattery", CLAIM_BATTERY);
        registry.register("ClaimScreen", CLAIM_SCREEN);
        registry.register("ClaimOther", CLAIM_OTHER);

        registry.register("ViewOrders", VIEW_ORDERS);
        registry.register("ReturnsExchanges", RETURNS_EXCHANGES);
        registry.register("ShippingStatus", SHIPPING_STATUS);
        registry.register("ConfirmReceipt", CONFIRM_RECEIPT);
        registry.register("InvoiceService", INVOICE_SERVICE);

        registry.register("ServiceQualityComplaint", SERVICE_QUALITY_COMPLAINT);
        registry.register("BillingComplaint", BILLING_COMPLAINT);
        registry.register("NetworkComplaint", NETWORK_COMPLAINT);
        registry.register("StaffComplaint", STAFF_COMPLAINT);
        registry.register("OtherComplaint", OTHER_COMPLAINT);
    }

    private static void registerNodeAliases(DefaultOperatorRegistry registry) {
        for (String id : List.of(
                "greeting", "mainMenuPrompt",
                "billingPrompt", "billingDisputePrompt", "duplicateChargePrompt",
                "techSupportPrompt", "networkIssuePrompt", "cannotConnectPrompt",
                "accountInsurancePrompt", "warrantyClaimsPrompt", "submitClaimPrompt",
                "ordersPrompt", "complaintsPrompt")) {
            registry.registerRaw(id, GREETING);
        }
        for (String id : List.of(
                "collectMainMenu",
                "collectBillingInput", "collectBillingDisputeInput", "collectDuplicateChargeInput",
                "collectTechSupportInput", "collectNetworkIssueInput", "collectCannotConnectInput",
                "collectAccountInsuranceInput", "collectWarrantyClaimsInput", "collectSubmitClaimInput",
                "collectOrdersInput", "collectComplaintsInput")) {
            registry.registerRaw(id, COLLECT_DTMF);
        }
        registry.registerRaw("identifyCustomer", IDENTIFY_CUSTOMER);
        registry.registerRaw("transferLiveAgent", TRANSFER_AGENT);
        registry.registerRaw("satisfactionSurvey", SATISFACTION_SURVEY);
        registry.registerRaw("callSummary", CALL_SUMMARY_BUILDER);
        registry.registerRaw("saveCallRecord", SAVE_CALL_RECORD);
        for (String id : List.of(
                "duplicateChargeResult", "billingDisputeResult", "billingResult",
                "cannotConnectResult", "networkIssueResult", "techSupportResult",
                "submitClaimResult", "warrantyClaimsResult", "accountInsuranceResult",
                "ordersResult", "complaintsResult")) {
            registry.registerRaw(id, MENU_RESULT);
        }

        registry.registerRaw("viewBill", VIEW_BILL);
        registry.registerRaw("topUpPayment", TOP_UP_PAYMENT);
        registry.registerRaw("planPricing", PLAN_PRICING);
        registry.registerRaw("feeBreakdown", FEE_BREAKDOWN);
        registry.registerRaw("processUnauthorizedCharge", PROCESS_UNAUTHORIZED_CHARGE);
        registry.registerRaw("processWrongAmount", PROCESS_WRONG_AMOUNT);
        registry.registerRaw("queryRefundStatus", QUERY_REFUND_STATUS);
        registry.registerRaw("processOtherDispute", PROCESS_OTHER_DISPUTE);
        registry.registerRaw("autoRefund", AUTO_REFUND);
        registry.registerRaw("manualReview", MANUAL_REVIEW);
        registry.registerRaw("viewChargeHistory", VIEW_CHARGE_HISTORY);
        registry.registerRaw("downloadStatement", DOWNLOAD_STATEMENT);
        registry.registerRaw("transferSupervisor", TRANSFER_SUPERVISOR);
        registry.registerRaw("billingReturn", RETURN_PREVIOUS_MENU);

        registry.registerRaw("deviceIssue", DEVICE_ISSUE);
        registry.registerRaw("softwareHelp", SOFTWARE_HELP);
        registry.registerRaw("scheduleInstall", SCHEDULE_INSTALL);
        registry.registerRaw("runSpeedTest", RUN_SPEED_TEST);
        registry.registerRaw("intermittentFix", INTERMITTENT_FIX);
        registry.registerRaw("slowSpeedFix", SLOW_SPEED_FIX);
        registry.registerRaw("dnsFix", DNS_FIX);
        registry.registerRaw("vpnFix", VPN_FIX);
        registry.registerRaw("restartDevice", RESTART_DEVICE);
        registry.registerRaw("checkLine", CHECK_LINE);
        registry.registerRaw("remoteDiagnostic", REMOTE_DIAGNOSTIC);
        registry.registerRaw("scheduleOnsite", SCHEDULE_ONSITE);
        registry.registerRaw("deviceReplacement", DEVICE_REPLACEMENT);
        registry.registerRaw("techReturn", RETURN_PREVIOUS_MENU);

        registry.registerRaw("changePassword", CHANGE_PASSWORD);
        registry.registerRaw("changePlan", CHANGE_PLAN);
        registry.registerRaw("activateService", ACTIVATE_SERVICE);
        registry.registerRaw("closeAccount", CLOSE_ACCOUNT);
        registry.registerRaw("warrantyInquiry", WARRANTY_INQUIRY);
        registry.registerRaw("claimStatus", CLAIM_STATUS);
        registry.registerRaw("extendedWarranty", EXTENDED_WARRANTY);
        registry.registerRaw("warrantyPolicy", WARRANTY_POLICY);
        registry.registerRaw("claimDeviceDamage", CLAIM_DEVICE_DAMAGE);
        registry.registerRaw("claimAccidentalLoss", CLAIM_ACCIDENTAL_LOSS);
        registry.registerRaw("claimBattery", CLAIM_BATTERY);
        registry.registerRaw("claimScreen", CLAIM_SCREEN);
        registry.registerRaw("claimOther", CLAIM_OTHER);
        registry.registerRaw("accountReturn", RETURN_PREVIOUS_MENU);

        registry.registerRaw("viewOrders", VIEW_ORDERS);
        registry.registerRaw("returnsExchanges", RETURNS_EXCHANGES);
        registry.registerRaw("shippingStatus", SHIPPING_STATUS);
        registry.registerRaw("confirmReceipt", CONFIRM_RECEIPT);
        registry.registerRaw("invoiceService", INVOICE_SERVICE);
        registry.registerRaw("ordersReturn", RETURN_PREVIOUS_MENU);

        registry.registerRaw("serviceQualityComplaint", SERVICE_QUALITY_COMPLAINT);
        registry.registerRaw("billingComplaint", BILLING_COMPLAINT);
        registry.registerRaw("networkComplaint", NETWORK_COMPLAINT);
        registry.registerRaw("staffComplaint", STAFF_COMPLAINT);
        registry.registerRaw("otherComplaint", OTHER_COMPLAINT);
        registry.registerRaw("complaintsReturn", RETURN_PREVIOUS_MENU);
    }

    private static String loadResource(String resourcePath) {
        try (var stream = IvrCustomerServiceDslExample.class.getResourceAsStream(resourcePath)) {
            if (stream == null) {
                throw new IllegalStateException("Resource not found: " + resourcePath);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read resource: " + resourcePath, e);
        }
    }
}

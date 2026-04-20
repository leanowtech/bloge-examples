package com.leanowtech.bloge.examples.ivr;

import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.context.NodeResults;
import com.leanowtech.bloge.core.engine.GraphEngine;
import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.model.NodeStatus;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.operator.OperatorLayer;
import com.leanowtech.bloge.core.operator.OperatorMeta;
import com.leanowtech.bloge.core.engine.operators.SubGraphOperator;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.examples.common.LoggingListener;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Comprehensive IVR customer service (Fluent API + SubGraph composition),
 * combining telecom, banking, insurance, and e-commerce support in one menu tree.
 *
 * <p>IVR menu tree:
 * <pre>
 * mainMenu
 * ├─ 1 billing
 * │  ├─ 1 viewBill
 * │  ├─ 2 billingDispute
 * │  │  ├─ 1 duplicateCharge
 * │  │  │  ├─ 1 autoRefund
 * │  │  │  ├─ 2 manualReview
 * │  │  │  └─ ...
 * │  │  └─ ...
 * │  └─ ...
 * ├─ 2 techSupport
 * │  ├─ 1 networkIssues
 * │  │  ├─ 1 cannotConnect
 * │  │  │  ├─ 1 restartDevice
 * │  │  │  └─ ...
 * │  │  └─ ...
 * │  └─ ...
 * ├─ 3 accountInsurance
 * │  ├─ 3 warrantyClaims
 * │  │  ├─ 2 submitClaim
 * │  │  │  ├─ 1 claimDeviceDamage
 * │  │  │  └─ ...
 * │  │  └─ ...
 * │  └─ ...
 * ├─ 4 ordersLogistics
 * ├─ 5 complaints
 * └─ 0 liveAgent
 * </pre>
 *
 * <p>Uses SubGraphOperator to compose each menu level into reusable/testable modules.
 *
 * @see IvrCustomerServiceDslExample
 * @see IvrCustomerServiceFlatExample
 * @see IvrCustomerServiceFlatDslExample
 */
@SuppressWarnings({"unchecked", "preview"})
public class IvrCustomerServiceExample {

    // ── Why SubGraph? ──────────────────────────────────────────────
    // Traditional IVR systems define menu trees in XML/config (Avaya, Genesys, Cisco).
    // Graph engine replaces this with composable sub-graphs:
    //   1. Each menu level is a self-contained Graph — testable in isolation
    //   2. Sub-graphs can be reused across different IVR flows
    //   3. Built-in retry/timeout/fallback replaces IVR platform-specific error handling
    //   4. Observable via GraphListener — replaces proprietary IVR reporting
    //   5. DSL provides a readable alternative to IVR XML configuration

    // ── Domain records ────────────────────────────────────────────────────────

    public record DtmfInput(String callId, String customerId, String menuId) {}

    public record DtmfResult(String key, String menuId) {}

    public record TtsPrompt(String callId, String text) {}

    public record TtsResult(String callId, String text, boolean played) {}

    public record CustomerInfo(String id, String name, String tier, String phone) {}

    public record TransferResult(String callId, String queue, String agentId) {}

    public record ActionResult(String callId, String action, String detail, boolean success) {}

    public record CallSummary(String callId, String customerId, List<String> menuPath, String resolution) {}

    public record SaveResult(String callId, String recordId, boolean persisted) {}

    public record SurveyResult(String callId, int score, String comment) {}

    // ── Common operators ──────────────────────────────────────────────────────

    @OperatorMeta(layer = OperatorLayer.CAPABILITY, tags = {"ivr", "tts"},
            description = "Plays a TTS prompt to the caller", owner = "ivr-team")
    static final Operator<TtsPrompt, TtsResult> GREETING = (input, ctx) -> {
        simulateLatency();
        return new TtsResult(input.callId(), input.text(), true);
    };

    @OperatorMeta(layer = OperatorLayer.DOMAIN, tags = {"ivr", "customer"},
            description = "Identifies the caller from ANI/customer id", owner = "ivr-team")
    static final Operator<String, CustomerInfo> IDENTIFY_CUSTOMER = (input, ctx) -> {
        simulateLatency();
        String phone = ctx.graphContext().containsKey("callerPhone")
                ? ctx.graphContext().get("callerPhone", String.class)
                : "+86-138-0000-0000";
        return new CustomerInfo(input, "Customer-" + input, "gold", phone);
    };

    @OperatorMeta(layer = OperatorLayer.CAPABILITY, tags = {"ivr", "dtmf"},
            description = "Collects DTMF key using simulated context values", owner = "ivr-team")
    static final Operator<DtmfInput, DtmfResult> COLLECT_DTMF = (input, ctx) -> {
        simulateLatency();
        Map<String, String> simulated = simulatedKeys(ctx.graphContext());
        String key = simulated.getOrDefault(input.menuId(), "0");
        return new DtmfResult(key, input.menuId());
    };

    @OperatorMeta(layer = OperatorLayer.DOMAIN, tags = {"ivr", "routing"},
            description = "Transfers the caller to a live agent queue", owner = "ivr-team")
    static final Operator<DtmfInput, TransferResult> TRANSFER_AGENT = (input, ctx) -> {
        simulateLatency();
        return new TransferResult(input.callId(), "live-agent", "AGENT-001");
    };

    @OperatorMeta(layer = OperatorLayer.DOMAIN, tags = {"ivr", "survey"},
            description = "Collects a post-call satisfaction survey", owner = "ivr-team")
    static final Operator<Map<String, Object>, SurveyResult> SATISFACTION_SURVEY = (input, ctx) -> {
        simulateLatency();
        String action = String.valueOf(input.getOrDefault("action", "unknown"));
        int score = action.contains("complaint") || action.contains("transfer") ? 3 : 5;
        return new SurveyResult(
                String.valueOf(input.getOrDefault("callId", "")),
                score,
                "Survey completed for action: " + action);
    };

    @OperatorMeta(layer = OperatorLayer.DOMAIN, tags = {"ivr", "summary"},
            description = "Builds final call summary", owner = "ivr-team")
    static final Operator<Map<String, Object>, CallSummary> CALL_SUMMARY_BUILDER = (input, ctx) -> {
        simulateLatency();
        return new CallSummary(
                String.valueOf(input.getOrDefault("callId", "")),
                String.valueOf(input.getOrDefault("customerId", "")),
                (List<String>) input.getOrDefault("menuPath", List.of("mainMenu", "unknown")),
                String.valueOf(input.getOrDefault("resolution", "unknown")));
    };

    @OperatorMeta(layer = OperatorLayer.INFRASTRUCTURE, tags = {"ivr", "persistence"},
            description = "Persists call record", owner = "crm-team")
    static final Operator<CallSummary, SaveResult> SAVE_CALL_RECORD = (input, ctx) -> {
        simulateLatency();
        return new SaveResult(input.callId(), "IVR-" + input.callId(), true);
    };

    @OperatorMeta(layer = OperatorLayer.DOMAIN, tags = {"ivr", "menu"},
            description = "Pass-through menu result materializer", owner = "ivr-team")
    static final Operator<Map<String, Object>, Map<String, Object>> MENU_RESULT = (input, ctx) -> {
        simulateLatency();
        return new LinkedHashMap<>(input);
    };

    @OperatorMeta(layer = OperatorLayer.DOMAIN, tags = {"ivr", "menu"},
            description = "Returns to previous menu", owner = "ivr-team")
    static final Operator<DtmfInput, ActionResult> RETURN_PREVIOUS_MENU =
            actionOperator("return-menu", "Returning to previous menu");

    // ── Billing chain operators ───────────────────────────────────────────────

    @OperatorMeta(layer = OperatorLayer.DOMAIN, tags = {"ivr", "billing"},
            description = "Views latest bill", owner = "billing-team")
    static final Operator<DtmfInput, ActionResult> VIEW_BILL =
            actionOperator("view-bill", "Latest bill amount: CNY 228.40");

    @OperatorMeta(layer = OperatorLayer.DOMAIN, tags = {"ivr", "billing"},
            description = "Handles top-up/payment", owner = "billing-team")
    static final Operator<DtmfInput, ActionResult> TOP_UP_PAYMENT =
            actionOperator("top-up-payment", "Top-up payment accepted and receipt sent");

    @OperatorMeta(layer = OperatorLayer.DOMAIN, tags = {"ivr", "billing"},
            description = "Explains plan pricing", owner = "billing-team")
    static final Operator<DtmfInput, ActionResult> PLAN_PRICING =
            actionOperator("plan-pricing", "Current plan monthly fee: CNY 129.00");

    @OperatorMeta(layer = OperatorLayer.DOMAIN, tags = {"ivr", "billing"},
            description = "Shows fee breakdown", owner = "billing-team")
    static final Operator<DtmfInput, ActionResult> FEE_BREAKDOWN =
            actionOperator("fee-breakdown", "Fee details sent to SMS");

    @OperatorMeta(layer = OperatorLayer.DOMAIN, tags = {"ivr", "billing"},
            description = "Processes unauthorized charge dispute", owner = "billing-team")
    static final Operator<DtmfInput, ActionResult> PROCESS_UNAUTHORIZED_CHARGE =
            actionOperator("unauthorized-charge", "Unauthorized charge dispute submitted");

    @OperatorMeta(layer = OperatorLayer.DOMAIN, tags = {"ivr", "billing"},
            description = "Processes wrong amount dispute", owner = "billing-team")
    static final Operator<DtmfInput, ActionResult> PROCESS_WRONG_AMOUNT =
            actionOperator("wrong-amount", "Wrong amount dispute recorded");

    @OperatorMeta(layer = OperatorLayer.DOMAIN, tags = {"ivr", "billing"},
            description = "Queries refund status", owner = "billing-team")
    static final Operator<DtmfInput, ActionResult> QUERY_REFUND_STATUS =
            actionOperator("refund-status", "Refund is being processed");

    @OperatorMeta(layer = OperatorLayer.DOMAIN, tags = {"ivr", "billing"},
            description = "Processes other disputes", owner = "billing-team")
    static final Operator<DtmfInput, ActionResult> PROCESS_OTHER_DISPUTE =
            actionOperator("other-dispute", "Other dispute ticket created");

    @OperatorMeta(layer = OperatorLayer.DOMAIN, tags = {"ivr", "billing"},
            description = "Issues automatic refund", owner = "billing-team")
    static final Operator<DtmfInput, ActionResult> AUTO_REFUND =
            actionOperator("auto-refund", "Duplicate charge refunded automatically");

    @OperatorMeta(layer = OperatorLayer.DOMAIN, tags = {"ivr", "billing"},
            description = "Escalates to manual review", owner = "billing-team")
    static final Operator<DtmfInput, ActionResult> MANUAL_REVIEW =
            actionOperator("manual-review", "Case sent to manual billing review");

    @OperatorMeta(layer = OperatorLayer.DOMAIN, tags = {"ivr", "billing"},
            description = "Shows charge history", owner = "billing-team")
    static final Operator<DtmfInput, ActionResult> VIEW_CHARGE_HISTORY =
            actionOperator("charge-history", "Charge history displayed in app");

    @OperatorMeta(layer = OperatorLayer.DOMAIN, tags = {"ivr", "billing"},
            description = "Downloads statement", owner = "billing-team")
    static final Operator<DtmfInput, ActionResult> DOWNLOAD_STATEMENT =
            actionOperator("download-statement", "Statement download link sent");

    @OperatorMeta(layer = OperatorLayer.DOMAIN, tags = {"ivr", "billing"},
            description = "Transfers to supervisor", owner = "billing-team")
    static final Operator<DtmfInput, ActionResult> TRANSFER_SUPERVISOR =
            actionOperator("transfer-supervisor", "Transferred to billing supervisor queue");

    // ── Tech support chain operators ──────────────────────────────────────────

    @OperatorMeta(layer = OperatorLayer.DOMAIN, tags = {"ivr", "tech"},
            description = "Handles device issue", owner = "tech-team")
    static final Operator<DtmfInput, ActionResult> DEVICE_ISSUE =
            actionOperator("device-issue", "Device troubleshooting checklist sent");

    @OperatorMeta(layer = OperatorLayer.DOMAIN, tags = {"ivr", "tech"},
            description = "Handles software/app issue", owner = "tech-team")
    static final Operator<DtmfInput, ActionResult> SOFTWARE_HELP =
            actionOperator("software-help", "App support article delivered");

    @OperatorMeta(layer = OperatorLayer.DOMAIN, tags = {"ivr", "tech"},
            description = "Schedules install service", owner = "tech-team")
    static final Operator<DtmfInput, ActionResult> SCHEDULE_INSTALL =
            actionOperator("schedule-install", "Installation slot reserved");

    @OperatorMeta(layer = OperatorLayer.DOMAIN, tags = {"ivr", "tech"},
            description = "Runs speed test", owner = "tech-team")
    static final Operator<DtmfInput, ActionResult> RUN_SPEED_TEST =
            actionOperator("run-speed-test", "Speed test started and report sent");

    @OperatorMeta(layer = OperatorLayer.DOMAIN, tags = {"ivr", "tech"},
            description = "Fixes intermittent connection", owner = "tech-team")
    static final Operator<DtmfInput, ActionResult> INTERMITTENT_FIX =
            actionOperator("intermittent-fix", "Stability profile applied");

    @OperatorMeta(layer = OperatorLayer.DOMAIN, tags = {"ivr", "tech"},
            description = "Fixes slow speed", owner = "tech-team")
    static final Operator<DtmfInput, ActionResult> SLOW_SPEED_FIX =
            actionOperator("slow-speed-fix", "Bandwidth optimization applied");

    @OperatorMeta(layer = OperatorLayer.DOMAIN, tags = {"ivr", "tech"},
            description = "Fixes DNS issue", owner = "tech-team")
    static final Operator<DtmfInput, ActionResult> DNS_FIX =
            actionOperator("dns-fix", "DNS reset completed");

    @OperatorMeta(layer = OperatorLayer.DOMAIN, tags = {"ivr", "tech"},
            description = "Fixes VPN issue", owner = "tech-team")
    static final Operator<DtmfInput, ActionResult> VPN_FIX =
            actionOperator("vpn-fix", "VPN profile refreshed");

    @OperatorMeta(layer = OperatorLayer.DOMAIN, tags = {"ivr", "tech"},
            description = "Guides restart action", owner = "tech-team")
    static final Operator<DtmfInput, ActionResult> RESTART_DEVICE =
            actionOperator("restart-device", "Device restart guidance completed");

    @OperatorMeta(layer = OperatorLayer.DOMAIN, tags = {"ivr", "tech"},
            description = "Checks line quality", owner = "tech-team")
    static final Operator<DtmfInput, ActionResult> CHECK_LINE =
            actionOperator("check-line", "Line quality check passed");

    @OperatorMeta(layer = OperatorLayer.DOMAIN, tags = {"ivr", "tech"},
            description = "Runs remote diagnostic", owner = "tech-team")
    static final Operator<DtmfInput, ActionResult> REMOTE_DIAGNOSTIC =
            actionOperator("remote-diagnostic", "Remote diagnostic started");

    @OperatorMeta(layer = OperatorLayer.DOMAIN, tags = {"ivr", "tech"},
            description = "Schedules onsite appointment", owner = "tech-team")
    static final Operator<DtmfInput, ActionResult> SCHEDULE_ONSITE =
            actionOperator("schedule-onsite", "Onsite technician appointment booked");

    @OperatorMeta(layer = OperatorLayer.DOMAIN, tags = {"ivr", "tech"},
            description = "Handles device replacement", owner = "tech-team")
    static final Operator<DtmfInput, ActionResult> DEVICE_REPLACEMENT =
            actionOperator("device-replacement", "Replacement request created");

    // ── Account & insurance chain operators ───────────────────────────────────

    @OperatorMeta(layer = OperatorLayer.DOMAIN, tags = {"ivr", "account"},
            description = "Changes account password", owner = "account-team")
    static final Operator<DtmfInput, ActionResult> CHANGE_PASSWORD =
            actionOperator("change-password", "Password reset link sent");

    @OperatorMeta(layer = OperatorLayer.DOMAIN, tags = {"ivr", "account"},
            description = "Changes account plan", owner = "account-team")
    static final Operator<DtmfInput, ActionResult> CHANGE_PLAN =
            actionOperator("change-plan", "Plan change request submitted");

    @OperatorMeta(layer = OperatorLayer.DOMAIN, tags = {"ivr", "account"},
            description = "Activates additional service", owner = "account-team")
    static final Operator<DtmfInput, ActionResult> ACTIVATE_SERVICE =
            actionOperator("activate-service", "Service activation completed");

    @OperatorMeta(layer = OperatorLayer.DOMAIN, tags = {"ivr", "account"},
            description = "Closes account", owner = "account-team")
    static final Operator<DtmfInput, ActionResult> CLOSE_ACCOUNT =
            actionOperator("close-account", "Account closure workflow started");

    @OperatorMeta(layer = OperatorLayer.DOMAIN, tags = {"ivr", "insurance"},
            description = "Queries warranty coverage", owner = "insurance-team")
    static final Operator<DtmfInput, ActionResult> WARRANTY_INQUIRY =
            actionOperator("warranty-inquiry", "Warranty details displayed");

    @OperatorMeta(layer = OperatorLayer.DOMAIN, tags = {"ivr", "insurance"},
            description = "Checks claim status", owner = "insurance-team")
    static final Operator<DtmfInput, ActionResult> CLAIM_STATUS =
            actionOperator("claim-status", "Claim status updated");

    @OperatorMeta(layer = OperatorLayer.DOMAIN, tags = {"ivr", "insurance"},
            description = "Provides extended warranty info", owner = "insurance-team")
    static final Operator<DtmfInput, ActionResult> EXTENDED_WARRANTY =
            actionOperator("extended-warranty", "Extended warranty plans sent");

    @OperatorMeta(layer = OperatorLayer.DOMAIN, tags = {"ivr", "insurance"},
            description = "Provides warranty policy info", owner = "insurance-team")
    static final Operator<DtmfInput, ActionResult> WARRANTY_POLICY =
            actionOperator("warranty-policy", "Warranty policy terms read out");

    @OperatorMeta(layer = OperatorLayer.DOMAIN, tags = {"ivr", "insurance"},
            description = "Submits claim for device damage", owner = "insurance-team")
    static final Operator<DtmfInput, ActionResult> CLAIM_DEVICE_DAMAGE =
            actionOperator("claim-device-damage", "Device damage claim submitted");

    @OperatorMeta(layer = OperatorLayer.DOMAIN, tags = {"ivr", "insurance"},
            description = "Submits claim for accidental loss", owner = "insurance-team")
    static final Operator<DtmfInput, ActionResult> CLAIM_ACCIDENTAL_LOSS =
            actionOperator("claim-accidental-loss", "Accidental loss claim submitted");

    @OperatorMeta(layer = OperatorLayer.DOMAIN, tags = {"ivr", "insurance"},
            description = "Submits claim for battery issue", owner = "insurance-team")
    static final Operator<DtmfInput, ActionResult> CLAIM_BATTERY =
            actionOperator("claim-battery", "Battery claim submitted");

    @OperatorMeta(layer = OperatorLayer.DOMAIN, tags = {"ivr", "insurance"},
            description = "Submits claim for screen issue", owner = "insurance-team")
    static final Operator<DtmfInput, ActionResult> CLAIM_SCREEN =
            actionOperator("claim-screen", "Screen repair claim submitted");

    @OperatorMeta(layer = OperatorLayer.DOMAIN, tags = {"ivr", "insurance"},
            description = "Submits claim for other issue", owner = "insurance-team")
    static final Operator<DtmfInput, ActionResult> CLAIM_OTHER =
            actionOperator("claim-other", "Other claim submitted");

    // ── Orders chain operators ────────────────────────────────────────────────

    @OperatorMeta(layer = OperatorLayer.DOMAIN, tags = {"ivr", "orders"},
            description = "Views orders", owner = "orders-team")
    static final Operator<DtmfInput, ActionResult> VIEW_ORDERS =
            actionOperator("view-orders", "Latest order details read out");

    @OperatorMeta(layer = OperatorLayer.DOMAIN, tags = {"ivr", "orders"},
            description = "Processes returns/exchanges", owner = "orders-team")
    static final Operator<DtmfInput, ActionResult> RETURNS_EXCHANGES =
            actionOperator("returns-exchanges", "Return/exchange request opened");

    @OperatorMeta(layer = OperatorLayer.DOMAIN, tags = {"ivr", "orders"},
            description = "Checks shipping status", owner = "orders-team")
    static final Operator<DtmfInput, ActionResult> SHIPPING_STATUS =
            actionOperator("shipping-status", "Shipment status pushed to app");

    @OperatorMeta(layer = OperatorLayer.DOMAIN, tags = {"ivr", "orders"},
            description = "Confirms receipt", owner = "orders-team")
    static final Operator<DtmfInput, ActionResult> CONFIRM_RECEIPT =
            actionOperator("confirm-receipt", "Receipt confirmation completed");

    @OperatorMeta(layer = OperatorLayer.DOMAIN, tags = {"ivr", "orders"},
            description = "Handles invoice services", owner = "orders-team")
    static final Operator<DtmfInput, ActionResult> INVOICE_SERVICE =
            actionOperator("invoice-service", "Invoice service ticket created");

    // ── Complaints chain operators ────────────────────────────────────────────

    @OperatorMeta(layer = OperatorLayer.DOMAIN, tags = {"ivr", "complaint"},
            description = "Creates service quality complaint ticket", owner = "complaint-team")
    static final Operator<DtmfInput, ActionResult> SERVICE_QUALITY_COMPLAINT =
            actionOperator("service-quality-complaint", "Service quality complaint ticket created");

    @OperatorMeta(layer = OperatorLayer.DOMAIN, tags = {"ivr", "complaint"},
            description = "Creates billing complaint ticket", owner = "complaint-team")
    static final Operator<DtmfInput, ActionResult> BILLING_COMPLAINT =
            actionOperator("billing-complaint", "Billing complaint ticket created");

    @OperatorMeta(layer = OperatorLayer.DOMAIN, tags = {"ivr", "complaint"},
            description = "Creates network complaint ticket", owner = "complaint-team")
    static final Operator<DtmfInput, ActionResult> NETWORK_COMPLAINT =
            actionOperator("network-complaint", "Network complaint ticket created");

    @OperatorMeta(layer = OperatorLayer.DOMAIN, tags = {"ivr", "complaint"},
            description = "Creates staff complaint ticket", owner = "complaint-team")
    static final Operator<DtmfInput, ActionResult> STAFF_COMPLAINT =
            actionOperator("staff-complaint", "Staff behavior complaint ticket created");

    @OperatorMeta(layer = OperatorLayer.DOMAIN, tags = {"ivr", "complaint"},
            description = "Creates other complaint ticket", owner = "complaint-team")
    static final Operator<DtmfInput, ActionResult> OTHER_COMPLAINT =
            actionOperator("other-complaint", "Other complaint ticket created");

    // ── Sub-graph builders ────────────────────────────────────────────────────

    public static Graph buildDuplicateChargeSubGraph() {
        return Graph.builder("duplicate-charge-menu")
                .node("duplicateChargePrompt", GREETING)
                    .input((results, ctx) -> new TtsPrompt(
                            ctx.get("callId", String.class),
                            "重复扣费处理：1自动退款 2人工审核 3扣费记录 4下载账单 5转接主管"))
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
                        ActionResult action = results.get(selected, ActionResult.class);
                        return menuResult("duplicate-charge-menu",
                                List.of("duplicateCharge", selected),
                                action.action(), action.detail());
                    })
                .build();
    }

    public static Graph buildBillingDisputeSubGraph(SubGraphOperator duplicateChargeFlow) {
        return Graph.builder("billing-dispute-menu")
                .node("billingDisputePrompt", GREETING)
                    .input((results, ctx) -> new TtsPrompt(
                            ctx.get("callId", String.class),
                            "账单争议：1重复扣费 2未授权扣费 3金额错误 4退款进度 5其他争议"))
                .node("collectBillingDisputeInput", COLLECT_DTMF)
                    .dependsOn("billingDisputePrompt")
                    .input((results, ctx) -> menuInput(ctx, "billing-dispute-menu"))
                .node("duplicateChargeFlow", duplicateChargeFlow)
                    .dependsOn("collectBillingDisputeInput")
                    .input((results, ctx) -> Map.of(
                            "callId", ctx.get("callId", String.class),
                            "customerId", ctx.get("customerId", String.class)))
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
                                "processUnauthorizedCharge", "processWrongAmount",
                                "queryRefundStatus", "processOtherDispute");
                        ActionResult action = results.get(selected, ActionResult.class);
                        return menuResult("billing-dispute-menu",
                                List.of("billingDispute", selected),
                                action.action(), action.detail());
                    })
                .build();
    }

    public static Graph buildBillingSubGraph(SubGraphOperator billingDisputeFlow) {
        return Graph.builder("billing-menu")
                .node("billingPrompt", GREETING)
                    .input((results, ctx) -> new TtsPrompt(
                            ctx.get("callId", String.class),
                            "话费账单：1查看账单 2账单争议 3充值缴费 4套餐费用 5费用明细 6返回"))
                .node("collectBillingInput", COLLECT_DTMF)
                    .dependsOn("billingPrompt")
                    .input((results, ctx) -> menuInput(ctx, "billing-menu"))
                .node("viewBill", VIEW_BILL)
                    .dependsOn("collectBillingInput")
                    .input((results, ctx) -> menuInput(ctx, "billing-menu"))
                .node("billingDisputeFlow", billingDisputeFlow)
                    .dependsOn("collectBillingInput")
                    .input((results, ctx) -> Map.of(
                            "callId", ctx.get("callId", String.class),
                            "customerId", ctx.get("customerId", String.class)))
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
                    .dependsOn("viewBill", "billingDisputeFlow", "topUpPayment",
                            "planPricing", "feeBreakdown", "billingReturn")
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
                        ActionResult action = results.get(selected, ActionResult.class);
                        return menuResult("billing-menu",
                                List.of("billing", selected),
                                action.action(), action.detail());
                    })
                .build();
    }

    public static Graph buildCannotConnectSubGraph() {
        return Graph.builder("cannot-connect-menu")
                .node("cannotConnectPrompt", GREETING)
                    .input((results, ctx) -> new TtsPrompt(
                            ctx.get("callId", String.class),
                            "无法连接诊断：1重启设备 2检查线路 3远程诊断 4预约上门 5更换设备"))
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
                        ActionResult action = results.get(selected, ActionResult.class);
                        return menuResult("cannot-connect-menu",
                                List.of("cannotConnect", selected),
                                action.action(), action.detail());
                    })
                .build();
    }

    public static Graph buildNetworkIssueSubGraph(SubGraphOperator cannotConnectFlow) {
        return Graph.builder("network-issue-menu")
                .node("networkIssuePrompt", GREETING)
                    .input((results, ctx) -> new TtsPrompt(
                            ctx.get("callId", String.class),
                            "网络故障：1无法连接 2连接不稳 3网速慢 4DNS错误 5VPN问题"))
                .node("collectNetworkIssueInput", COLLECT_DTMF)
                    .dependsOn("networkIssuePrompt")
                    .input((results, ctx) -> menuInput(ctx, "network-issue-menu"))
                .node("cannotConnectFlow", cannotConnectFlow)
                    .dependsOn("collectNetworkIssueInput")
                    .input((results, ctx) -> Map.of(
                            "callId", ctx.get("callId", String.class),
                            "customerId", ctx.get("customerId", String.class)))
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
                        String selected = firstCompleted(results,
                                "intermittentFix", "slowSpeedFix", "dnsFix", "vpnFix");
                        ActionResult action = results.get(selected, ActionResult.class);
                        return menuResult("network-issue-menu",
                                List.of("networkIssues", selected),
                                action.action(), action.detail());
                    })
                .build();
    }

    public static Graph buildTechSupportSubGraph(SubGraphOperator networkIssueFlow) {
        return Graph.builder("tech-support-menu")
                .node("techSupportPrompt", GREETING)
                    .input((results, ctx) -> new TtsPrompt(
                            ctx.get("callId", String.class),
                            "技术支持：1网络故障 2设备问题 3应用故障 4安装服务 5网速测试 6返回"))
                .node("collectTechSupportInput", COLLECT_DTMF)
                    .dependsOn("techSupportPrompt")
                    .input((results, ctx) -> menuInput(ctx, "tech-support-menu"))
                .node("networkIssueFlow", networkIssueFlow)
                    .dependsOn("collectTechSupportInput")
                    .input((results, ctx) -> Map.of(
                            "callId", ctx.get("callId", String.class),
                            "customerId", ctx.get("customerId", String.class)))
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
                        ActionResult action = results.get(selected, ActionResult.class);
                        return menuResult("tech-support-menu",
                                List.of("techSupport", selected),
                                action.action(), action.detail());
                    })
                .build();
    }

    public static Graph buildSubmitClaimSubGraph() {
        return Graph.builder("submit-claim-menu")
                .node("submitClaimPrompt", GREETING)
                    .input((results, ctx) -> new TtsPrompt(
                            ctx.get("callId", String.class),
                            "提交理赔：1设备损坏 2意外丢失 3电池故障 4屏幕维修 5其他故障"))
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
                        ActionResult action = results.get(selected, ActionResult.class);
                        return menuResult("submit-claim-menu",
                                List.of("submitClaim", selected),
                                action.action(), action.detail());
                    })
                .build();
    }

    public static Graph buildWarrantyClaimsSubGraph(SubGraphOperator submitClaimFlow) {
        return Graph.builder("warranty-claims-menu")
                .node("warrantyClaimsPrompt", GREETING)
                    .input((results, ctx) -> new TtsPrompt(
                            ctx.get("callId", String.class),
                            "保修理赔：1保修查询 2提交理赔 3理赔进度 4延保服务 5保修政策"))
                .node("collectWarrantyClaimsInput", COLLECT_DTMF)
                    .dependsOn("warrantyClaimsPrompt")
                    .input((results, ctx) -> menuInput(ctx, "warranty-claims-menu"))
                .node("warrantyInquiry", WARRANTY_INQUIRY)
                    .dependsOn("collectWarrantyClaimsInput")
                    .input((results, ctx) -> menuInput(ctx, "warranty-claims-menu"))
                .node("submitClaimFlow", submitClaimFlow)
                    .dependsOn("collectWarrantyClaimsInput")
                    .input((results, ctx) -> Map.of(
                            "callId", ctx.get("callId", String.class),
                            "customerId", ctx.get("customerId", String.class)))
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
                        ActionResult action = results.get(selected, ActionResult.class);
                        return menuResult("warranty-claims-menu",
                                List.of("warrantyClaims", selected),
                                action.action(), action.detail());
                    })
                .build();
    }

    public static Graph buildAccountInsuranceSubGraph(SubGraphOperator warrantyClaimsFlow) {
        return Graph.builder("account-insurance-menu")
                .node("accountInsurancePrompt", GREETING)
                    .input((results, ctx) -> new TtsPrompt(
                            ctx.get("callId", String.class),
                            "账户保障：1修改密码 2套餐变更 3保修理赔 4开通服务 5账户注销 6返回"))
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
                    .input((results, ctx) -> Map.of(
                            "callId", ctx.get("callId", String.class),
                            "customerId", ctx.get("customerId", String.class)))
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
                        ActionResult action = results.get(selected, ActionResult.class);
                        return menuResult("account-insurance-menu",
                                List.of("accountInsurance", selected),
                                action.action(), action.detail());
                    })
                .build();
    }

    public static Graph buildOrdersSubGraph() {
        return Graph.builder("orders-menu")
                .node("ordersPrompt", GREETING)
                    .input((results, ctx) -> new TtsPrompt(
                            ctx.get("callId", String.class),
                            "订单物流：1查看订单 2退换货 3物流查询 4确认收货 5发票服务 6返回"))
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
                        ActionResult action = results.get(selected, ActionResult.class);
                        return menuResult("orders-menu",
                                List.of("ordersLogistics", selected),
                                action.action(), action.detail());
                    })
                .build();
    }

    public static Graph buildComplaintsSubGraph() {
        return Graph.builder("complaints-menu")
                .node("complaintsPrompt", GREETING)
                    .input((results, ctx) -> new TtsPrompt(
                            ctx.get("callId", String.class),
                            "投诉建议：1服务质量 2计费问题 3网络质量 4员工态度 5其他投诉 6返回"))
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
                        ActionResult action = results.get(selected, ActionResult.class);
                        return menuResult("complaints-menu",
                                List.of("complaints", selected),
                                action.action(), action.detail() + " + transferred to complaint specialist");
                    })
                .build();
    }

    // ── Main graph ─────────────────────────────────────────────────────────────

    public static Graph buildMainGraph(
            SubGraphOperator billingFlow,
            SubGraphOperator techFlow,
            SubGraphOperator accountFlow,
            SubGraphOperator ordersFlow,
            SubGraphOperator complaintsFlow
    ) {
        return Graph.builder("ivrCustomerService")
                .node("greeting", GREETING)
                    .input((results, ctx) -> new TtsPrompt(
                            ctx.get("callId", String.class),
                            "欢迎致电综合客服中心，正在为您连接智能语音系统。"))
                .node("identifyCustomer", IDENTIFY_CUSTOMER)
                    .dependsOn("greeting")
                    .input((results, ctx) -> ctx.get("customerId", String.class))
                .node("mainMenuPrompt", GREETING)
                    .dependsOn("identifyCustomer")
                    .input((results, ctx) -> new TtsPrompt(
                            ctx.get("callId", String.class),
                            "主菜单：1话费账单 2技术支持 3账户保障 4订单物流 5投诉建议 0人工座席"))
                .node("collectMainMenu", COLLECT_DTMF)
                    .dependsOn("mainMenuPrompt")
                    .input((results, ctx) -> menuInput(ctx, "main-menu"))
                .node("billingFlow", billingFlow)
                    .dependsOn("collectMainMenu")
                    .input((results, ctx) -> Map.of(
                            "callId", ctx.get("callId", String.class),
                            "customerId", ctx.get("customerId", String.class)))
                .node("techFlow", techFlow)
                    .dependsOn("collectMainMenu")
                    .input((results, ctx) -> Map.of(
                            "callId", ctx.get("callId", String.class),
                            "customerId", ctx.get("customerId", String.class)))
                .node("accountFlow", accountFlow)
                    .dependsOn("collectMainMenu")
                    .input((results, ctx) -> Map.of(
                            "callId", ctx.get("callId", String.class),
                            "customerId", ctx.get("customerId", String.class)))
                .node("ordersFlow", ordersFlow)
                    .dependsOn("collectMainMenu")
                    .input((results, ctx) -> Map.of(
                            "callId", ctx.get("callId", String.class),
                            "customerId", ctx.get("customerId", String.class)))
                .node("complaintsFlow", complaintsFlow)
                    .dependsOn("collectMainMenu")
                    .input((results, ctx) -> Map.of(
                            "callId", ctx.get("callId", String.class),
                            "customerId", ctx.get("customerId", String.class)))
                .node("transferLiveAgent", TRANSFER_AGENT)
                    .dependsOn("collectMainMenu")
                    .input((results, ctx) -> menuInput(ctx, "main-menu"))
                .branch("collectMainMenu")
                    .on("key")
                    .when(v -> "1".equals(v), "billingFlow")
                    .when(v -> "2".equals(v), "techFlow")
                    .when(v -> "3".equals(v), "accountFlow")
                    .when(v -> "4".equals(v), "ordersFlow")
                    .when(v -> "5".equals(v), "complaintsFlow")
                    .otherwise("transferLiveAgent")
                .node("satisfactionSurvey", SATISFACTION_SURVEY)
                    .dependsOn("billingFlow", "techFlow", "accountFlow", "ordersFlow", "complaintsFlow", "transferLiveAgent")
                    .input((results, ctx) -> {
                        Map<String, Object> outcome = resolveMainOutcome(results);
                        return Map.of(
                                "callId", ctx.get("callId", String.class),
                                "action", stringValue(outcome, "action", "unknown"),
                                "detail", stringValue(outcome, "detail", "unknown"));
                    })
                .node("callSummary", CALL_SUMMARY_BUILDER)
                    .dependsOn("satisfactionSurvey")
                    .input((results, ctx) -> {
                        Map<String, Object> outcome = resolveMainOutcome(results);
                        SurveyResult survey = results.get("satisfactionSurvey", SurveyResult.class);
                        List<String> path = new ArrayList<>(List.of("mainMenu"));
                        path.addAll(pathOf(outcome));
                        return Map.of(
                                "callId", ctx.get("callId", String.class),
                                "customerId", ctx.get("customerId", String.class),
                                "menuPath", path,
                                "resolution", stringValue(outcome, "action", "unknown")
                                        + " | " + stringValue(outcome, "detail", "unknown")
                                        + " | survey=" + survey.score());
                    })
                .node("saveCallRecord", SAVE_CALL_RECORD)
                    .dependsOn("callSummary")
                    .input((results, ctx) -> results.get("callSummary", CallSummary.class))
                .build();
    }

    // ── Entry point ───────────────────────────────────────────────────────────

    public static void main(String[] args) {
        var registry = new DefaultOperatorRegistry();
        registerAnnotatedOperators(registry);
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

        Graph graph = buildMainGraph(billingFlow, techFlow, accountFlow, ordersFlow, complaintsFlow);

        var engine = GraphEngine.builder()
                .registry(registry)
                .interceptors(List.of())
                .listeners(List.of(new LoggingListener()))
                .build();
        var ctx = new GraphContext(Map.of(
                "callId", "CALL-IVR-001",
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
        System.out.println("  IVR Customer Service Result (Fluent/SubGraph)");
        System.out.println("═══════════════════════════════════════════════");
        System.out.println("  Success : " + result.isSuccess());
        System.out.println("  Elapsed : " + result.elapsed().toMillis() + " ms");
        System.out.println();
        for (var entry : result.statusMap().entrySet()) {
            System.out.printf("  %-28s → %s%n", entry.getKey(), entry.getValue());
        }
        System.out.println();

        if (result.getStatus("callSummary") == NodeStatus.COMPLETED) {
            CallSummary summary = result.getOutput("callSummary", CallSummary.class);
            System.out.println("  Call summary : " + summary);
        }
        if (result.getStatus("satisfactionSurvey") == NodeStatus.COMPLETED) {
            SurveyResult survey = result.getOutput("satisfactionSurvey", SurveyResult.class);
            System.out.println("  Survey       : " + survey);
        }
        if (result.getStatus("saveCallRecord") == NodeStatus.COMPLETED) {
            SaveResult save = result.getOutput("saveCallRecord", SaveResult.class);
            System.out.println("  Record saved : " + save);
        }
        System.out.println("═══════════════════════════════════════════════");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static void simulateLatency() throws InterruptedException {
        Thread.sleep(ThreadLocalRandom.current().nextInt(20, 51));
    }

    private static Operator<DtmfInput, ActionResult> actionOperator(String action, String detail) {
        return (input, ctx) -> {
            simulateLatency();
            return new ActionResult(input.callId(), action, detail, true);
        };
    }

    private static DtmfInput menuInput(GraphContext ctx, String menuId) {
        return new DtmfInput(
                ctx.get("callId", String.class),
                ctx.get("customerId", String.class),
                menuId);
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

    private static Map<String, Object> resolveMainOutcome(NodeResults results) {
        if (results.hasResult("billingFlow")) {
            return unwrapSubGraphResult(results.getRaw("billingFlow"), "billingResult");
        }
        if (results.hasResult("techFlow")) {
            return unwrapSubGraphResult(results.getRaw("techFlow"), "techSupportResult");
        }
        if (results.hasResult("accountFlow")) {
            return unwrapSubGraphResult(results.getRaw("accountFlow"), "accountInsuranceResult");
        }
        if (results.hasResult("ordersFlow")) {
            return unwrapSubGraphResult(results.getRaw("ordersFlow"), "ordersResult");
        }
        if (results.hasResult("complaintsFlow")) {
            return unwrapSubGraphResult(results.getRaw("complaintsFlow"), "complaintsResult");
        }
        if (results.hasResult("transferLiveAgent")) {
            TransferResult transfer = results.get("transferLiveAgent", TransferResult.class);
            return menuResult(
                    "main-menu",
                    List.of("liveAgent"),
                    "transfer-live-agent",
                    transfer.queue() + ":" + transfer.agentId());
        }
        return menuResult("main-menu", List.of("unknown"), "unknown", "no-route");
    }

    private static void registerAnnotatedOperators(DefaultOperatorRegistry registry) {
        for (var field : IvrCustomerServiceExample.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) || !field.isAnnotationPresent(OperatorMeta.class)) {
                continue;
            }
            try {
                registry.registerRaw(field.getName(), field.get(null));
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("Cannot register operator field: " + field.getName(), e);
            }
        }
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
}

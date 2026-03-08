package com.leanowtech.bloge.examples.ivr;

import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.context.NodeResults;
import com.leanowtech.bloge.core.engine.GraphEngine;
import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.model.NodeStatus;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.examples.common.LoggingListener;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.leanowtech.bloge.examples.ivr.IvrCustomerServiceExample.*;

/**
 * Comprehensive IVR customer service modeled as a single flat Fluent graph.
 *
 * <p>Same business menu as {@link IvrCustomerServiceExample}, but all levels are declared
 * in one graph definition to show the maintenance cost of the flat style.
 *
 * <pre>
 * mainMenu
 * ├─ billing → billingDispute → duplicateCharge
 * ├─ techSupport → networkIssues → cannotConnect
 * ├─ accountInsurance → warrantyClaims → submitClaim
 * ├─ ordersLogistics
 * ├─ complaints
 * └─ liveAgent
 * </pre>
 *
 * @see IvrCustomerServiceExample
 * @see IvrCustomerServiceDslExample
 * @see IvrCustomerServiceFlatDslExample
 */
@SuppressWarnings({"unchecked", "preview"})
public class IvrCustomerServiceFlatExample {

    // ── Flat Graph Trade-offs ──────────────────────────────────────
    // All ~70 nodes in one graph — mirrors traditional IVR "flat config" approach.
    // Compare with IvrCustomerServiceExample which uses SubGraph composition.
    // Flat approach: simpler mental model, but harder to maintain at scale.
    // SubGraph approach: modular, testable, reusable, but requires SubGraphOperator wiring.

    private static final List<String> LEAF_NODES = List.of(
            "l1ViewBill", "l1TopUpPayment", "l1PlanPricing", "l1FeeBreakdown", "l1BillingReturn",
            "l2UnauthorizedCharge", "l2WrongAmount", "l2RefundStatus", "l2OtherDispute",
            "l3AutoRefund", "l3ManualReview", "l3ChargeHistory", "l3DownloadStatement", "l3TransferSupervisor",

            "l1DeviceIssue", "l1SoftwareHelp", "l1ScheduleInstall", "l1RunSpeedTest", "l1TechReturn",
            "l2IntermittentFix", "l2SlowSpeedFix", "l2DnsFix", "l2VpnFix",
            "l3RestartDevice", "l3CheckLine", "l3RemoteDiagnostic", "l3ScheduleOnsite", "l3DeviceReplacement",

            "l1ChangePassword", "l1ChangePlan", "l1ActivateService", "l1CloseAccount", "l1AccountReturn",
            "l2WarrantyInquiry", "l2ClaimStatus", "l2ExtendedWarranty", "l2WarrantyPolicy",
            "l3ClaimDeviceDamage", "l3ClaimAccidentalLoss", "l3ClaimBattery", "l3ClaimScreen", "l3ClaimOther",

            "l1ViewOrders", "l1ReturnsExchanges", "l1ShippingStatus", "l1ConfirmReceipt", "l1InvoiceService", "l1OrdersReturn",
            "l1ServiceQualityComplaint", "l1BillingComplaint", "l1NetworkComplaint", "l1StaffComplaint", "l1OtherComplaint", "l1ComplaintsReturn"
    );

    private static final Map<String, List<String>> LEAF_PATHS = buildLeafPaths();

    public static Graph buildGraph() {
        return Graph.builder("ivrCustomerServiceFlat")
                // Level 0
                .node("l0Greeting", GREETING)
                    .input((results, ctx) -> new TtsPrompt(
                            ctx.get("callId", String.class),
                            "欢迎致电综合客服中心，正在为您连接智能语音系统。"))
                .node("l0IdentifyCustomer", IDENTIFY_CUSTOMER)
                    .dependsOn("l0Greeting")
                    .input((results, ctx) -> ctx.get("customerId", String.class))
                .node("l0MainMenuPrompt", GREETING)
                    .dependsOn("l0IdentifyCustomer")
                    .input((results, ctx) -> new TtsPrompt(
                            ctx.get("callId", String.class),
                            "主菜单：1话费账单 2技术支持 3账户保障 4订单物流 5投诉建议 0人工座席"))
                .node("collectMainMenu", COLLECT_DTMF)
                    .dependsOn("l0MainMenuPrompt")
                    .input((results, ctx) -> new DtmfInput(
                            ctx.get("callId", String.class),
                            ctx.get("customerId", String.class),
                            "main-menu"))

                // L1 Billing chain
                .node("l1BillingPrompt", GREETING)
                    .dependsOn("collectMainMenu")
                    .input((results, ctx) -> new TtsPrompt(
                            ctx.get("callId", String.class),
                            "话费账单：1查看账单 2账单争议 3充值缴费 4套餐费用 5费用明细 6返回"))
                .node("collectBillingInput", COLLECT_DTMF)
                    .dependsOn("l1BillingPrompt")
                    .input((results, ctx) -> new DtmfInput(
                            ctx.get("callId", String.class),
                            ctx.get("customerId", String.class),
                            "billing-menu"))
                .node("l1ViewBill", VIEW_BILL)
                    .dependsOn("collectBillingInput")
                    .input((results, ctx) -> dtmf(ctx, "billing-menu"))
                .node("l1TopUpPayment", TOP_UP_PAYMENT)
                    .dependsOn("collectBillingInput")
                    .input((results, ctx) -> dtmf(ctx, "billing-menu"))
                .node("l1PlanPricing", PLAN_PRICING)
                    .dependsOn("collectBillingInput")
                    .input((results, ctx) -> dtmf(ctx, "billing-menu"))
                .node("l1FeeBreakdown", FEE_BREAKDOWN)
                    .dependsOn("collectBillingInput")
                    .input((results, ctx) -> dtmf(ctx, "billing-menu"))
                .node("l1BillingReturn", RETURN_PREVIOUS_MENU)
                    .dependsOn("collectBillingInput")
                    .input((results, ctx) -> dtmf(ctx, "billing-menu"))
                .branch("collectBillingInput")
                    .on("key")
                    .when(v -> "1".equals(v), "l1ViewBill")
                    .when(v -> "2".equals(v), "l2BillingDisputePrompt")
                    .when(v -> "3".equals(v), "l1TopUpPayment")
                    .when(v -> "4".equals(v), "l1PlanPricing")
                    .when(v -> "5".equals(v), "l1FeeBreakdown")
                    .otherwise("l1BillingReturn")

                // L2 Billing dispute
                .node("l2BillingDisputePrompt", GREETING)
                    .dependsOn("collectBillingInput")
                    .input((results, ctx) -> new TtsPrompt(
                            ctx.get("callId", String.class),
                            "账单争议：1重复扣费 2未授权扣费 3金额错误 4退款进度 5其他争议"))
                .node("collectDisputeInput", COLLECT_DTMF)
                    .dependsOn("l2BillingDisputePrompt")
                    .input((results, ctx) -> dtmf(ctx, "billing-dispute-menu"))
                .node("l2UnauthorizedCharge", PROCESS_UNAUTHORIZED_CHARGE)
                    .dependsOn("collectDisputeInput")
                    .input((results, ctx) -> dtmf(ctx, "billing-dispute-menu"))
                .node("l2WrongAmount", PROCESS_WRONG_AMOUNT)
                    .dependsOn("collectDisputeInput")
                    .input((results, ctx) -> dtmf(ctx, "billing-dispute-menu"))
                .node("l2RefundStatus", QUERY_REFUND_STATUS)
                    .dependsOn("collectDisputeInput")
                    .input((results, ctx) -> dtmf(ctx, "billing-dispute-menu"))
                .node("l2OtherDispute", PROCESS_OTHER_DISPUTE)
                    .dependsOn("collectDisputeInput")
                    .input((results, ctx) -> dtmf(ctx, "billing-dispute-menu"))
                .branch("collectDisputeInput")
                    .on("key")
                    .when(v -> "1".equals(v), "l3DuplicateChargePrompt")
                    .when(v -> "2".equals(v), "l2UnauthorizedCharge")
                    .when(v -> "3".equals(v), "l2WrongAmount")
                    .when(v -> "4".equals(v), "l2RefundStatus")
                    .otherwise("l2OtherDispute")

                // L3 Billing duplicate charge resolution
                .node("l3DuplicateChargePrompt", GREETING)
                    .dependsOn("collectDisputeInput")
                    .input((results, ctx) -> new TtsPrompt(
                            ctx.get("callId", String.class),
                            "重复扣费处理：1自动退款 2人工审核 3扣费记录 4下载账单 5转接主管"))
                .node("collectResolutionInput", COLLECT_DTMF)
                    .dependsOn("l3DuplicateChargePrompt")
                    .input((results, ctx) -> dtmf(ctx, "duplicate-charge-menu"))
                .node("l3AutoRefund", AUTO_REFUND)
                    .dependsOn("collectResolutionInput")
                    .input((results, ctx) -> dtmf(ctx, "duplicate-charge-menu"))
                .node("l3ManualReview", MANUAL_REVIEW)
                    .dependsOn("collectResolutionInput")
                    .input((results, ctx) -> dtmf(ctx, "duplicate-charge-menu"))
                .node("l3ChargeHistory", VIEW_CHARGE_HISTORY)
                    .dependsOn("collectResolutionInput")
                    .input((results, ctx) -> dtmf(ctx, "duplicate-charge-menu"))
                .node("l3DownloadStatement", DOWNLOAD_STATEMENT)
                    .dependsOn("collectResolutionInput")
                    .input((results, ctx) -> dtmf(ctx, "duplicate-charge-menu"))
                .node("l3TransferSupervisor", TRANSFER_SUPERVISOR)
                    .dependsOn("collectResolutionInput")
                    .input((results, ctx) -> dtmf(ctx, "duplicate-charge-menu"))
                .branch("collectResolutionInput")
                    .on("key")
                    .when(v -> "1".equals(v), "l3AutoRefund")
                    .when(v -> "2".equals(v), "l3ManualReview")
                    .when(v -> "3".equals(v), "l3ChargeHistory")
                    .when(v -> "4".equals(v), "l3DownloadStatement")
                    .otherwise("l3TransferSupervisor")

                // L1 Tech support chain
                .node("l1TechPrompt", GREETING)
                    .dependsOn("collectMainMenu")
                    .input((results, ctx) -> new TtsPrompt(
                            ctx.get("callId", String.class),
                            "技术支持：1网络故障 2设备问题 3应用故障 4安装服务 5网速测试 6返回"))
                .node("collectTechInput", COLLECT_DTMF)
                    .dependsOn("l1TechPrompt")
                    .input((results, ctx) -> dtmf(ctx, "tech-support-menu"))
                .node("l1DeviceIssue", DEVICE_ISSUE)
                    .dependsOn("collectTechInput")
                    .input((results, ctx) -> dtmf(ctx, "tech-support-menu"))
                .node("l1SoftwareHelp", SOFTWARE_HELP)
                    .dependsOn("collectTechInput")
                    .input((results, ctx) -> dtmf(ctx, "tech-support-menu"))
                .node("l1ScheduleInstall", SCHEDULE_INSTALL)
                    .dependsOn("collectTechInput")
                    .input((results, ctx) -> dtmf(ctx, "tech-support-menu"))
                .node("l1RunSpeedTest", RUN_SPEED_TEST)
                    .dependsOn("collectTechInput")
                    .input((results, ctx) -> dtmf(ctx, "tech-support-menu"))
                .node("l1TechReturn", RETURN_PREVIOUS_MENU)
                    .dependsOn("collectTechInput")
                    .input((results, ctx) -> dtmf(ctx, "tech-support-menu"))
                .branch("collectTechInput")
                    .on("key")
                    .when(v -> "1".equals(v), "l2NetworkPrompt")
                    .when(v -> "2".equals(v), "l1DeviceIssue")
                    .when(v -> "3".equals(v), "l1SoftwareHelp")
                    .when(v -> "4".equals(v), "l1ScheduleInstall")
                    .when(v -> "5".equals(v), "l1RunSpeedTest")
                    .otherwise("l1TechReturn")

                // L2 Network issues
                .node("l2NetworkPrompt", GREETING)
                    .dependsOn("collectTechInput")
                    .input((results, ctx) -> new TtsPrompt(
                            ctx.get("callId", String.class),
                            "网络故障：1无法连接 2连接不稳 3网速慢 4DNS错误 5VPN问题"))
                .node("collectNetworkInput", COLLECT_DTMF)
                    .dependsOn("l2NetworkPrompt")
                    .input((results, ctx) -> dtmf(ctx, "network-issue-menu"))
                .node("l2IntermittentFix", INTERMITTENT_FIX)
                    .dependsOn("collectNetworkInput")
                    .input((results, ctx) -> dtmf(ctx, "network-issue-menu"))
                .node("l2SlowSpeedFix", SLOW_SPEED_FIX)
                    .dependsOn("collectNetworkInput")
                    .input((results, ctx) -> dtmf(ctx, "network-issue-menu"))
                .node("l2DnsFix", DNS_FIX)
                    .dependsOn("collectNetworkInput")
                    .input((results, ctx) -> dtmf(ctx, "network-issue-menu"))
                .node("l2VpnFix", VPN_FIX)
                    .dependsOn("collectNetworkInput")
                    .input((results, ctx) -> dtmf(ctx, "network-issue-menu"))
                .branch("collectNetworkInput")
                    .on("key")
                    .when(v -> "1".equals(v), "l3CannotConnectPrompt")
                    .when(v -> "2".equals(v), "l2IntermittentFix")
                    .when(v -> "3".equals(v), "l2SlowSpeedFix")
                    .when(v -> "4".equals(v), "l2DnsFix")
                    .otherwise("l2VpnFix")

                // L3 Cannot connect diagnostics
                .node("l3CannotConnectPrompt", GREETING)
                    .dependsOn("collectNetworkInput")
                    .input((results, ctx) -> new TtsPrompt(
                            ctx.get("callId", String.class),
                            "无法连接诊断：1重启设备 2检查线路 3远程诊断 4预约上门 5更换设备"))
                .node("collectCannotConnectInput", COLLECT_DTMF)
                    .dependsOn("l3CannotConnectPrompt")
                    .input((results, ctx) -> dtmf(ctx, "cannot-connect-menu"))
                .node("l3RestartDevice", RESTART_DEVICE)
                    .dependsOn("collectCannotConnectInput")
                    .input((results, ctx) -> dtmf(ctx, "cannot-connect-menu"))
                .node("l3CheckLine", CHECK_LINE)
                    .dependsOn("collectCannotConnectInput")
                    .input((results, ctx) -> dtmf(ctx, "cannot-connect-menu"))
                .node("l3RemoteDiagnostic", REMOTE_DIAGNOSTIC)
                    .dependsOn("collectCannotConnectInput")
                    .input((results, ctx) -> dtmf(ctx, "cannot-connect-menu"))
                .node("l3ScheduleOnsite", SCHEDULE_ONSITE)
                    .dependsOn("collectCannotConnectInput")
                    .input((results, ctx) -> dtmf(ctx, "cannot-connect-menu"))
                .node("l3DeviceReplacement", DEVICE_REPLACEMENT)
                    .dependsOn("collectCannotConnectInput")
                    .input((results, ctx) -> dtmf(ctx, "cannot-connect-menu"))
                .branch("collectCannotConnectInput")
                    .on("key")
                    .when(v -> "1".equals(v), "l3RestartDevice")
                    .when(v -> "2".equals(v), "l3CheckLine")
                    .when(v -> "3".equals(v), "l3RemoteDiagnostic")
                    .when(v -> "4".equals(v), "l3ScheduleOnsite")
                    .otherwise("l3DeviceReplacement")

                // L1 Account & insurance chain
                .node("l1AccountPrompt", GREETING)
                    .dependsOn("collectMainMenu")
                    .input((results, ctx) -> new TtsPrompt(
                            ctx.get("callId", String.class),
                            "账户保障：1修改密码 2套餐变更 3保修理赔 4开通服务 5账户注销 6返回"))
                .node("collectAccountInput", COLLECT_DTMF)
                    .dependsOn("l1AccountPrompt")
                    .input((results, ctx) -> dtmf(ctx, "account-insurance-menu"))
                .node("l1ChangePassword", CHANGE_PASSWORD)
                    .dependsOn("collectAccountInput")
                    .input((results, ctx) -> dtmf(ctx, "account-insurance-menu"))
                .node("l1ChangePlan", CHANGE_PLAN)
                    .dependsOn("collectAccountInput")
                    .input((results, ctx) -> dtmf(ctx, "account-insurance-menu"))
                .node("l1ActivateService", ACTIVATE_SERVICE)
                    .dependsOn("collectAccountInput")
                    .input((results, ctx) -> dtmf(ctx, "account-insurance-menu"))
                .node("l1CloseAccount", CLOSE_ACCOUNT)
                    .dependsOn("collectAccountInput")
                    .input((results, ctx) -> dtmf(ctx, "account-insurance-menu"))
                .node("l1AccountReturn", RETURN_PREVIOUS_MENU)
                    .dependsOn("collectAccountInput")
                    .input((results, ctx) -> dtmf(ctx, "account-insurance-menu"))
                .branch("collectAccountInput")
                    .on("key")
                    .when(v -> "1".equals(v), "l1ChangePassword")
                    .when(v -> "2".equals(v), "l1ChangePlan")
                    .when(v -> "3".equals(v), "l2WarrantyPrompt")
                    .when(v -> "4".equals(v), "l1ActivateService")
                    .when(v -> "5".equals(v), "l1CloseAccount")
                    .otherwise("l1AccountReturn")

                // L2 Warranty claims
                .node("l2WarrantyPrompt", GREETING)
                    .dependsOn("collectAccountInput")
                    .input((results, ctx) -> new TtsPrompt(
                            ctx.get("callId", String.class),
                            "保修理赔：1保修查询 2提交理赔 3理赔进度 4延保服务 5保修政策"))
                .node("collectWarrantyInput", COLLECT_DTMF)
                    .dependsOn("l2WarrantyPrompt")
                    .input((results, ctx) -> dtmf(ctx, "warranty-claims-menu"))
                .node("l2WarrantyInquiry", WARRANTY_INQUIRY)
                    .dependsOn("collectWarrantyInput")
                    .input((results, ctx) -> dtmf(ctx, "warranty-claims-menu"))
                .node("l2ClaimStatus", CLAIM_STATUS)
                    .dependsOn("collectWarrantyInput")
                    .input((results, ctx) -> dtmf(ctx, "warranty-claims-menu"))
                .node("l2ExtendedWarranty", EXTENDED_WARRANTY)
                    .dependsOn("collectWarrantyInput")
                    .input((results, ctx) -> dtmf(ctx, "warranty-claims-menu"))
                .node("l2WarrantyPolicy", WARRANTY_POLICY)
                    .dependsOn("collectWarrantyInput")
                    .input((results, ctx) -> dtmf(ctx, "warranty-claims-menu"))
                .branch("collectWarrantyInput")
                    .on("key")
                    .when(v -> "1".equals(v), "l2WarrantyInquiry")
                    .when(v -> "2".equals(v), "l3SubmitClaimPrompt")
                    .when(v -> "3".equals(v), "l2ClaimStatus")
                    .when(v -> "4".equals(v), "l2ExtendedWarranty")
                    .otherwise("l2WarrantyPolicy")

                // L3 Submit claim
                .node("l3SubmitClaimPrompt", GREETING)
                    .dependsOn("collectWarrantyInput")
                    .input((results, ctx) -> new TtsPrompt(
                            ctx.get("callId", String.class),
                            "提交理赔：1设备损坏 2意外丢失 3电池故障 4屏幕维修 5其他故障"))
                .node("collectSubmitClaimInput", COLLECT_DTMF)
                    .dependsOn("l3SubmitClaimPrompt")
                    .input((results, ctx) -> dtmf(ctx, "submit-claim-menu"))
                .node("l3ClaimDeviceDamage", CLAIM_DEVICE_DAMAGE)
                    .dependsOn("collectSubmitClaimInput")
                    .input((results, ctx) -> dtmf(ctx, "submit-claim-menu"))
                .node("l3ClaimAccidentalLoss", CLAIM_ACCIDENTAL_LOSS)
                    .dependsOn("collectSubmitClaimInput")
                    .input((results, ctx) -> dtmf(ctx, "submit-claim-menu"))
                .node("l3ClaimBattery", CLAIM_BATTERY)
                    .dependsOn("collectSubmitClaimInput")
                    .input((results, ctx) -> dtmf(ctx, "submit-claim-menu"))
                .node("l3ClaimScreen", CLAIM_SCREEN)
                    .dependsOn("collectSubmitClaimInput")
                    .input((results, ctx) -> dtmf(ctx, "submit-claim-menu"))
                .node("l3ClaimOther", CLAIM_OTHER)
                    .dependsOn("collectSubmitClaimInput")
                    .input((results, ctx) -> dtmf(ctx, "submit-claim-menu"))
                .branch("collectSubmitClaimInput")
                    .on("key")
                    .when(v -> "1".equals(v), "l3ClaimDeviceDamage")
                    .when(v -> "2".equals(v), "l3ClaimAccidentalLoss")
                    .when(v -> "3".equals(v), "l3ClaimBattery")
                    .when(v -> "4".equals(v), "l3ClaimScreen")
                    .otherwise("l3ClaimOther")

                // L1 Orders chain (simplified terminal)
                .node("l1OrdersPrompt", GREETING)
                    .dependsOn("collectMainMenu")
                    .input((results, ctx) -> new TtsPrompt(
                            ctx.get("callId", String.class),
                            "订单物流：1查看订单 2退换货 3物流查询 4确认收货 5发票服务 6返回"))
                .node("collectOrdersInput", COLLECT_DTMF)
                    .dependsOn("l1OrdersPrompt")
                    .input((results, ctx) -> dtmf(ctx, "orders-menu"))
                .node("l1ViewOrders", VIEW_ORDERS)
                    .dependsOn("collectOrdersInput")
                    .input((results, ctx) -> dtmf(ctx, "orders-menu"))
                .node("l1ReturnsExchanges", RETURNS_EXCHANGES)
                    .dependsOn("collectOrdersInput")
                    .input((results, ctx) -> dtmf(ctx, "orders-menu"))
                .node("l1ShippingStatus", SHIPPING_STATUS)
                    .dependsOn("collectOrdersInput")
                    .input((results, ctx) -> dtmf(ctx, "orders-menu"))
                .node("l1ConfirmReceipt", CONFIRM_RECEIPT)
                    .dependsOn("collectOrdersInput")
                    .input((results, ctx) -> dtmf(ctx, "orders-menu"))
                .node("l1InvoiceService", INVOICE_SERVICE)
                    .dependsOn("collectOrdersInput")
                    .input((results, ctx) -> dtmf(ctx, "orders-menu"))
                .node("l1OrdersReturn", RETURN_PREVIOUS_MENU)
                    .dependsOn("collectOrdersInput")
                    .input((results, ctx) -> dtmf(ctx, "orders-menu"))
                .branch("collectOrdersInput")
                    .on("key")
                    .when(v -> "1".equals(v), "l1ViewOrders")
                    .when(v -> "2".equals(v), "l1ReturnsExchanges")
                    .when(v -> "3".equals(v), "l1ShippingStatus")
                    .when(v -> "4".equals(v), "l1ConfirmReceipt")
                    .when(v -> "5".equals(v), "l1InvoiceService")
                    .otherwise("l1OrdersReturn")

                // L1 Complaints chain (simplified terminal)
                .node("l1ComplaintsPrompt", GREETING)
                    .dependsOn("collectMainMenu")
                    .input((results, ctx) -> new TtsPrompt(
                            ctx.get("callId", String.class),
                            "投诉建议：1服务质量 2计费问题 3网络质量 4员工态度 5其他投诉 6返回"))
                .node("collectComplaintsInput", COLLECT_DTMF)
                    .dependsOn("l1ComplaintsPrompt")
                    .input((results, ctx) -> dtmf(ctx, "complaints-menu"))
                .node("l1ServiceQualityComplaint", SERVICE_QUALITY_COMPLAINT)
                    .dependsOn("collectComplaintsInput")
                    .input((results, ctx) -> dtmf(ctx, "complaints-menu"))
                .node("l1BillingComplaint", BILLING_COMPLAINT)
                    .dependsOn("collectComplaintsInput")
                    .input((results, ctx) -> dtmf(ctx, "complaints-menu"))
                .node("l1NetworkComplaint", NETWORK_COMPLAINT)
                    .dependsOn("collectComplaintsInput")
                    .input((results, ctx) -> dtmf(ctx, "complaints-menu"))
                .node("l1StaffComplaint", STAFF_COMPLAINT)
                    .dependsOn("collectComplaintsInput")
                    .input((results, ctx) -> dtmf(ctx, "complaints-menu"))
                .node("l1OtherComplaint", OTHER_COMPLAINT)
                    .dependsOn("collectComplaintsInput")
                    .input((results, ctx) -> dtmf(ctx, "complaints-menu"))
                .node("l1ComplaintsReturn", RETURN_PREVIOUS_MENU)
                    .dependsOn("collectComplaintsInput")
                    .input((results, ctx) -> dtmf(ctx, "complaints-menu"))
                .branch("collectComplaintsInput")
                    .on("key")
                    .when(v -> "1".equals(v), "l1ServiceQualityComplaint")
                    .when(v -> "2".equals(v), "l1BillingComplaint")
                    .when(v -> "3".equals(v), "l1NetworkComplaint")
                    .when(v -> "4".equals(v), "l1StaffComplaint")
                    .when(v -> "5".equals(v), "l1OtherComplaint")
                    .otherwise("l1ComplaintsReturn")

                // Main menu branch
                .node("transferLiveAgent", TRANSFER_AGENT)
                    .dependsOn("collectMainMenu")
                    .input((results, ctx) -> dtmf(ctx, "main-menu"))
                .branch("collectMainMenu")
                    .on("key")
                    .when(v -> "1".equals(v), "l1BillingPrompt")
                    .when(v -> "2".equals(v), "l1TechPrompt")
                    .when(v -> "3".equals(v), "l1AccountPrompt")
                    .when(v -> "4".equals(v), "l1OrdersPrompt")
                    .when(v -> "5".equals(v), "l1ComplaintsPrompt")
                    .otherwise("transferLiveAgent")

                // Final aggregation
                .node("satisfactionSurvey", SATISFACTION_SURVEY)
                    .dependsOn(dependsWithTransfer())
                    .input((results, ctx) -> {
                        String action = resolveLeafAction(results, ctx);
                        return Map.of(
                                "callId", ctx.get("callId", String.class),
                                "action", action,
                                "selectedKey", results.get("collectMainMenu", DtmfResult.class).key());
                    })
                .node("callSummary", CALL_SUMMARY_BUILDER)
                    .dependsOn("satisfactionSurvey")
                    .input((results, ctx) -> {
                        String leaf = resolveLeafNode(results, ctx);
                        List<String> path = new ArrayList<>(List.of("mainMenu"));
                        String resolution = "transfer-live-agent";
                        if (leaf != null) {
                            path.addAll(LEAF_PATHS.getOrDefault(leaf, List.of("unknown")));
                            ActionResult leafResult = results.get(leaf, ActionResult.class);
                            resolution = leafResult.action() + " | " + leafResult.detail();
                        } else if (results.hasResult("transferLiveAgent")) {
                            TransferResult transfer = results.get("transferLiveAgent", TransferResult.class);
                            path.add("liveAgent");
                            resolution = transfer.queue() + ":" + transfer.agentId();
                        }
                        SurveyResult survey = results.get("satisfactionSurvey", SurveyResult.class);
                        return Map.of(
                                "callId", ctx.get("callId", String.class),
                                "customerId", ctx.get("customerId", String.class),
                                "menuPath", path,
                                "resolution", resolution + " | survey=" + survey.score());
                    })
                .node("saveCallRecord", SAVE_CALL_RECORD)
                    .dependsOn("callSummary")
                    .input((results, ctx) -> results.get("callSummary", CallSummary.class))
                .build();
    }

    public static void main(String[] args) {
        var registry = new DefaultOperatorRegistry();
        registerNodeAliases(registry);

        Graph graph = buildGraph();
        var engine = GraphEngine.builder()
                .registry(registry)
                .interceptors(List.of())
                .listeners(List.of(new LoggingListener()))
                .build();

        var ctx = new GraphContext(Map.of(
                "callId", "CALL-IVR-FLAT-001",
                "customerId", "CUST-9001",
                "callerPhone", "+86-139-8888-1001",
                "simulatedKeys", Map.of(
                        "main-menu", "2",
                        "tech-support-menu", "1",
                        "network-issue-menu", "1",
                        "cannot-connect-menu", "3"
                )
        ));

        GraphResult result = engine.execute(graph, ctx);

        System.out.println("\n═══════════════════════════════════════════════");
        System.out.println("  IVR Customer Service Result (Fluent/Flat)");
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
        if (result.getStatus("saveCallRecord") == NodeStatus.COMPLETED) {
            SaveResult save = result.getOutput("saveCallRecord", SaveResult.class);
            System.out.println("  Record saved : " + save);
        }
        System.out.println("═══════════════════════════════════════════════");
    }

    private static DtmfInput dtmf(GraphContext ctx, String menuId) {
        return new DtmfInput(ctx.get("callId", String.class), ctx.get("customerId", String.class), menuId);
    }

    private static String[] dependsWithTransfer() {
        List<String> all = new ArrayList<>(LEAF_NODES);
        all.add("transferLiveAgent");
        return all.toArray(String[]::new);
    }

    private static String resolveLeafNode(NodeResults results, GraphContext ctx) {
        String expected = expectedLeafNode(ctx);
        if (expected != null && results.hasResult(expected)) {
            return expected;
        }
        for (String node : LEAF_NODES) {
            if (results.hasResult(node)) {
                return node;
            }
        }
        return null;
    }

    private static String resolveLeafAction(NodeResults results, GraphContext ctx) {
        String leaf = resolveLeafNode(results, ctx);
        if (leaf == null) {
            return "transfer-live-agent";
        }
        return results.get(leaf, ActionResult.class).action();
    }

    private static String expectedLeafNode(GraphContext ctx) {
        Object raw = ctx.get("simulatedKeys");
        if (!(raw instanceof Map<?, ?> map)) {
            return null;
        }
        Map<String, String> keys = new LinkedHashMap<>();
        for (var entry : map.entrySet()) {
            keys.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
        }
        String main = keys.getOrDefault("main-menu", "0");
        return switch (main) {
            case "1" -> switch (keys.getOrDefault("billing-menu", "1")) {
                case "2" -> switch (keys.getOrDefault("billing-dispute-menu", "5")) {
                    case "1" -> switch (keys.getOrDefault("duplicate-charge-menu", "5")) {
                        case "1" -> "l3AutoRefund";
                        case "2" -> "l3ManualReview";
                        case "3" -> "l3ChargeHistory";
                        case "4" -> "l3DownloadStatement";
                        default -> "l3TransferSupervisor";
                    };
                    case "2" -> "l2UnauthorizedCharge";
                    case "3" -> "l2WrongAmount";
                    case "4" -> "l2RefundStatus";
                    default -> "l2OtherDispute";
                };
                case "3" -> "l1TopUpPayment";
                case "4" -> "l1PlanPricing";
                case "5" -> "l1FeeBreakdown";
                case "6" -> "l1BillingReturn";
                default -> "l1ViewBill";
            };
            case "2" -> switch (keys.getOrDefault("tech-support-menu", "1")) {
                case "1" -> switch (keys.getOrDefault("network-issue-menu", "5")) {
                    case "1" -> switch (keys.getOrDefault("cannot-connect-menu", "5")) {
                        case "1" -> "l3RestartDevice";
                        case "2" -> "l3CheckLine";
                        case "3" -> "l3RemoteDiagnostic";
                        case "4" -> "l3ScheduleOnsite";
                        default -> "l3DeviceReplacement";
                    };
                    case "2" -> "l2IntermittentFix";
                    case "3" -> "l2SlowSpeedFix";
                    case "4" -> "l2DnsFix";
                    default -> "l2VpnFix";
                };
                case "2" -> "l1DeviceIssue";
                case "3" -> "l1SoftwareHelp";
                case "4" -> "l1ScheduleInstall";
                case "5" -> "l1RunSpeedTest";
                default -> "l1TechReturn";
            };
            case "3" -> switch (keys.getOrDefault("account-insurance-menu", "1")) {
                case "3" -> switch (keys.getOrDefault("warranty-claims-menu", "5")) {
                    case "2" -> switch (keys.getOrDefault("submit-claim-menu", "5")) {
                        case "1" -> "l3ClaimDeviceDamage";
                        case "2" -> "l3ClaimAccidentalLoss";
                        case "3" -> "l3ClaimBattery";
                        case "4" -> "l3ClaimScreen";
                        default -> "l3ClaimOther";
                    };
                    case "1" -> "l2WarrantyInquiry";
                    case "3" -> "l2ClaimStatus";
                    case "4" -> "l2ExtendedWarranty";
                    default -> "l2WarrantyPolicy";
                };
                case "2" -> "l1ChangePlan";
                case "4" -> "l1ActivateService";
                case "5" -> "l1CloseAccount";
                case "6" -> "l1AccountReturn";
                default -> "l1ChangePassword";
            };
            case "4" -> switch (keys.getOrDefault("orders-menu", "6")) {
                case "1" -> "l1ViewOrders";
                case "2" -> "l1ReturnsExchanges";
                case "3" -> "l1ShippingStatus";
                case "4" -> "l1ConfirmReceipt";
                case "5" -> "l1InvoiceService";
                default -> "l1OrdersReturn";
            };
            case "5" -> switch (keys.getOrDefault("complaints-menu", "6")) {
                case "1" -> "l1ServiceQualityComplaint";
                case "2" -> "l1BillingComplaint";
                case "3" -> "l1NetworkComplaint";
                case "4" -> "l1StaffComplaint";
                case "5" -> "l1OtherComplaint";
                default -> "l1ComplaintsReturn";
            };
            default -> null;
        };
    }

    private static Map<String, List<String>> buildLeafPaths() {
        Map<String, List<String>> paths = new LinkedHashMap<>();

        paths.put("l1ViewBill", List.of("billing", "viewBill"));
        paths.put("l1TopUpPayment", List.of("billing", "topUpPayment"));
        paths.put("l1PlanPricing", List.of("billing", "planPricing"));
        paths.put("l1FeeBreakdown", List.of("billing", "feeBreakdown"));
        paths.put("l1BillingReturn", List.of("billing", "billingReturn"));
        paths.put("l2UnauthorizedCharge", List.of("billing", "billingDispute", "unauthorizedCharge"));
        paths.put("l2WrongAmount", List.of("billing", "billingDispute", "wrongAmount"));
        paths.put("l2RefundStatus", List.of("billing", "billingDispute", "refundStatus"));
        paths.put("l2OtherDispute", List.of("billing", "billingDispute", "otherDispute"));
        paths.put("l3AutoRefund", List.of("billing", "billingDispute", "duplicateCharge", "autoRefund"));
        paths.put("l3ManualReview", List.of("billing", "billingDispute", "duplicateCharge", "manualReview"));
        paths.put("l3ChargeHistory", List.of("billing", "billingDispute", "duplicateCharge", "chargeHistory"));
        paths.put("l3DownloadStatement", List.of("billing", "billingDispute", "duplicateCharge", "downloadStatement"));
        paths.put("l3TransferSupervisor", List.of("billing", "billingDispute", "duplicateCharge", "transferSupervisor"));

        paths.put("l1DeviceIssue", List.of("techSupport", "deviceIssue"));
        paths.put("l1SoftwareHelp", List.of("techSupport", "softwareHelp"));
        paths.put("l1ScheduleInstall", List.of("techSupport", "scheduleInstall"));
        paths.put("l1RunSpeedTest", List.of("techSupport", "runSpeedTest"));
        paths.put("l1TechReturn", List.of("techSupport", "techReturn"));
        paths.put("l2IntermittentFix", List.of("techSupport", "networkIssues", "intermittentFix"));
        paths.put("l2SlowSpeedFix", List.of("techSupport", "networkIssues", "slowSpeedFix"));
        paths.put("l2DnsFix", List.of("techSupport", "networkIssues", "dnsFix"));
        paths.put("l2VpnFix", List.of("techSupport", "networkIssues", "vpnFix"));
        paths.put("l3RestartDevice", List.of("techSupport", "networkIssues", "cannotConnect", "restartDevice"));
        paths.put("l3CheckLine", List.of("techSupport", "networkIssues", "cannotConnect", "checkLine"));
        paths.put("l3RemoteDiagnostic", List.of("techSupport", "networkIssues", "cannotConnect", "remoteDiagnostic"));
        paths.put("l3ScheduleOnsite", List.of("techSupport", "networkIssues", "cannotConnect", "scheduleOnsite"));
        paths.put("l3DeviceReplacement", List.of("techSupport", "networkIssues", "cannotConnect", "deviceReplacement"));

        paths.put("l1ChangePassword", List.of("accountInsurance", "changePassword"));
        paths.put("l1ChangePlan", List.of("accountInsurance", "changePlan"));
        paths.put("l1ActivateService", List.of("accountInsurance", "activateService"));
        paths.put("l1CloseAccount", List.of("accountInsurance", "closeAccount"));
        paths.put("l1AccountReturn", List.of("accountInsurance", "accountReturn"));
        paths.put("l2WarrantyInquiry", List.of("accountInsurance", "warrantyClaims", "warrantyInquiry"));
        paths.put("l2ClaimStatus", List.of("accountInsurance", "warrantyClaims", "claimStatus"));
        paths.put("l2ExtendedWarranty", List.of("accountInsurance", "warrantyClaims", "extendedWarranty"));
        paths.put("l2WarrantyPolicy", List.of("accountInsurance", "warrantyClaims", "warrantyPolicy"));
        paths.put("l3ClaimDeviceDamage", List.of("accountInsurance", "warrantyClaims", "submitClaim", "claimDeviceDamage"));
        paths.put("l3ClaimAccidentalLoss", List.of("accountInsurance", "warrantyClaims", "submitClaim", "claimAccidentalLoss"));
        paths.put("l3ClaimBattery", List.of("accountInsurance", "warrantyClaims", "submitClaim", "claimBattery"));
        paths.put("l3ClaimScreen", List.of("accountInsurance", "warrantyClaims", "submitClaim", "claimScreen"));
        paths.put("l3ClaimOther", List.of("accountInsurance", "warrantyClaims", "submitClaim", "claimOther"));

        paths.put("l1ViewOrders", List.of("ordersLogistics", "viewOrders"));
        paths.put("l1ReturnsExchanges", List.of("ordersLogistics", "returnsExchanges"));
        paths.put("l1ShippingStatus", List.of("ordersLogistics", "shippingStatus"));
        paths.put("l1ConfirmReceipt", List.of("ordersLogistics", "confirmReceipt"));
        paths.put("l1InvoiceService", List.of("ordersLogistics", "invoiceService"));
        paths.put("l1OrdersReturn", List.of("ordersLogistics", "ordersReturn"));

        paths.put("l1ServiceQualityComplaint", List.of("complaints", "serviceQualityComplaint"));
        paths.put("l1BillingComplaint", List.of("complaints", "billingComplaint"));
        paths.put("l1NetworkComplaint", List.of("complaints", "networkComplaint"));
        paths.put("l1StaffComplaint", List.of("complaints", "staffComplaint"));
        paths.put("l1OtherComplaint", List.of("complaints", "otherComplaint"));
        paths.put("l1ComplaintsReturn", List.of("complaints", "complaintsReturn"));
        return Map.copyOf(paths);
    }

    private static void registerNodeAliases(DefaultOperatorRegistry registry) {
        for (String id : List.of(
                "l0Greeting", "l0MainMenuPrompt",
                "l1BillingPrompt", "l2BillingDisputePrompt", "l3DuplicateChargePrompt",
                "l1TechPrompt", "l2NetworkPrompt", "l3CannotConnectPrompt",
                "l1AccountPrompt", "l2WarrantyPrompt", "l3SubmitClaimPrompt",
                "l1OrdersPrompt", "l1ComplaintsPrompt")) {
            registry.registerRaw(id, GREETING);
        }
        for (String id : List.of(
                "collectMainMenu",
                "collectBillingInput", "collectDisputeInput", "collectResolutionInput",
                "collectTechInput", "collectNetworkInput", "collectCannotConnectInput",
                "collectAccountInput", "collectWarrantyInput", "collectSubmitClaimInput",
                "collectOrdersInput", "collectComplaintsInput")) {
            registry.registerRaw(id, COLLECT_DTMF);
        }
        registry.registerRaw("l0IdentifyCustomer", IDENTIFY_CUSTOMER);
        registry.registerRaw("transferLiveAgent", TRANSFER_AGENT);
        registry.registerRaw("satisfactionSurvey", SATISFACTION_SURVEY);
        registry.registerRaw("callSummary", CALL_SUMMARY_BUILDER);
        registry.registerRaw("saveCallRecord", SAVE_CALL_RECORD);

        registry.registerRaw("l1ViewBill", VIEW_BILL);
        registry.registerRaw("l1TopUpPayment", TOP_UP_PAYMENT);
        registry.registerRaw("l1PlanPricing", PLAN_PRICING);
        registry.registerRaw("l1FeeBreakdown", FEE_BREAKDOWN);
        registry.registerRaw("l1BillingReturn", RETURN_PREVIOUS_MENU);
        registry.registerRaw("l2UnauthorizedCharge", PROCESS_UNAUTHORIZED_CHARGE);
        registry.registerRaw("l2WrongAmount", PROCESS_WRONG_AMOUNT);
        registry.registerRaw("l2RefundStatus", QUERY_REFUND_STATUS);
        registry.registerRaw("l2OtherDispute", PROCESS_OTHER_DISPUTE);
        registry.registerRaw("l3AutoRefund", AUTO_REFUND);
        registry.registerRaw("l3ManualReview", MANUAL_REVIEW);
        registry.registerRaw("l3ChargeHistory", VIEW_CHARGE_HISTORY);
        registry.registerRaw("l3DownloadStatement", DOWNLOAD_STATEMENT);
        registry.registerRaw("l3TransferSupervisor", TRANSFER_SUPERVISOR);

        registry.registerRaw("l1DeviceIssue", DEVICE_ISSUE);
        registry.registerRaw("l1SoftwareHelp", SOFTWARE_HELP);
        registry.registerRaw("l1ScheduleInstall", SCHEDULE_INSTALL);
        registry.registerRaw("l1RunSpeedTest", RUN_SPEED_TEST);
        registry.registerRaw("l1TechReturn", RETURN_PREVIOUS_MENU);
        registry.registerRaw("l2IntermittentFix", INTERMITTENT_FIX);
        registry.registerRaw("l2SlowSpeedFix", SLOW_SPEED_FIX);
        registry.registerRaw("l2DnsFix", DNS_FIX);
        registry.registerRaw("l2VpnFix", VPN_FIX);
        registry.registerRaw("l3RestartDevice", RESTART_DEVICE);
        registry.registerRaw("l3CheckLine", CHECK_LINE);
        registry.registerRaw("l3RemoteDiagnostic", REMOTE_DIAGNOSTIC);
        registry.registerRaw("l3ScheduleOnsite", SCHEDULE_ONSITE);
        registry.registerRaw("l3DeviceReplacement", DEVICE_REPLACEMENT);

        registry.registerRaw("l1ChangePassword", CHANGE_PASSWORD);
        registry.registerRaw("l1ChangePlan", CHANGE_PLAN);
        registry.registerRaw("l1ActivateService", ACTIVATE_SERVICE);
        registry.registerRaw("l1CloseAccount", CLOSE_ACCOUNT);
        registry.registerRaw("l1AccountReturn", RETURN_PREVIOUS_MENU);
        registry.registerRaw("l2WarrantyInquiry", WARRANTY_INQUIRY);
        registry.registerRaw("l2ClaimStatus", CLAIM_STATUS);
        registry.registerRaw("l2ExtendedWarranty", EXTENDED_WARRANTY);
        registry.registerRaw("l2WarrantyPolicy", WARRANTY_POLICY);
        registry.registerRaw("l3ClaimDeviceDamage", CLAIM_DEVICE_DAMAGE);
        registry.registerRaw("l3ClaimAccidentalLoss", CLAIM_ACCIDENTAL_LOSS);
        registry.registerRaw("l3ClaimBattery", CLAIM_BATTERY);
        registry.registerRaw("l3ClaimScreen", CLAIM_SCREEN);
        registry.registerRaw("l3ClaimOther", CLAIM_OTHER);

        registry.registerRaw("l1ViewOrders", VIEW_ORDERS);
        registry.registerRaw("l1ReturnsExchanges", RETURNS_EXCHANGES);
        registry.registerRaw("l1ShippingStatus", SHIPPING_STATUS);
        registry.registerRaw("l1ConfirmReceipt", CONFIRM_RECEIPT);
        registry.registerRaw("l1InvoiceService", INVOICE_SERVICE);
        registry.registerRaw("l1OrdersReturn", RETURN_PREVIOUS_MENU);

        registry.registerRaw("l1ServiceQualityComplaint", SERVICE_QUALITY_COMPLAINT);
        registry.registerRaw("l1BillingComplaint", BILLING_COMPLAINT);
        registry.registerRaw("l1NetworkComplaint", NETWORK_COMPLAINT);
        registry.registerRaw("l1StaffComplaint", STAFF_COMPLAINT);
        registry.registerRaw("l1OtherComplaint", OTHER_COMPLAINT);
        registry.registerRaw("l1ComplaintsReturn", RETURN_PREVIOUS_MENU);
    }
}

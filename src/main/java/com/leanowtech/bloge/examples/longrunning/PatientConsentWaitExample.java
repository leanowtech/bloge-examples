package com.leanowtech.bloge.examples.longrunning;

import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.model.NodeStatus;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.operator.OperatorResult;
import com.leanowtech.bloge.core.operator.SuspendableOperator;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.examples.common.LoggingListener;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Demonstrates the long-running <em>patient consent deadline</em> pattern
 * using a timer-based suspension (deadline wait).
 *
 * <p>After a patient is enrolled in a clinical trial, the system sends a
 * consent form and <em>suspends</em> waiting for up to 48 hours (shortened to
 * 2 seconds in this example) for the patient to sign. If no signature arrives
 * before the deadline, the helper-managed in-memory timer fires and the consent
 * is marked as expired, which routes to a rescheduling node.
 *
 * <h2>Graph layout</h2>
 * <pre>
 * enrollPatient → sendConsentForm
 *                       ↓
 *              [SUSPEND waitConsentSignature]
 *              (signal if signed OR timer → expired)
 *                       ↓
 *               branch → recordConsent → scheduleFirstVisit
 *                      → rescheduleConsent
 * </pre>
 *
 * <h2>Long-running lifecycle</h2>
 * <ol>
 *   <li>Execute → suspends at {@code waitConsentSignature}; timer scheduled for 48 h.</li>
 *   <li>Scenario A — patient signs in time: save signed runtime node output → {@code resume()}.</li>
 *   <li>Scenario B — deadline expires: timer fires automatically → save expired
 *       runtime node output → {@code resume()} routes to {@code rescheduleConsent}.</li>
 * </ol>
 */
@SuppressWarnings("preview")
public class PatientConsentWaitExample {

    // ── Records ───────────────────────────────────────────────────────────────

    public record PatientInput(String patientId, String trialId) {}
    public record EnrolledPatient(String patientId, String trialId, String enrolledAt) {}

    public record ConsentFormInput(String patientId, String trialId) {}
    public record ConsentFormResult(String formId, String sentAt, String expiresAt) {}

    public record ConsentRecord(String formId, String status, String signedAt, String reason) {}
    public record VisitSchedule(String visitId, String scheduledDate) {}
    public record RescheduleResult(String newFormId, String scheduledAt) {}

    // ── Operators ─────────────────────────────────────────────────────────────

    static final Operator<PatientInput, EnrolledPatient> ENROLL_PATIENT = (input, ctx) -> {
        Thread.sleep(30);
        System.out.printf("  [enrollPatient] patientId=%s trialId=%s%n",
                input.patientId(), input.trialId());
        return new EnrolledPatient(input.patientId(), input.trialId(), Instant.now().toString());
    };

    static final Operator<ConsentFormInput, ConsentFormResult> SEND_CONSENT_FORM = (input, ctx) -> {
        Thread.sleep(20);
        System.out.println("  [sendConsentForm] consent form emailed to patient");
        String formId = "FORM-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        return new ConsentFormResult(formId, Instant.now().toString(), "expires in 48h");
    };

    /**
     * Deadline-based suspension: returns a suspended result with a 2-second
     * timeout (represents 48-hour clinical deadline).  When the timer fires, the
     * engine calls {@code signal(executionId, "waitConsentSignature", timerPayload)}.
     */
    static final SuspendableOperator<ConsentFormResult, ConsentRecord> WAIT_CONSENT_SIGNATURE = (input, ctx) -> {
        String patientId = ctx.graphContext().get("patientId", String.class);
        System.out.printf("  [waitConsentSignature] SUSPENDING — formId=%s patient=%s timeout=2s%n",
                input.formId(), patientId);
        return OperatorResult.suspend("consent:" + patientId, null, Duration.ofSeconds(2));
    };

    static final Operator<ConsentRecord, ConsentRecord> RECORD_CONSENT = (input, ctx) -> {
        Thread.sleep(20);
        System.out.printf("  [recordConsent] status=%s formId=%s%n", input.status(), input.formId());
        return input;
    };

    static final Operator<ConsentRecord, VisitSchedule> SCHEDULE_FIRST_VISIT = (input, ctx) -> {
        Thread.sleep(30);
        String visitId = "VISIT-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        System.out.println("  [scheduleFirstVisit] visitId=" + visitId);
        return new VisitSchedule(visitId, "2026-07-15");
    };

    static final Operator<ConsentFormResult, RescheduleResult> RESCHEDULE_CONSENT = (input, ctx) -> {
        Thread.sleep(20);
        System.out.println("  [rescheduleConsent] scheduling new consent session");
        return new RescheduleResult("FORM-NEW-001", Instant.now().toString());
    };

    // ── Main ──────────────────────────────────────────────────────────────────

    public static void main(String[] args) throws Exception {
        System.out.println("═══ Scenario A: Patient signs the consent form ═══");
        runScenario(true);

        System.out.println("\n\n═══ Scenario B: Consent deadline expires (timer fires) ═══");
        runScenario(false);
    }

    @SuppressWarnings("unchecked")
    private static void runScenario(boolean patientSigns) throws Exception {
        var registry = new DefaultOperatorRegistry();
        var runtime = LongRunningRuntimeExampleSupport.runtime(registry, new LoggingListener());
        var engine = runtime.engine();

        // ── Build graph ───────────────────────────────────────────────────────
        Graph graph = Graph.builder("patientConsentWait")
                .node("enrollPatient", ENROLL_PATIENT)
                    .input((results, ctx) -> new PatientInput(
                            ctx.get("patientId", String.class),
                            ctx.get("trialId", String.class)))
                .node("sendConsentForm", SEND_CONSENT_FORM)
                    .dependsOn("enrollPatient")
                    .input((results, ctx) -> {
                        EnrolledPatient p = results.get("enrollPatient", EnrolledPatient.class);
                        return new ConsentFormInput(p.patientId(), p.trialId());
                    })
                .suspendNode("waitConsentSignature", WAIT_CONSENT_SIGNATURE)
                    .dependsOn("sendConsentForm")
                    .input((results, ctx) -> results.get("sendConsentForm", ConsentFormResult.class))
                .node("recordConsent", RECORD_CONSENT)
                    .dependsOn("waitConsentSignature")
                    .input((results, ctx) -> {
                        Object raw = results.getRaw("waitConsentSignature");
                        if (raw instanceof ConsentRecord cr) return cr;
                        if (raw instanceof Map<?,?> m) {
                            Object fId = m.get("formId"); Object st = m.get("status");
                            Object sig = m.get("signedAt"); Object rea = m.get("reason");
                            return new ConsentRecord(
                                    fId instanceof String s1 ? s1 : "UNKNOWN",
                                    st  instanceof String s2 ? s2 : "unknown",
                                    sig instanceof String s3 ? s3 : null,
                                    rea instanceof String s4 ? s4 : null);
                        }
                        return new ConsentRecord("UNKNOWN", "unknown", null, null);
                    })
                .node("scheduleFirstVisit", SCHEDULE_FIRST_VISIT)
                    .dependsOn("recordConsent")
                    .input((results, ctx) -> results.get("recordConsent", ConsentRecord.class))
                .node("rescheduleConsent", RESCHEDULE_CONSENT)
                    .dependsOn("waitConsentSignature")
                    .input((results, ctx) -> results.get("sendConsentForm", ConsentFormResult.class))
                .branch("waitConsentSignature")
                    .on("status")
                    .when(val -> "signed".equals(val), "recordConsent")
                    .otherwise("rescheduleConsent")
                .build();

        var ctx = new GraphContext(Map.of(
                "patientId", "PAT-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase(),
                "trialId",   "TRIAL-CARDIO-001"
        ));

        // ── Phase 1: execute until suspension ────────────────────────────────
        System.out.println("\n── Phase 1: Enroll patient and send consent form ──");
        GraphResult phase1 = engine.executeWithOperators(graph, ctx, Map.of(
                "enrollPatient",         ENROLL_PATIENT,
                "sendConsentForm",       SEND_CONSENT_FORM,
                "waitConsentSignature",  WAIT_CONSENT_SIGNATURE,
                "recordConsent",         RECORD_CONSENT,
                "scheduleFirstVisit",    SCHEDULE_FIRST_VISIT,
                "rescheduleConsent",     RESCHEDULE_CONSENT
        ));

        System.out.printf("%nSuspended: %s  executionId: %s%n",
                phase1.isSuspended(), phase1.executionId());
        String execId = phase1.executionId();
        ConsentFormResult form = phase1.getOutput("sendConsentForm", ConsentFormResult.class);

        // ── Phase 2: signal or timer ───────────────────────────────────────────
        if (patientSigns) {
            System.out.println("\n── Phase 2A: Patient signs the consent form ──");
            Thread.sleep(100);
            runtime.saveNodeOutput(execId, "patientConsentWait", "waitConsentSignature",
                    new ConsentRecord(form.formId(), "signed", Instant.now().toString(), null));
        } else {
            System.out.println("\n── Phase 2B: Waiting for 2-second deadline to expire... ──");
            Thread.sleep(2500); // wait for the 2s timer to fire
            // Timer fires → signal() called internally. Save expired status as runtime node output.
            runtime.saveNodeOutput(execId, "patientConsentWait", "waitConsentSignature",
                    new ConsentRecord(form.formId(), "expired", null, "48h deadline passed"));
            System.out.println("Timer fired — consent expired, routing to reschedule");
        }

        // ── Phase 3: resume ────────────────────────────────────────────────────
        System.out.println("\n── Phase 3: Resume patient consent workflow ──");
        registry.register("enrollPatient",        ENROLL_PATIENT);
        registry.register("sendConsentForm",      SEND_CONSENT_FORM);
        registry.registerRaw("waitConsentSignature", WAIT_CONSENT_SIGNATURE);
        registry.register("recordConsent",        RECORD_CONSENT);
        registry.register("scheduleFirstVisit",   SCHEDULE_FIRST_VISIT);
        registry.register("rescheduleConsent",    RESCHEDULE_CONSENT);

        GraphResult phase3 = engine.resume(graph, execId, ctx);

        System.out.println("\n── Final Result ──");
        System.out.println("Success: " + phase3.isSuccess());
        for (var e : phase3.statusMap().entrySet()) {
            System.out.printf("  %-25s → %s%n", e.getKey(), e.getValue());
        }
        if (patientSigns && phase3.getStatus("scheduleFirstVisit") == NodeStatus.COMPLETED) {
            System.out.println("First visit: " + phase3.getOutput("scheduleFirstVisit", VisitSchedule.class));
        } else if (!patientSigns && phase3.getStatus("rescheduleConsent") == NodeStatus.COMPLETED) {
            System.out.println("Rescheduled: " + phase3.getOutput("rescheduleConsent", RescheduleResult.class));
        }
    }
}

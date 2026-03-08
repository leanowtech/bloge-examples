package com.leanowtech.bloge.examples.longrunning;

import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.model.NodeStatus;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.operator.OperatorResult;
import com.leanowtech.bloge.core.operator.SuspendableOperator;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.dsl.compiler.GraphLoader;
import com.leanowtech.bloge.examples.common.LoggingListener;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * DSL version of the patient-consent deadline example.
 *
 * <p>Demonstrates a timer-based long-running workflow loaded from an inline
 * DSL string.  The {@code waitConsentSignature} node represents the
 * {@code wait ... timeout = 48h on_timeout { ... }} DSL block.
 *
 * <p>Scenarios:
 * <ul>
 *   <li><b>Scenario A</b> — patient signs in time: runtime node output saved with
 *       {@code "status":"signed"} → resume routes to {@code recordConsent}.</li>
 *   <li><b>Scenario B</b> — deadline expires: 2-second timer fires →
 *       runtime node output saved with {@code "status":"expired"} → resume routes to
 *       {@code rescheduleConsent}.</li>
 * </ul>
 */
@SuppressWarnings("preview")
public class PatientConsentWaitDslExample {

    static final Operator<Map<String, Object>, Map<String, Object>> ENROLL_PATIENT = (input, ctx) -> {
        Thread.sleep(30);
        System.out.println("  [enrollPatient] patientId=" + input.get("patientId"));
        return Map.of("patientId", input.get("patientId"), "trialId", input.get("trialId"),
                "enrolledAt", Instant.now().toString());
    };

    static final Operator<Map<String, Object>, Map<String, Object>> SEND_CONSENT_FORM = (input, ctx) -> {
        Thread.sleep(20);
        String formId = "FORM-DSL-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        System.out.println("  [sendConsentForm] formId=" + formId);
        return Map.of("formId", formId, "sentAt", Instant.now().toString(), "expiresAt", "48h from now");
    };

    /** Represents: wait waitConsentSignature { signal_key = "consent:" + ctx.patientId  timeout = 48h } */
    static final SuspendableOperator<Map<String, Object>, Map<String, Object>> WAIT_CONSENT_SIGNATURE = (input, ctx) -> {
        String patientId = ctx.graphContext().get("patientId", String.class);
        System.out.println("  [waitConsentSignature] SUSPENDING — timeout=2s patient=" + patientId);
        return OperatorResult.suspend("consent:" + patientId, null, java.time.Duration.ofSeconds(2));
    };

    static final Operator<Map<String, Object>, Map<String, Object>> RECORD_CONSENT = (input, ctx) -> {
        Thread.sleep(20);
        System.out.println("  [recordConsent] status=" + input.get("status"));
        return Map.of("recorded", true, "status", input.get("status"), "recordedAt", Instant.now().toString());
    };

    static final Operator<Map<String, Object>, Map<String, Object>> SCHEDULE_FIRST_VISIT = (input, ctx) -> {
        Thread.sleep(30);
        String visitId = "VISIT-DSL-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        System.out.println("  [scheduleFirstVisit] visitId=" + visitId);
        return Map.of("visitId", visitId, "scheduledDate", "2026-07-15");
    };

    static final Operator<Map<String, Object>, Map<String, Object>> RESCHEDULE_CONSENT = (input, ctx) -> {
        Thread.sleep(20);
        System.out.println("  [rescheduleConsent] new consent session scheduled");
        return Map.of("newFormId", "FORM-NEW-DSL", "scheduledAt", Instant.now().toString());
    };

    public static void main(String[] args) throws Exception {
        System.out.println("═══ DSL Scenario A: Patient signs in time ═══");
        runScenario(true);
        System.out.println("\n\n═══ DSL Scenario B: Deadline expires ═══");
        runScenario(false);
    }

    private static void runScenario(boolean patientSigns) throws Exception {
        var registry = new DefaultOperatorRegistry();
        registry.register("EnrollPatientOperator",        ENROLL_PATIENT);
        registry.register("SendConsentFormOperator",      SEND_CONSENT_FORM);
        registry.registerRaw("WaitConsentSignatureOperator", WAIT_CONSENT_SIGNATURE);
        registry.register("RecordConsentOperator",        RECORD_CONSENT);
        registry.register("ScheduleFirstVisitOperator",   SCHEDULE_FIRST_VISIT);
        registry.register("RescheduleConsentOperator",    RESCHEDULE_CONSENT);

        var runtime = LongRunningRuntimeExampleSupport.runtime(registry, new LoggingListener());
        var engine = runtime.engine();
        var loader = new GraphLoader(registry);

        String dsl = """
                graph patientConsentWait {

                  node enrollPatient : EnrollPatientOperator {
                    input {
                      patientId = ctx.patientId
                      trialId   = ctx.trialId
                    }
                    timeout = 5s
                  }

                  node sendConsentForm : SendConsentFormOperator {
                    depends_on = [enrollPatient]
                    input {
                      patientId = ctx.patientId
                      trialId   = ctx.trialId
                    }
                    timeout = 10s
                  }

                  /// Represents: wait waitConsentSignature {
                  ///   signal_key = "consent:" + ctx.patientId
                  ///   timeout    = 48h
                  ///   on_timeout { status = "expired" }
                  /// }
                  node waitConsentSignature : WaitConsentSignatureOperator {
                    depends_on = [sendConsentForm]
                    input {
                      formId  = sendConsentForm.output.formId
                      sentAt  = sendConsentForm.output.sentAt
                    }
                  }

                  node recordConsent : RecordConsentOperator {
                    depends_on = [waitConsentSignature]
                    input {
                      formId   = waitConsentSignature.output.formId
                      status   = waitConsentSignature.output.status
                      signedAt = waitConsentSignature.output.signedAt
                    }
                    timeout = 10s
                  }

                  node scheduleFirstVisit : ScheduleFirstVisitOperator {
                    depends_on = [recordConsent]
                    input {
                      patientId = ctx.patientId
                      trialId   = ctx.trialId
                      status    = recordConsent.output.status
                    }
                    timeout = 10s
                  }

                  node rescheduleConsent : RescheduleConsentOperator {
                    depends_on = [waitConsentSignature]
                    input {
                      patientId = ctx.patientId
                      formId    = sendConsentForm.output.formId
                    }
                    timeout = 10s
                  }

                  branch on waitConsentSignature.output.status {
                    "signed" -> recordConsent
                    otherwise -> rescheduleConsent
                  }
                }
                """;

        Graph graph = loader.load(dsl);

        var ctx = new GraphContext(Map.of(
                "patientId", "PAT-DSL-" + UUID.randomUUID().toString().substring(0, 4).toUpperCase(),
                "trialId",   "TRIAL-CARDIO-001"
        ));

        System.out.println("\n── Phase 1 (DSL): Execute until suspension ──");
        GraphResult phase1 = engine.execute(graph, ctx);
        System.out.printf("%nSuspended: %s  executionId: %s%n",
                phase1.isSuspended(), phase1.executionId());

        String execId = phase1.executionId();
        Object formRaw = phase1.results().getRaw("sendConsentForm");
        String formId = formRaw instanceof Map<?,?> m ? (String) m.get("formId") : "UNKNOWN";

        if (patientSigns) {
            System.out.println("\n── Phase 2A (DSL): Patient signs ──");
            Thread.sleep(100);
            runtime.saveNodeOutput(execId, "patientConsentWait", "waitConsentSignature",
                    LongRunningRuntimeExampleSupport.payload(
                            "formId", formId,
                            "status", "signed",
                            "signedAt", Instant.now().toString(),
                            "reason", null
                    ));
        } else {
            System.out.println("\n── Phase 2B (DSL): Waiting for 2s deadline ──");
            Thread.sleep(2500);
            runtime.saveNodeOutput(execId, "patientConsentWait", "waitConsentSignature",
                    LongRunningRuntimeExampleSupport.payload(
                            "formId", formId,
                            "status", "expired",
                            "signedAt", null,
                            "reason", "48h deadline passed"
                    ));
            System.out.println("Timer fired — consent expired");
        }

        System.out.println("\n── Phase 3 (DSL): Resume ──");
        GraphResult phase3 = engine.resume(graph, execId, ctx);

        System.out.println("\n── Final DSL Result ──");
        System.out.println("Success: " + phase3.isSuccess());
        for (var e : phase3.statusMap().entrySet()) {
            System.out.printf("  %-28s → %s%n", e.getKey(), e.getValue());
        }
        String outcome = patientSigns ? "scheduleFirstVisit" : "rescheduleConsent";
        if (phase3.getStatus(outcome) == NodeStatus.COMPLETED) {
            System.out.println("Outcome: " + phase3.results().getRaw(outcome));
        }
    }
}

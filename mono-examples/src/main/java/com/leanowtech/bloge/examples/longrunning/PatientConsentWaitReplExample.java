package com.leanowtech.bloge.examples.longrunning;

import java.nio.charset.StandardCharsets;
import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.model.NodeStatus;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.dsl.compiler.GraphLoader;
import com.leanowtech.bloge.examples.common.LoggingListener;
import com.leanowtech.bloge.examples.common.ReplHelper;

import java.time.Instant;
import java.util.Map;
import java.util.Scanner;
import java.util.UUID;

public class PatientConsentWaitReplExample {

    private static final String DSL = """

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

    private static Graph buildGraph(DefaultOperatorRegistry registry) {
        registry.register("EnrollPatientOperator", PatientConsentWaitDslExample.ENROLL_PATIENT);
        registry.register("SendConsentFormOperator", PatientConsentWaitDslExample.SEND_CONSENT_FORM);
        registry.registerRaw("WaitConsentSignatureOperator", PatientConsentWaitDslExample.WAIT_CONSENT_SIGNATURE);
        registry.register("RecordConsentOperator", PatientConsentWaitDslExample.RECORD_CONSENT);
        registry.register("ScheduleFirstVisitOperator", PatientConsentWaitDslExample.SCHEDULE_FIRST_VISIT);
        registry.register("RescheduleConsentOperator", PatientConsentWaitDslExample.RESCHEDULE_CONSENT);
        return new GraphLoader(registry).load(DSL);
    }

    private static Map<String, Object> promptContext(Scanner scanner) {
        String patientId = ReplHelper.promptString(scanner, "patientId", "PAT-DSL-" + UUID.randomUUID().toString().substring(0, 4).toUpperCase());
        String trialId = ReplHelper.promptString(scanner, "trialId", "TRIAL-CARDIO-001");
        return Map.of(
                "patientId", patientId,
                "trialId", trialId
        );
    }

    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        try (var scanner = new Scanner(System.in, StandardCharsets.UTF_8)) {
            boolean runAgain;
            do {
                ReplHelper.header("Patient Consent Wait REPL");
                Map<String, Object> values = promptContext(scanner);

                var registry = new DefaultOperatorRegistry();
                Graph graph = buildGraph(registry);
                var runtime = LongRunningRuntimeExampleSupport.runtime(registry, new LoggingListener());
                var engine = runtime.engine();

                GraphResult phase1 = engine.execute(graph, new GraphContext(values));
                ReplHelper.printResult(phase1);

                if (phase1.isSuspended() || phase1.getStatus("waitConsentSignature") == NodeStatus.SUSPENDED) {
                    String formId = "UNKNOWN";
                    Object raw = phase1.results().getRaw("sendConsentForm");
                    if (raw instanceof Map<?, ?> m) {
                        Object fid = m.get("formId");
                        if (fid instanceof String s) formId = s;
                    }

                    System.out.print("Press Enter to simulate signature, or type 'timeout' then Enter: ");
                    String action = scanner.nextLine().trim();
                    boolean signed = action.isEmpty() || "signed".equalsIgnoreCase(action);

                    if (!signed) {
                        Thread.sleep(2100);
                    }

                    Map<String, Object> payload = signed
                            ? LongRunningRuntimeExampleSupport.payload(
                                    "formId", formId,
                                    "status", "signed",
                                    "signedAt", Instant.now().toString(),
                                    "reason", null
                            )
                            : LongRunningRuntimeExampleSupport.payload(
                                    "formId", formId,
                                    "status", "expired",
                                    "signedAt", null,
                                    "reason", "48h deadline passed"
                            );

                    runtime.saveNodeOutput(phase1.executionId(), "patientConsentWait", "waitConsentSignature",
                            payload);

                    GraphResult phase2 = engine.resume(graph, phase1.executionId(), new GraphContext(values));
                    ReplHelper.printResult(phase2);
                }

                runAgain = ReplHelper.askRunAgain(scanner);
            } while (runAgain);
        }
    }
}

package com.leanowtech.bloge.examples.healthcare;

import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.engine.GraphEngine;
import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.model.NodeStatus;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.dsl.compiler.GraphLoader;
import com.leanowtech.bloge.examples.ecommerce.OrderProcessingExample;

import java.util.List;
import java.util.Map;

/**
 * DSL version of online patient triage orchestration.
 *
 * <p>This example compiles and executes the triage workflow from DSL using
 * Map-based operators registered in the runtime registry.
 *
 * <p>Graph layout:
 * <pre>
 * collectPatientInfo + analyzeSymptoms
 *   -> evaluateRisk
 *   -> decideCarePath
 *      -> emergencyReferral | scheduleDoctorVisit | selfCareGuidance
 * </pre>
 *
 * <p>Run {@link #main(String[])} to compile and execute the DSL graph.
 */
@SuppressWarnings("preview")
public class OnlineTriageDslExample {

    static final Operator<Map<String, Object>, Map<String, Object>> FETCH_PATIENT_RECORD = (input, ctx) -> {
        Thread.sleep(50);
        String patientId = (String) input.get("patientId");
        return Map.of("id", patientId, "name", "Zhang Wei", "age", 45, "gender", "male",
                "allergies", List.of("penicillin"), "bloodType", "A+");
    };

    static final Operator<Map<String, Object>, Map<String, Object>> FETCH_MEDICAL_HISTORY = (input, ctx) -> {
        Thread.sleep(70);
        return Map.of(
                "records", List.of(
                        Map.of("condition", "hypertension", "date", "2021-03-15", "treatment", "medication management"),
                        Map.of("condition", "type 2 diabetes", "date", "2019-08-22", "treatment", "lifestyle modification and metformin")
                ),
                "medications", List.of("metformin", "lisinopril")
        );
    };

    static final Operator<Map<String, Object>, Map<String, Object>> ANALYZE_SYMPTOMS = (input, ctx) -> {
        Thread.sleep(100);
        return Map.of("symptoms", List.of("chest pain", "shortness of breath", "dizziness"),
                "bodySystem", "cardiovascular", "severity", "high");
    };

    static final Operator<Map<String, Object>, Map<String, Object>> AI_PRE_DIAGNOSIS = (input, ctx) -> {
        Thread.sleep(200);
        return Map.of("possibleConditions", List.of("acute coronary syndrome", "angina pectoris", "panic disorder"),
                "confidenceScore", 0.78, "urgencyLevel", "emergency");
    };

    @SuppressWarnings("unchecked")
    static final Operator<Map<String, Object>, Map<String, Object>> TRIAGE_DECISION = (input, ctx) -> {
        Thread.sleep(30);
        var diagnosis = (Map<String, Object>) input.get("diagnosis");
        String urgencyLevel = (String) diagnosis.get("urgencyLevel");
        if ("emergency".equals(urgencyLevel)) {
            return Map.of("triageLevel", "emergency", "department", "Emergency");
        }
        double confidence = ((Number) diagnosis.get("confidenceScore")).doubleValue();
        if (confidence > 0.5) {
            return Map.of("triageLevel", "specialist", "department", "Cardiology");
        }
        return Map.of("triageLevel", "general", "department", "General Medicine");
    };

    @SuppressWarnings("unchecked")
    static final Operator<Map<String, Object>, Map<String, Object>> ROUTE_EMERGENCY = (input, ctx) -> {
        Thread.sleep(20);
        return Map.of("roomId", "ER-03", "doctor", "Dr. Li", "eta", "2 minutes");
    };

    @SuppressWarnings("unchecked")
    static final Operator<Map<String, Object>, Map<String, Object>> ROUTE_SPECIALIST = (input, ctx) -> {
        Thread.sleep(30);
        String department = (String) input.get("department");
        return Map.of("department", department, "doctorName", "Dr. Chen", "appointmentTime", "14:30 today");
    };

    static final Operator<Map<String, Object>, Map<String, Object>> ROUTE_GENERAL = (input, ctx) -> {
        Thread.sleep(20);
        return Map.of("queueNumber", "Q-042", "estimatedWaitMinutes", 35);
    };

    public static void main(String[] args) {
        var registry = new DefaultOperatorRegistry();
        // ── Operator Registrations ─────────────────────────────────────────────
        // FETCH_PATIENT_RECORD: reads ctx.patientId → returns {id, name, age, gender, allergies, bloodType}
        registry.register("FetchPatientRecordOperator", FETCH_PATIENT_RECORD);
        // FETCH_MEDICAL_HISTORY: reads ctx.patientId → returns {records, medications}
        registry.register("FetchMedicalHistoryOperator", FETCH_MEDICAL_HISTORY);
        // ANALYZE_SYMPTOMS: reads patient, history, chiefComplaint → returns {symptoms, bodySystem, severity}
        registry.register("AnalyzeSymptomsOperator", ANALYZE_SYMPTOMS);
        // AI_PRE_DIAGNOSIS: reads patient, symptoms, history → returns {possibleConditions, confidenceScore, urgencyLevel}
        registry.register("AiPreDiagnosisOperator", AI_PRE_DIAGNOSIS);
        // TRIAGE_DECISION: reads diagnosis → returns {triageLevel, department}
        registry.register("TriageDecisionOperator", TRIAGE_DECISION);
        // ROUTE_EMERGENCY: reads patient, diagnosis → returns {roomId, doctor, eta}
        registry.register("RouteEmergencyOperator", ROUTE_EMERGENCY);
        // ROUTE_SPECIALIST: reads patient, diagnosis, department → returns {department, doctorName, appointmentTime}
        registry.register("RouteSpecialistOperator", ROUTE_SPECIALIST);
        // ROUTE_GENERAL: reads patient → returns {queueNumber, estimatedWaitMinutes}
        registry.register("RouteGeneralOperator", ROUTE_GENERAL);

        var loader = new GraphLoader(registry);

        String dsl = """
                graph onlineTriage {
                  ///  parallel fetch: fetchPatientRecord and fetchMedicalHistory run concurrently
                  node fetchPatientRecord : FetchPatientRecordOperator {
                    input { patientId = ctx.patientId }
                    timeout = 3s
                    retry = { attempts: 1, backoff: 200ms, strategy: exponential }
                  }
                  node fetchMedicalHistory : FetchMedicalHistoryOperator {
                    input { patientId = ctx.patientId }
                    timeout = 3s
                  }
                  ///  analyzeSymptoms: fan-in of fetchPatientRecord+fetchMedicalHistory → {symptoms, bodySystem, severity}
                  node analyzeSymptoms : AnalyzeSymptomsOperator {
                    depends_on = [fetchPatientRecord, fetchMedicalHistory]
                    input {
                      patient        = fetchPatientRecord.output
                      history        = fetchMedicalHistory.output
                      chiefComplaint = ctx.chiefComplaint
                    }
                    timeout = 5s
                  }
                  ///  aiPreDiagnosis: reads patient, symptoms, history → {possibleConditions, confidenceScore, urgencyLevel}
                  node aiPreDiagnosis : AiPreDiagnosisOperator {
                    depends_on = [analyzeSymptoms]
                    input {
                      patient  = fetchPatientRecord.output
                      symptoms = analyzeSymptoms.output
                      history  = fetchMedicalHistory.output
                    }
                    retry = { attempts: 2, backoff: 500ms, strategy: exponential }
                    timeout = 10s
                  }
                  ///  triageDecision: reads diagnosis.urgencyLevel → {triageLevel, department}
                  node triageDecision : TriageDecisionOperator {
                    depends_on = [aiPreDiagnosis]
                    input {
                      diagnosis = aiPreDiagnosis.output
                      patient   = fetchPatientRecord.output
                    }
                  }
                  ///  branch on triageLevel: emergency → routeEmergency, specialist → routeSpecialist, otherwise → routeGeneral
                  branch on triageDecision.output.triageLevel {
                    "emergency"  -> routeEmergency
                    "specialist" -> routeSpecialist
                    otherwise    -> routeGeneral
                  }
                  ///  branch outcomes: only one routing node will execute
                  node routeEmergency : RouteEmergencyOperator {
                    depends_on = [triageDecision]
                    input {
                      patient   = fetchPatientRecord.output
                      diagnosis = aiPreDiagnosis.output
                    }
                  }
                  node routeSpecialist : RouteSpecialistOperator {
                    depends_on = [triageDecision]
                    input {
                      patient    = fetchPatientRecord.output
                      diagnosis  = aiPreDiagnosis.output
                      department = triageDecision.output.department
                    }
                  }
                  node routeGeneral : RouteGeneralOperator {
                    depends_on = [triageDecision]
                    input {
                      patient = fetchPatientRecord.output
                    }
                  }
                }
                """;

        // compile DSL; operators resolved by PascalCase name
        Graph graph = loader.load(dsl);

        var engine = GraphEngine.builder()
                .registry(registry)
                .interceptors(List.of())
                .listeners(List.of(new OrderProcessingExample.LoggingListener()))
                .build();
        var ctx = new GraphContext(Map.of(
                "patientId", "P-10042",
                "chiefComplaint", "chest pain and shortness of breath for 2 hours"
        ));

        // execute; results keyed by node ID
        GraphResult result = engine.execute(graph, ctx);

        System.out.println("\n═══ DSL Online Triage Result ═══");
        System.out.println("Success: " + result.isSuccess());
        System.out.println("Elapsed: " + result.elapsed().toMillis() + "ms");
        System.out.println();

        for (var entry : result.statusMap().entrySet()) {
            System.out.printf("  %-25s → %s%n", entry.getKey(), entry.getValue());
        }
        System.out.println();

        // getRaw returns Object; cast to Map<String,Object> if typed access is needed
        if (result.getStatus("routeEmergency") == NodeStatus.COMPLETED) {
            System.out.println("Emergency routing: " + result.results().getRaw("routeEmergency"));
        } else if (result.getStatus("routeSpecialist") == NodeStatus.COMPLETED) {
            System.out.println("Specialist routing: " + result.results().getRaw("routeSpecialist"));
        } else if (result.getStatus("routeGeneral") == NodeStatus.COMPLETED) {
            System.out.println("General routing: " + result.results().getRaw("routeGeneral"));
        }
    }
}

package com.leanowtech.bloge.examples.healthcare;

import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.engine.GraphEngine;
import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.core.model.BackoffStrategy;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.model.NodeStatus;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.examples.ecommerce.OrderProcessingExample;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Online triage workflow for healthcare symptom intake and recommendation.
 *
 * <p>This example demonstrates symptom analysis, risk evaluation, and care-path branching
 * to emergency escalation, doctor scheduling, or self-care guidance.
 *
 * <p>Graph layout:
 * <pre>
 * collectPatientInfo + analyzeSymptoms
 *   -> evaluateRisk
 *   -> decideCarePath
 *      -> emergencyReferral | scheduleDoctorVisit | selfCareGuidance
 * </pre>
 *
 * <p>Run {@link #main(String[])} to execute the graph with sample patient input.
 */
public class OnlineTriageExample {

    public record PatientQuery(String patientId) {}
    public record PatientRecord(String id, String name, int age, String gender, List<String> allergies, String bloodType) {}

    public record HistoryQuery(String patientId) {}
    public record PastDiagnosis(String condition, String date, String treatment) {}
    public record MedicalHistory(List<PastDiagnosis> records, List<String> medications) {}

    public record SymptomInput(PatientRecord patient, MedicalHistory history, String chiefComplaint) {}
    public record SymptomAnalysis(List<String> symptoms, String bodySystem, String severity) {}

    public record DiagnosisInput(PatientRecord patient, SymptomAnalysis symptoms, MedicalHistory history) {}
    public record PreDiagnosis(List<String> possibleConditions, double confidenceScore, String urgencyLevel) {}

    public record TriageInput(PreDiagnosis diagnosis, PatientRecord patient) {}
    public record TriageResult(String triageLevel, String department) {}

    public record EmergencyInput(PatientRecord patient, PreDiagnosis diagnosis) {}
    public record EmergencyRouting(String roomId, String doctor, String eta) {}

    public record SpecialistInput(PatientRecord patient, PreDiagnosis diagnosis, String department) {}
    public record SpecialistRouting(String department, String doctorName, String appointmentTime) {}

    public record GeneralInput(PatientRecord patient) {}
    public record GeneralRouting(String queueNumber, int estimatedWaitMinutes) {}

    static final Operator<PatientQuery, PatientRecord> FETCH_PATIENT_RECORD = (input, ctx) -> {
        Thread.sleep(50);
        return new PatientRecord("P-10042", "Zhang Wei", 45, "male", List.of("penicillin"), "A+");
    };

    static final Operator<HistoryQuery, MedicalHistory> FETCH_MEDICAL_HISTORY = (input, ctx) -> {
        Thread.sleep(70);
        return new MedicalHistory(
                List.of(
                        new PastDiagnosis("hypertension", "2021-03-15", "medication management"),
                        new PastDiagnosis("type 2 diabetes", "2019-08-22", "lifestyle modification and metformin")
                ),
                List.of("metformin", "lisinopril")
        );
    };

    static final Operator<SymptomInput, SymptomAnalysis> ANALYZE_SYMPTOMS = (input, ctx) -> {
        Thread.sleep(100);
        return new SymptomAnalysis(List.of("chest pain", "shortness of breath", "dizziness"), "cardiovascular", "high");
    };

    static final Operator<DiagnosisInput, PreDiagnosis> AI_PRE_DIAGNOSIS = (input, ctx) -> {
        Thread.sleep(200);
        return new PreDiagnosis(List.of("acute coronary syndrome", "angina pectoris", "panic disorder"), 0.78, "emergency");
    };

    static final Operator<TriageInput, TriageResult> TRIAGE_DECISION = (input, ctx) -> {
        Thread.sleep(30);
        if ("emergency".equals(input.diagnosis().urgencyLevel())) {
            return new TriageResult("emergency", "Emergency");
        } else if (input.diagnosis().confidenceScore() > 0.5) {
            return new TriageResult("specialist", "Cardiology");
        }
        return new TriageResult("general", "General Medicine");
    };

    static final Operator<EmergencyInput, EmergencyRouting> ROUTE_EMERGENCY = (input, ctx) -> {
        Thread.sleep(20);
        return new EmergencyRouting("ER-03", "Dr. Li", "2 minutes");
    };

    static final Operator<SpecialistInput, SpecialistRouting> ROUTE_SPECIALIST = (input, ctx) -> {
        Thread.sleep(30);
        return new SpecialistRouting(input.department(), "Dr. Chen", "14:30 today");
    };

    static final Operator<GeneralInput, GeneralRouting> ROUTE_GENERAL = (input, ctx) -> {
        Thread.sleep(20);
        return new GeneralRouting("Q-042", 35);
    };

    public static Graph buildGraph() {
        var builder = Graph.builder("onlineTriage")
                .node("fetchPatientRecord", FETCH_PATIENT_RECORD)
                    .input((results, ctx) -> new PatientQuery(ctx.get("patientId", String.class)))
                    .timeout(Duration.ofSeconds(3))
                    .retry(1, Duration.ofMillis(200), BackoffStrategy.EXPONENTIAL)
                .node("fetchMedicalHistory", FETCH_MEDICAL_HISTORY)
                    .input((results, ctx) -> new HistoryQuery(ctx.get("patientId", String.class)))
                    .timeout(Duration.ofSeconds(3))
                .node("analyzeSymptoms", ANALYZE_SYMPTOMS)
                    .dependsOn("fetchPatientRecord", "fetchMedicalHistory")
                    .input((results, ctx) -> new SymptomInput(
                            results.get("fetchPatientRecord", PatientRecord.class),
                            results.get("fetchMedicalHistory", MedicalHistory.class),
                            ctx.get("chiefComplaint", String.class)))
                    .timeout(Duration.ofSeconds(5))
                .node("aiPreDiagnosis", AI_PRE_DIAGNOSIS)
                    .dependsOn("analyzeSymptoms")
                    .input((results, ctx) -> new DiagnosisInput(
                            results.get("fetchPatientRecord", PatientRecord.class),
                            results.get("analyzeSymptoms", SymptomAnalysis.class),
                            results.get("fetchMedicalHistory", MedicalHistory.class)))
                    .retry(2, Duration.ofMillis(500), BackoffStrategy.EXPONENTIAL)
                    .timeout(Duration.ofSeconds(10))
                .node("triageDecision", TRIAGE_DECISION)
                    .dependsOn("aiPreDiagnosis")
                    .input((results, ctx) -> new TriageInput(
                            results.get("aiPreDiagnosis", PreDiagnosis.class),
                            results.get("fetchPatientRecord", PatientRecord.class)))
                .node("routeEmergency", ROUTE_EMERGENCY)
                    .dependsOn("triageDecision")
                    .input((results, ctx) -> new EmergencyInput(
                            results.get("fetchPatientRecord", PatientRecord.class),
                            results.get("aiPreDiagnosis", PreDiagnosis.class)))
                .node("routeSpecialist", ROUTE_SPECIALIST)
                    .dependsOn("triageDecision")
                    .input((results, ctx) -> new SpecialistInput(
                            results.get("fetchPatientRecord", PatientRecord.class),
                            results.get("aiPreDiagnosis", PreDiagnosis.class),
                            results.get("triageDecision", TriageResult.class).department()))
                .node("routeGeneral", ROUTE_GENERAL)
                    .dependsOn("triageDecision")
                    .input((results, ctx) -> new GeneralInput(
                            results.get("fetchPatientRecord", PatientRecord.class)))
                .branch("triageDecision")
                    .on("triageLevel")
                    .when(val -> "emergency".equals(val), "routeEmergency")
                    .when(val -> "specialist".equals(val), "routeSpecialist")
                    .otherwise("routeGeneral");

        return builder.build();
    }

    @SuppressWarnings("preview")
    public static void main(String[] args) {
        var registry = new DefaultOperatorRegistry();
        var engine = GraphEngine.builder()
                .registry(registry)
                .interceptors(List.of())
                .listeners(List.of(new OrderProcessingExample.LoggingListener()))
                .build();

        Graph graph = buildGraph();

        var ctx = new GraphContext(Map.of(
                "patientId", "P-10042",
                "chiefComplaint", "chest pain and shortness of breath for 2 hours"
        ));

        GraphResult result = engine.executeWithOperators(graph, ctx, Map.of(
                "fetchPatientRecord", FETCH_PATIENT_RECORD,
                "fetchMedicalHistory", FETCH_MEDICAL_HISTORY,
                "analyzeSymptoms", ANALYZE_SYMPTOMS,
                "aiPreDiagnosis", AI_PRE_DIAGNOSIS,
                "triageDecision", TRIAGE_DECISION,
                "routeEmergency", ROUTE_EMERGENCY,
                "routeSpecialist", ROUTE_SPECIALIST,
                "routeGeneral", ROUTE_GENERAL
        ));

        System.out.println("\n═══ Online Triage Result ═══");
        System.out.println("Success: " + result.isSuccess());
        System.out.println("Elapsed: " + result.elapsed().toMillis() + "ms");
        System.out.println();

        for (var entry : result.statusMap().entrySet()) {
            System.out.printf("  %-25s → %s%n", entry.getKey(), entry.getValue());
        }
        System.out.println();

        if (result.getStatus("routeEmergency") == NodeStatus.COMPLETED) {
            EmergencyRouting routing = result.getOutput("routeEmergency", EmergencyRouting.class);
            System.out.println("Emergency routing: " + routing);
        } else if (result.getStatus("routeSpecialist") == NodeStatus.COMPLETED) {
            SpecialistRouting routing = result.getOutput("routeSpecialist", SpecialistRouting.class);
            System.out.println("Specialist routing: " + routing);
        } else if (result.getStatus("routeGeneral") == NodeStatus.COMPLETED) {
            GeneralRouting routing = result.getOutput("routeGeneral", GeneralRouting.class);
            System.out.println("General routing: " + routing);
        }
    }
}

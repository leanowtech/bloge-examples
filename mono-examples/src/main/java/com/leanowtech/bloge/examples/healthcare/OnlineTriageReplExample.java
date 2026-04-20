package com.leanowtech.bloge.examples.healthcare;

import java.nio.charset.StandardCharsets;
import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.engine.GraphEngine;
import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.dsl.compiler.GraphLoader;
import com.leanowtech.bloge.examples.common.LoggingListener;
import com.leanowtech.bloge.examples.common.ReplHelper;

import java.util.List;
import java.util.Map;
import java.util.Scanner;

public class OnlineTriageReplExample {

    private static final String DSL = """

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

    private static Graph buildGraph(DefaultOperatorRegistry registry) {
        registry.register("FetchPatientRecordOperator", OnlineTriageDslExample.FETCH_PATIENT_RECORD);
        registry.register("FetchMedicalHistoryOperator", OnlineTriageDslExample.FETCH_MEDICAL_HISTORY);
        registry.register("AnalyzeSymptomsOperator", OnlineTriageDslExample.ANALYZE_SYMPTOMS);
        registry.register("AiPreDiagnosisOperator", OnlineTriageDslExample.AI_PRE_DIAGNOSIS);
        registry.register("TriageDecisionOperator", OnlineTriageDslExample.TRIAGE_DECISION);
        registry.register("RouteEmergencyOperator", OnlineTriageDslExample.ROUTE_EMERGENCY);
        registry.register("RouteSpecialistOperator", OnlineTriageDslExample.ROUTE_SPECIALIST);
        registry.register("RouteGeneralOperator", OnlineTriageDslExample.ROUTE_GENERAL);
        return new GraphLoader(registry).load(DSL);
    }

    private static Map<String, Object> promptContext(Scanner scanner) {
        String patientId = ReplHelper.promptString(scanner, "patientId", "P-10042");
        String chiefComplaint = ReplHelper.promptString(scanner, "chiefComplaint", "chest pain and shortness of breath for 2 hours");
        return Map.of(
                "patientId", patientId,
                "chiefComplaint", chiefComplaint
        );
    }

    public static void main(String[] args) {
        var registry = new DefaultOperatorRegistry();
        Graph graph = buildGraph(registry);
        var engine = GraphEngine.builder()
                .registry(registry)
                .interceptors(List.of())
                .listeners(List.of(new LoggingListener()))
                .build();

        try (var scanner = new Scanner(System.in, StandardCharsets.UTF_8)) {
            boolean runAgain;
            do {
                ReplHelper.header("Online Triage REPL");
                Map<String, Object> values = promptContext(scanner);
                GraphResult result = engine.execute(graph, new GraphContext(values));
                ReplHelper.printResult(result);
                runAgain = ReplHelper.askRunAgain(scanner);
            } while (runAgain);
        }
    }
}

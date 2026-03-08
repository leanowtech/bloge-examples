package com.leanowtech.bloge.examples.healthcare;

import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.engine.GraphEngine;
import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.model.NodeStatus;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.operator.StreamingOperator;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.dsl.ast.AstNode.GraphDef;
import com.leanowtech.bloge.dsl.compiler.DslCompiler;
import com.leanowtech.bloge.dsl.lexer.Lexer;
import com.leanowtech.bloge.dsl.parser.Parser;
import com.leanowtech.bloge.examples.common.LoggingListener;

import java.util.List;
import java.util.Map;

/**
 * DSL version of the voice telemedicine consultation pipeline.
 *
 * <p>All operators use {@code Map<String,Object>} I/O and are registered by PascalCase name
 * so the DSL compiler can resolve them.  The medical-intake sub-graph is built via the Java
 * API and registered with {@link DslCompiler#registerSubGraph(String, Graph)} before
 * compiling the main DSL.
 *
 * <p>A thin {@code classifyUrgency} node is placed after {@code medicalIntake} to flatten
 * the sub-graph's terminal output ({@code urgencyAssessment}) into a top-level map suitable
 * for DSL branch evaluation.
 *
 * <p>Graph layout:
 * <pre>
 * stream audioCapture → stream speechToText
 *                               │
 *                               ▼
 *                     verifyPatientIdentity
 *                               │
 *                               ▼
 *                          medicalIntake  ←── sub-graph: medical-intake
 *                      (symptomExtraction → terminologyNormalization → urgencyAssessment)
 *                               │
 *                               ▼
 *                         classifyUrgency
 *                               │
 *           ┌───────────────────┼────────────────────┐
 *           ▼                   ▼                    ▼
 *  routeEmergencyDoctor   routeSpecialist   routeGeneralPractitioner
 *                               │
 *                               ▼ (always runs)
 *                    startConsultationRecording
 * </pre>
 *
 * <p>Run {@link #main(String[])} to compile and execute the DSL graph.
 */
@SuppressWarnings({"unchecked", "preview"})
public class VoiceTelemedicineDslExample {

    // ── Streaming operators ───────────────────────────────────────────────────

    static final StreamingOperator<Map<String, Object>, Map<String, Object>> AUDIO_CAPTURE =
            (input, channel, ctx) -> {
                System.out.println("    [AudioCapture] Starting audio capture...");
                for (int i = 0; i < 6; i++) {
                    Thread.sleep(20);
                    channel.send(Map.of("sequenceId", i, "sampleRate", 16000, "bytes", i * 1024));
                    System.out.printf("    [AudioCapture] Emitted chunk #%d%n", i);
                }
                System.out.println("    [AudioCapture] Audio capture complete");
            };

    static final StreamingOperator<Map<String, Object>, Map<String, Object>> SPEECH_TO_TEXT =
            (input, channel, ctx) -> {
                var audioChunks = (List<Map<String, Object>>) input.get("audio");
                System.out.println("    [SpeechToText] Processing " + audioChunks.size() + " audio chunks...");
                String[] words = {"chest", "pain", "shortness", "of", "breath", "palpitations"};
                int idx = 0;
                for (Map<String, Object> chunk : audioChunks) {
                    Thread.sleep(20);
                    int seqId = (Integer) chunk.get("sequenceId");
                    String word = words[idx % words.length];
                    channel.send(Map.of("sequenceId", seqId, "text", word, "confidence", 0.94 - idx * 0.01));
                    System.out.printf("    [SpeechToText] Transcribed chunk #%d → \"%s\"%n", seqId, word);
                    idx++;
                }
                System.out.println("    [SpeechToText] Transcription complete");
            };

    // ── Main-graph operators ──────────────────────────────────────────────────

    static final Operator<Map<String, Object>, Map<String, Object>> PATIENT_IDENTITY_VERIFIER = (input, ctx) -> {
        Thread.sleep(40);
        String patientId = (String) input.getOrDefault("patientId", "");
        return Map.of("patientId", patientId, "verified", true, "method", "biometric");
    };

    /**
     * Flattens the {@code urgencyAssessment} terminal output of the medical-intake sub-graph
     * into a top-level map, exposing {@code urgencyLevel} as a first-class key for DSL branch
     * evaluation.
     */
    static final Operator<Map<String, Object>, Map<String, Object>> URGENCY_CLASSIFIER = (input, ctx) -> {
        var assessment = (Map<String, Object>) input.get("assessment");
        if (assessment == null) assessment = input;
        return Map.of(
                "urgencyLevel", assessment.getOrDefault("urgencyLevel", "general"),
                "specialty", assessment.getOrDefault("specialty", "General Medicine"),
                "symptoms", assessment.getOrDefault("symptoms", List.of()));
    };

    static final Operator<Map<String, Object>, Map<String, Object>> EMERGENCY_DOCTOR_ROUTER = (input, ctx) -> {
        Thread.sleep(20);
        String patientId = (String) input.getOrDefault("patientId", "");
        return Map.of("patientId", patientId, "roomId", "ER-05", "eta", "3 minutes");
    };

    static final Operator<Map<String, Object>, Map<String, Object>> SPECIALIST_ROUTER = (input, ctx) -> {
        Thread.sleep(25);
        String patientId = (String) input.getOrDefault("patientId", "");
        String specialty = (String) input.getOrDefault("specialty", "Cardiology");
        return Map.of("patientId", patientId, "department", specialty, "doctorName", "Dr. Chen");
    };

    static final Operator<Map<String, Object>, Map<String, Object>> GENERAL_PRACTITIONER_ROUTER = (input, ctx) -> {
        Thread.sleep(20);
        String patientId = (String) input.getOrDefault("patientId", "");
        return Map.of("patientId", patientId, "queueNumber", "Q-014", "waitMinutes", 20);
    };

    static final Operator<Map<String, Object>, Map<String, Object>> CONSULTATION_RECORDER = (input, ctx) -> {
        Thread.sleep(30);
        String sessionId = (String) input.getOrDefault("sessionId", "");
        return Map.of("sessionId", sessionId, "recordingId", "REC-" + sessionId, "status", "STARTED");
    };

    // ── Medical-intake sub-graph operators ────────────────────────────────────

    static final Operator<Map<String, Object>, Map<String, Object>> SYMPTOM_EXTRACTION = (input, ctx) -> {
        Thread.sleep(50);
        return Map.of(
                "symptoms", List.of("chest pain", "shortness of breath", "palpitations"),
                "bodySystem", "cardiovascular");
    };

    static final Operator<Map<String, Object>, Map<String, Object>> TERMINOLOGY_NORMALIZATION = (input, ctx) -> {
        Thread.sleep(35);
        var symptoms = (List<?>) input.getOrDefault("symptoms", List.of());
        return Map.of(
                "symptoms", symptoms,
                "normalizedTerms", List.of("angina pectoris", "dyspnea", "palpitation"),
                "icd10Codes", List.of("I20.0", "R06.0", "R00.2"));
    };

    static final Operator<Map<String, Object>, Map<String, Object>> URGENCY_ASSESSMENT = (input, ctx) -> {
        Thread.sleep(60);
        return Map.of(
                "urgencyLevel", "specialist",
                "specialty", "Cardiology",
                "symptoms", input.getOrDefault("normalizedTerms",
                        List.of("angina pectoris", "dyspnea")));
    };

    // ── Sub-graph construction ────────────────────────────────────────────────

    public static Graph buildMedicalIntakeSubGraph() {
        Operator<Object, Object> ph = new Operator<>() {
            @Override public Object execute(Object in, com.leanowtech.bloge.core.operator.OperatorContext ctx) { return null; }
        };
        return Graph.builder("medical-intake")
                .node("symptomExtraction", ph)
                    .input((results, ctx) -> Map.of(
                            "patientId", ctx.get("patientId", String.class),
                            "transcript", ctx.containsKey("transcript")
                                    ? ctx.get("transcript", Object.class) : List.of()))
                .node("terminologyNormalization", ph)
                    .dependsOn("symptomExtraction")
                    .input((results, ctx) -> (Map<String, Object>) results.getRaw("symptomExtraction"))
                .node("urgencyAssessment", ph)
                    .dependsOn("terminologyNormalization")
                    .input((results, ctx) -> (Map<String, Object>) results.getRaw("terminologyNormalization"))
                .build();
    }

    // ── Entry point ───────────────────────────────────────────────────────────

    public static void main(String[] args) {
        var registry = new DefaultOperatorRegistry();

        // ── Operator Registrations ─────────────────────────────────────────────
        // NOTE: registerRaw is required for StreamingOperator implementations; register is for standard Operator<?,?> only

        // Streaming operators — registered by PascalCase name for DSL resolution
        // AUDIO_CAPTURE: no input → emits {sequenceId, sampleRate, bytes} chunks via NodeChannel
        registry.registerRaw("AudioCapture", AUDIO_CAPTURE);
        // SPEECH_TO_TEXT: reads input.audio (List<chunk>) → emits {sequenceId, text, confidence} tokens via NodeChannel
        registry.registerRaw("SpeechToText", SPEECH_TO_TEXT);

        // Main-graph operators
        // PATIENT_IDENTITY_VERIFIER: reads patientId, transcript → returns {patientId, verified, method}
        registry.register("PatientIdentityVerifier", PATIENT_IDENTITY_VERIFIER);
        // URGENCY_CLASSIFIER: reads assessment (Map from sub-graph terminal node) → returns {urgencyLevel, specialty, symptoms}
        registry.register("UrgencyClassifier", URGENCY_CLASSIFIER);
        // EMERGENCY_DOCTOR_ROUTER: reads patientId, urgency → returns {patientId, roomId, eta}
        registry.register("EmergencyDoctorRouter", EMERGENCY_DOCTOR_ROUTER);
        // SPECIALIST_ROUTER: reads patientId, specialty → returns {patientId, department, doctorName}
        registry.register("SpecialistRouter", SPECIALIST_ROUTER);
        // GENERAL_PRACTITIONER_ROUTER: reads patientId → returns {patientId, queueNumber, waitMinutes}
        registry.register("GeneralPractitionerRouter", GENERAL_PRACTITIONER_ROUTER);
        // CONSULTATION_RECORDER: reads patientId, sessionId, urgencyLevel → returns {sessionId, recordingId, status}
        registry.register("ConsultationRecorder", CONSULTATION_RECORDER);

        // Sub-graph node operators — registered by node id for SubGraphOperator resolution
        // SYMPTOM_EXTRACTION: reads patientId, transcript → returns {symptoms, bodySystem}
        registry.registerRaw("symptomExtraction", SYMPTOM_EXTRACTION);
        // TERMINOLOGY_NORMALIZATION: reads symptoms → returns {symptoms, normalizedTerms, icd10Codes}
        registry.registerRaw("terminologyNormalization", TERMINOLOGY_NORMALIZATION);
        // URGENCY_ASSESSMENT: reads normalizedTerms, icd10Codes → returns {urgencyLevel, specialty, symptoms}
        registry.registerRaw("urgencyAssessment", URGENCY_ASSESSMENT);

        // Build sub-graph and register it with the DslCompiler
        Graph medicalIntakeSubGraph = buildMedicalIntakeSubGraph();
        var compiler = new DslCompiler(registry);
        // register sub-graphs before loading main DSL
        compiler.registerSubGraph("medical-intake", medicalIntakeSubGraph);

        // ── DSL ───────────────────────────────────────────────────────────────
        String dsl = """
                graph voiceTelemedicine {

                  /// STREAM NODE: emits audio chunks via NodeChannel; downstream consumes without blocking this producer
                  stream node audioCapture : AudioCapture {
                    buffer = 64
                  }

                  /// STREAM NODE: emits transcription tokens via NodeChannel as audio chunks arrive
                  stream node speechToText : SpeechToText {
                    input {
                      /// .output on a stream node materialises the full List<chunk> before this operator starts
                      audio = audioCapture.output
                    }
                    buffer = 32
                  }

                  node verifyPatientIdentity : PatientIdentityVerifier {
                    depends_on = [speechToText]
                    input {
                      patientId  = ctx.patientId
                      /// .output materialises the full List<token> into a single collection before PatientIdentityVerifier runs
                      transcript = speechToText.output
                    }
                    timeout = 5s
                    retry = { attempts: 2, backoff: 500ms, strategy: exponential }
                  }

                  /// SUBGRAPH: runs medical-intake
                  /// (symptomExtraction → terminologyNormalization → urgencyAssessment); result = urgencyAssessment output
                  node medicalIntake : subgraph("medical-intake") {
                    depends_on = [verifyPatientIdentity]
                    input {
                      patientId  = ctx.patientId
                      /// .output materialises the full List<token> into a single collection before medicalIntake runs
                      transcript = speechToText.output
                      verified   = verifyPatientIdentity.output.verified
                    }
                    timeout = 30s
                  }

                  node classifyUrgency : UrgencyClassifier {
                    depends_on = [medicalIntake]
                    input {
                      assessment = medicalIntake.output.urgencyAssessment
                    }
                  }

                  node routeEmergencyDoctor : EmergencyDoctorRouter {
                    depends_on = [classifyUrgency]
                    input {
                      patientId = ctx.patientId
                      urgency   = classifyUrgency.output.urgencyLevel
                    }
                  }

                  node routeSpecialist : SpecialistRouter {
                    depends_on = [classifyUrgency]
                    input {
                      patientId = ctx.patientId
                      specialty = classifyUrgency.output.specialty
                    }
                  }

                  node routeGeneralPractitioner : GeneralPractitionerRouter {
                    depends_on = [classifyUrgency]
                    input {
                      patientId = ctx.patientId
                    }
                  }

                  /// BRANCH: evaluates urgencyLevel; exactly one care-pathway node executes, others are skipped
                  branch on classifyUrgency.output.urgencyLevel {
                    "emergency" -> routeEmergencyDoctor
                    "specialist" -> routeSpecialist
                    otherwise -> routeGeneralPractitioner
                  }

                  node startConsultationRecording : ConsultationRecorder {
                    depends_on = [classifyUrgency]
                    input {
                      patientId    = ctx.patientId
                      sessionId    = ctx.sessionId
                      urgencyLevel = classifyUrgency.output.urgencyLevel
                    }
                  }
                }
                """;

        var tokens = new Lexer(dsl).tokenize();
        GraphDef ast = new Parser(tokens).parse();
        // compile DSL; operators resolved by PascalCase name
        Graph graph = compiler.compile(ast);

        var engine = GraphEngine.builder()
                .registry(registry)
                .interceptors(List.of())
                .listeners(List.of(new LoggingListener()))
                .build();
        var ctx = new GraphContext(Map.of(
                "patientId", "P-10042",
                "sessionId", "CONSULT-001"));

        // execute; streaming results accessible via result.results()
        GraphResult result = engine.execute(graph, ctx);

        // ── Results ──────────────────────────────────────────────────────────
        System.out.println("\n═══════════════════════════════════════════════");
        System.out.println("  DSL Voice Telemedicine Result");
        System.out.println("═══════════════════════════════════════════════");
        System.out.println("  Success : " + result.isSuccess());
        System.out.println("  Elapsed : " + result.elapsed().toMillis() + " ms");
        System.out.println();

        for (var entry : result.statusMap().entrySet()) {
            System.out.printf("  %-32s → %s%n", entry.getKey(), entry.getValue());
        }
        System.out.println();

        if (result.getStatus("verifyPatientIdentity") == NodeStatus.COMPLETED) {
            System.out.println("  Identity result    : " + result.results().getRaw("verifyPatientIdentity"));
        }

        if (result.getStatus("medicalIntake") == NodeStatus.COMPLETED) {
            System.out.println("  Medical intake     : " + result.results().getRaw("medicalIntake"));
        }

        if (result.getStatus("classifyUrgency") == NodeStatus.COMPLETED) {
            var urgencyOut = (Map<String, Object>) result.results().getRaw("classifyUrgency");
            System.out.println("  Urgency classified : " + urgencyOut);
            String urgencyLevel = (String) urgencyOut.get("urgencyLevel");
            switch (urgencyLevel != null ? urgencyLevel : "general") {
                case "emergency" ->
                    System.out.println("  Emergency routing  : " + result.results().getRaw("routeEmergencyDoctor"));
                case "specialist" ->
                    System.out.println("  Specialist routing : " + result.results().getRaw("routeSpecialist"));
                default ->
                    System.out.println("  General routing    : " + result.results().getRaw("routeGeneralPractitioner"));
            }
        }

        if (result.getStatus("startConsultationRecording") == NodeStatus.COMPLETED) {
            System.out.println("  Recording          : " + result.results().getRaw("startConsultationRecording"));
        }

        if (result.getStatus("speechToText") == NodeStatus.COMPLETED) {
            System.out.println("\n  STT chunks         : "
                    + ((List<?>) result.results().getRaw("speechToText")).size());
        }

        System.out.println("═══════════════════════════════════════════════");
    }
}

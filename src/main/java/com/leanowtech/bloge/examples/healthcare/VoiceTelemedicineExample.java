package com.leanowtech.bloge.examples.healthcare;

import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.engine.GraphEngine;
import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.core.model.BackoffStrategy;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.model.NodeStatus;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.operator.OperatorLayer;
import com.leanowtech.bloge.core.operator.OperatorMeta;
import com.leanowtech.bloge.core.operator.StreamingOperator;
import com.leanowtech.bloge.core.engine.operators.SubGraphOperator;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.examples.common.LoggingListener;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Voice telemedicine consultation pipeline combining streaming audio ingestion,
 * speech-to-text transcription, patient identity verification, medical intake
 * assessment (via sub-graph), urgency-based routing, and session recording.
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
 *                               │                    │
 *           ┌───────────────────┼────────────────────┤
 *           ▼                   ▼                    ▼
 *  routeEmergencyDoctor   routeSpecialist   routeGeneralPractitioner
 *                               │
 *                               ▼ (always runs)
 *                    startConsultationRecording
 * </pre>
 *
 * <p>Run {@link #main(String[])} to execute the pipeline with a simulated consultation session.
 */
@SuppressWarnings({"unchecked", "preview"})
public class VoiceTelemedicineExample {

    // ── Domain records ────────────────────────────────────────────────────────

    public record AudioChunk(int sequenceId, byte[] samples, int sampleRate) {}

    public record TextChunk(int sequenceId, String text, double confidence) {}

    public record VerifyInput(String patientId, List<TextChunk> transcript) {}

    public record VerifyResult(String patientId, boolean verified, String method) {}

    public record IntakeInput(String patientId, List<TextChunk> transcript, boolean verified) {}

    /** urgencyLevel: "emergency" | "specialist" | "general" */
    public record IntakeResult(String urgencyLevel, String specialty, List<String> symptoms) {}

    public record EmergencyRoutingResult(String patientId, String roomId, String eta) {}

    public record SpecialistRoutingResult(String patientId, String department, String doctorName) {}

    public record GeneralRoutingResult(String patientId, String queueNumber, int waitMinutes) {}

    public record RecordingResult(String sessionId, String recordingId, String status) {}

    // ── Streaming operators ───────────────────────────────────────────────────

    @OperatorMeta(layer = OperatorLayer.INFRASTRUCTURE, tags = {"voice", "audio"},
            description = "Captures a continuous audio stream from the telephony channel", owner = "media-team")
    static final StreamingOperator<Void, AudioChunk> AUDIO_CAPTURE = (input, channel, ctx) -> {
        System.out.println("    [AudioCapture] Starting audio capture...");
        for (int i = 0; i < 6; i++) {
            Thread.sleep(20);
            var chunk = new AudioChunk(i, new byte[1024], 16000);
            channel.send(chunk);
            System.out.printf("    [AudioCapture] Emitted chunk #%d%n", i);
        }
        System.out.println("    [AudioCapture] Audio capture complete");
    };

    @OperatorMeta(layer = OperatorLayer.CAPABILITY, tags = {"voice", "stt"},
            description = "Converts audio chunks to text tokens in real-time", owner = "ai-team")
    static final StreamingOperator<List<AudioChunk>, TextChunk> SPEECH_TO_TEXT = (input, channel, ctx) -> {
        System.out.println("    [SpeechToText] Processing " + input.size() + " audio chunks...");
        String[] words = {"chest", "pain", "shortness", "of", "breath", "palpitations"};
        int idx = 0;
        for (AudioChunk chunk : input) {
            Thread.sleep(20);
            String word = words[idx % words.length];
            channel.send(new TextChunk(chunk.sequenceId(), word, 0.94 - idx * 0.01));
            System.out.printf("    [SpeechToText] Transcribed chunk #%d → \"%s\"%n", chunk.sequenceId(), word);
            idx++;
        }
        System.out.println("    [SpeechToText] Transcription complete");
    };

    // ── Main-graph operators ──────────────────────────────────────────────────

    @OperatorMeta(layer = OperatorLayer.CAPABILITY, tags = {"telemedicine", "identity"},
            description = "Verifies patient identity against the health-record system", owner = "auth-team")
    static final Operator<VerifyInput, VerifyResult> VERIFY_PATIENT = (input, ctx) -> {
        Thread.sleep(40);
        return new VerifyResult(input.patientId(), true, "biometric");
    };

    @OperatorMeta(layer = OperatorLayer.DOMAIN, tags = {"telemedicine", "routing"},
            description = "Routes the patient to the emergency department", owner = "clinical-team")
    static final Operator<IntakeResult, EmergencyRoutingResult> ROUTE_EMERGENCY = (input, ctx) -> {
        Thread.sleep(20);
        String patientId = ctx.graphContext().get("patientId", String.class);
        return new EmergencyRoutingResult(patientId, "ER-05", "3 minutes");
    };

    @OperatorMeta(layer = OperatorLayer.DOMAIN, tags = {"telemedicine", "routing"},
            description = "Routes the patient to the appropriate specialist", owner = "clinical-team")
    static final Operator<IntakeResult, SpecialistRoutingResult> ROUTE_SPECIALIST = (input, ctx) -> {
        Thread.sleep(25);
        String patientId = ctx.graphContext().get("patientId", String.class);
        return new SpecialistRoutingResult(patientId, input.specialty(), "Dr. Chen");
    };

    @OperatorMeta(layer = OperatorLayer.DOMAIN, tags = {"telemedicine", "routing"},
            description = "Routes the patient to the general-practice queue", owner = "clinical-team")
    static final Operator<IntakeResult, GeneralRoutingResult> ROUTE_GENERAL = (input, ctx) -> {
        Thread.sleep(20);
        String patientId = ctx.graphContext().get("patientId", String.class);
        return new GeneralRoutingResult(patientId, "Q-014", 20);
    };

    @OperatorMeta(layer = OperatorLayer.INFRASTRUCTURE, tags = {"telemedicine", "recording"},
            description = "Starts the consultation session recording", owner = "platform-team")
    static final Operator<Map<String, Object>, RecordingResult> START_RECORDING = (input, ctx) -> {
        Thread.sleep(30);
        String sessionId = (String) input.get("sessionId");
        return new RecordingResult(sessionId, "REC-" + sessionId, "STARTED");
    };

    // ── Medical-intake sub-graph operators ────────────────────────────────────

    @OperatorMeta(layer = OperatorLayer.CAPABILITY, tags = {"telemedicine", "nlp"},
            description = "Extracts reported symptoms from the transcribed text", owner = "ai-team")
    static final Operator<Map<String, Object>, Map<String, Object>> SYMPTOM_EXTRACTION = (input, ctx) -> {
        Thread.sleep(50);
        return Map.of(
                "symptoms", List.of("chest pain", "shortness of breath", "palpitations"),
                "bodySystem", "cardiovascular");
    };

    @OperatorMeta(layer = OperatorLayer.CAPABILITY, tags = {"telemedicine", "nlp"},
            description = "Normalises symptom descriptions to ICD-10 clinical terminology", owner = "ai-team")
    static final Operator<Map<String, Object>, Map<String, Object>> TERMINOLOGY_NORMALIZATION = (input, ctx) -> {
        Thread.sleep(35);
        var symptoms = (List<?>) input.getOrDefault("symptoms", List.of());
        return Map.of(
                "symptoms", symptoms,
                "normalizedTerms", List.of("angina pectoris", "dyspnea", "palpitation"),
                "icd10Codes", List.of("I20.0", "R06.0", "R00.2"));
    };

    @OperatorMeta(layer = OperatorLayer.DOMAIN, tags = {"telemedicine", "clinical"},
            description = "Assesses urgency level and recommends care pathway", owner = "clinical-team")
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
        return Graph.builder("medical-intake")
                .node("symptomExtraction", SYMPTOM_EXTRACTION)
                    .input((results, ctx) -> Map.of(
                            "patientId", ctx.get("patientId", String.class),
                            "transcript", ctx.containsKey("transcript")
                                    ? ctx.get("transcript", Object.class) : List.of()))
                .node("terminologyNormalization", TERMINOLOGY_NORMALIZATION)
                    .dependsOn("symptomExtraction")
                    .input((results, ctx) -> (Map<String, Object>) results.getRaw("symptomExtraction"))
                .node("urgencyAssessment", URGENCY_ASSESSMENT)
                    .dependsOn("terminologyNormalization")
                    .input((results, ctx) -> (Map<String, Object>) results.getRaw("terminologyNormalization"))
                .build();
    }

    /**
     * Builds a typed {@link Operator} that delegates to a {@link SubGraphOperator} wrapping the
     * medical-intake sub-graph and converts its terminal output to a typed {@link IntakeResult}.
     *
     * @param registry the registry containing sub-graph node operators
     */
    public static Operator<IntakeInput, IntakeResult> buildMedicalIntakeOp(DefaultOperatorRegistry registry) {
        var subOp = new SubGraphOperator(buildMedicalIntakeSubGraph(), registry);
        return (input, ctx) -> {
            var inputMap = Map.<String, Object>of(
                    "patientId", input.patientId(),
                    "transcript", input.transcript(),
                    "verified", input.verified());
            Map<String, Object> subResult = subOp.execute(inputMap, ctx);
            // urgencyAssessment is the sole terminal node; its output map is keyed by node id
            var assessment = (Map<String, Object>) subResult.get("urgencyAssessment");
            return new IntakeResult(
                    (String) assessment.get("urgencyLevel"),
                    (String) assessment.get("specialty"),
                    (List<String>) assessment.get("symptoms"));
        };
    }

    // ── Main graph construction ───────────────────────────────────────────────

    public static Graph buildGraph(Operator<IntakeInput, IntakeResult> medicalIntakeOp) {
        // Streaming node placeholder: anonymous class gives empty getSimpleName() so the engine
        // resolves the operator by node id from the registry (finding the registered StreamingOperator).
        Operator<Object, Object> streamPlaceholder = new Operator<>() {
            @Override public Object execute(Object in, com.leanowtech.bloge.core.operator.OperatorContext ctx) { return null; }
        };
        return Graph.builder("voiceTelemedicine")
                // ── Streaming pipeline ────────────────────────────────────────
                .node("audioCapture", streamPlaceholder)
                    .meta("__streaming__", "true")
                    .meta("__bufferSize__", "64")
                .node("speechToText", streamPlaceholder)
                    .dependsOn("audioCapture")
                    .meta("__streaming__", "true")
                    .meta("__bufferSize__", "32")
                    .input((results, ctx) -> (List<AudioChunk>) results.getRaw("audioCapture"))
                // ── Identity verification ─────────────────────────────────────
                .node("verifyPatientIdentity", VERIFY_PATIENT)
                    .dependsOn("speechToText")
                    .input((results, ctx) -> new VerifyInput(
                            ctx.get("patientId", String.class),
                            (List<TextChunk>) results.getRaw("speechToText")))
                    .timeout(Duration.ofSeconds(5))
                    .retry(2, Duration.ofMillis(500), BackoffStrategy.EXPONENTIAL)
                // ── Medical intake (runs medical-intake sub-graph) ────────────
                .node("medicalIntake", medicalIntakeOp)
                    .dependsOn("verifyPatientIdentity")
                    .input((results, ctx) -> {
                        var verify = results.get("verifyPatientIdentity", VerifyResult.class);
                        return new IntakeInput(
                                ctx.get("patientId", String.class),
                                (List<TextChunk>) results.getRaw("speechToText"),
                                verify.verified());
                    })
                    .timeout(Duration.ofSeconds(30))
                // ── Branch on urgencyLevel (IntakeResult record accessor) ─────
                .node("routeEmergencyDoctor", ROUTE_EMERGENCY)
                    .dependsOn("medicalIntake")
                    .input((results, ctx) -> results.get("medicalIntake", IntakeResult.class))
                .node("routeSpecialist", ROUTE_SPECIALIST)
                    .dependsOn("medicalIntake")
                    .input((results, ctx) -> results.get("medicalIntake", IntakeResult.class))
                .node("routeGeneralPractitioner", ROUTE_GENERAL)
                    .dependsOn("medicalIntake")
                    .input((results, ctx) -> results.get("medicalIntake", IntakeResult.class))
                .branch("medicalIntake")
                    .on("urgencyLevel")
                    .when(v -> "emergency".equals(v), "routeEmergencyDoctor")
                    .when(v -> "specialist".equals(v), "routeSpecialist")
                    .otherwise("routeGeneralPractitioner")
                // ── Consultation recording (always runs after intake) ─────────
                .node("startConsultationRecording", START_RECORDING)
                    .dependsOn("medicalIntake")
                    .input((results, ctx) -> {
                        var intake = results.get("medicalIntake", IntakeResult.class);
                        return Map.of(
                                "patientId", ctx.get("patientId", String.class),
                                "sessionId", ctx.get("sessionId", String.class),
                                "urgencyLevel", intake.urgencyLevel());
                    })
                .build();
    }

    // ── Entry point ───────────────────────────────────────────────────────────

    public static void main(String[] args) {
        var registry = new DefaultOperatorRegistry();

        // Streaming operators — registered by node id via registerRaw
        registry.registerRaw("audioCapture", AUDIO_CAPTURE);
        registry.registerRaw("speechToText", SPEECH_TO_TEXT);

        // Sub-graph node operators — must be registered before building the medicalIntake op
        registry.registerRaw("symptomExtraction", SYMPTOM_EXTRACTION);
        registry.registerRaw("terminologyNormalization", TERMINOLOGY_NORMALIZATION);
        registry.registerRaw("urgencyAssessment", URGENCY_ASSESSMENT);

        // Build the medicalIntake operator (wraps SubGraphOperator, returns IntakeResult)
        var medicalIntakeOp = buildMedicalIntakeOp(registry);

        // Main-graph operators
        registry.registerRaw("verifyPatientIdentity", VERIFY_PATIENT);
        registry.registerRaw("medicalIntake", medicalIntakeOp);
        registry.registerRaw("routeEmergencyDoctor", ROUTE_EMERGENCY);
        registry.registerRaw("routeSpecialist", ROUTE_SPECIALIST);
        registry.registerRaw("routeGeneralPractitioner", ROUTE_GENERAL);
        registry.registerRaw("startConsultationRecording", START_RECORDING);

        Graph graph = buildGraph(medicalIntakeOp);

        var engine = GraphEngine.builder()
                .registry(registry)
                .interceptors(List.of())
                .listeners(List.of(new LoggingListener()))
                .build();
        var ctx = new GraphContext(Map.of(
                "patientId", "P-10042",
                "sessionId", "CONSULT-001"));

        var opMap = new java.util.LinkedHashMap<String, Operator<?, ?>>();
        opMap.put("verifyPatientIdentity", VERIFY_PATIENT);
        opMap.put("medicalIntake", medicalIntakeOp);
        opMap.put("routeEmergencyDoctor", ROUTE_EMERGENCY);
        opMap.put("routeSpecialist", ROUTE_SPECIALIST);
        opMap.put("routeGeneralPractitioner", ROUTE_GENERAL);
        opMap.put("startConsultationRecording", START_RECORDING);

        GraphResult result = engine.executeWithOperators(graph, ctx, opMap);

        // ── Results ──────────────────────────────────────────────────────────
        System.out.println("\n═══════════════════════════════════════════════");
        System.out.println("  Voice Telemedicine Result");
        System.out.println("═══════════════════════════════════════════════");
        System.out.println("  Success : " + result.isSuccess());
        System.out.println("  Elapsed : " + result.elapsed().toMillis() + " ms");
        System.out.println();

        for (var entry : result.statusMap().entrySet()) {
            System.out.printf("  %-32s → %s%n", entry.getKey(), entry.getValue());
        }
        System.out.println();

        if (result.getStatus("verifyPatientIdentity") == NodeStatus.COMPLETED) {
            VerifyResult verify = result.getOutput("verifyPatientIdentity", VerifyResult.class);
            System.out.println("  Identity verified  : " + verify.verified() + " (" + verify.method() + ")");
        }

        if (result.getStatus("medicalIntake") == NodeStatus.COMPLETED) {
            IntakeResult intake = result.getOutput("medicalIntake", IntakeResult.class);
            System.out.println("  Urgency level      : " + intake.urgencyLevel());
            System.out.println("  Specialty          : " + intake.specialty());
            System.out.println("  Symptoms           : " + intake.symptoms());
            switch (intake.urgencyLevel()) {
                case "emergency" -> {
                    EmergencyRoutingResult routing = result.getOutput("routeEmergencyDoctor", EmergencyRoutingResult.class);
                    System.out.println("  Emergency routing  : " + routing);
                }
                case "specialist" -> {
                    SpecialistRoutingResult routing = result.getOutput("routeSpecialist", SpecialistRoutingResult.class);
                    System.out.println("  Specialist routing : " + routing);
                }
                default -> {
                    GeneralRoutingResult routing = result.getOutput("routeGeneralPractitioner", GeneralRoutingResult.class);
                    System.out.println("  General routing    : " + routing);
                }
            }
        }

        if (result.getStatus("startConsultationRecording") == NodeStatus.COMPLETED) {
            RecordingResult rec = result.getOutput("startConsultationRecording", RecordingResult.class);
            System.out.println("  Recording          : " + rec);
        }

        if (result.getStatus("audioCapture") == NodeStatus.COMPLETED) {
            System.out.println("\n  audioCapture chunks  : "
                    + ((List<?>) result.results().getRaw("audioCapture")).size());
        }
        if (result.getStatus("speechToText") == NodeStatus.COMPLETED) {
            System.out.println("  speechToText chunks  : "
                    + ((List<?>) result.results().getRaw("speechToText")).size());
        }

        System.out.println("═══════════════════════════════════════════════");
    }
}

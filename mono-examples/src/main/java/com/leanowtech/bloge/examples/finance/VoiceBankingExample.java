package com.leanowtech.bloge.examples.finance;

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
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.examples.common.LoggingListener;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Voice banking workflow demonstrating streaming audio capture, voiceprint
 * authentication, real-time speech-to-text, intent detection, and
 * transactional branching — all using the Java fluent API.
 *
 * <p>Graph layout:
 * <pre>
 * stream audioCapture
 *   → voiceprintAuth (timeout=10s, retry=2/1s/FIXED)
 *   → stream speechToText
 *   → intentDetection
 *   → txnContext (aggregates auth + intent)
 *      → (transfer)  executeTransfer (timeout=15s)
 *      → (balance)   queryBalance
 *      → (otherwise) routeToAgent
 * intentDetection → complianceRecording (fallback)
 * </pre>
 *
 * <p>Run {@link #main(String[])} to execute the graph with a simulated
 * voice banking session for account {@code ACC-9921}.
 */
@SuppressWarnings({"unchecked", "preview"})
public class VoiceBankingExample {

    // ── Domain records ────────────────────────────────────────────────────────

    public record AudioChunk(int sequenceId, byte[] samples, int sampleRate) {}
    public record AuthInput(String accountId, List<AudioChunk> audio) {}
    public record AuthResult(String accountId, String authToken, double confidenceScore, boolean authenticated) {}
    public record TextChunk(int sequenceId, String text, double confidence) {}
    public record IntentInput(List<TextChunk> transcript) {}
    public record IntentResult(String intent, String rawText, double amount, String targetAccount) {}
    public record TxnContext(String accountId, String authToken, double authScore,
                             String intent, double amount, String targetAccount) {}
    public record TransferResult(String txnId, String status, double amount) {}
    public record BalanceResult(String accountId, double balance, String currency) {}
    public record AgentRoutingResult(String agentId, String channel) {}
    public record ComplianceRecord(String sessionId, String status, String recordingId) {}

    // ── Streaming operators ───────────────────────────────────────────────────

    @OperatorMeta(layer = OperatorLayer.INFRASTRUCTURE, tags = {"voice", "audio"},
            description = "Captures a continuous audio stream from the banking telephony channel",
            owner = "media-team")
    static final StreamingOperator<Void, AudioChunk> AUDIO_CAPTURE = (input, channel, ctx) -> {
        System.out.println("    [AudioCapture] Starting audio capture...");
        for (int i = 0; i < 8; i++) {
            Thread.sleep(20);
            var chunk = new AudioChunk(i, new byte[320], 16000);
            channel.send(chunk);
            System.out.printf("    [AudioCapture] Emitted chunk #%d%n", i);
        }
        System.out.println("    [AudioCapture] Audio capture complete");
    };

    @OperatorMeta(layer = OperatorLayer.CAPABILITY, tags = {"voice", "stt"},
            description = "Converts banking audio chunks to text tokens in real-time",
            owner = "ai-team")
    static final StreamingOperator<List<AudioChunk>, TextChunk> SPEECH_TO_TEXT = (input, channel, ctx) -> {
        System.out.println("    [SpeechToText] Processing " + input.size() + " audio chunks...");
        String[] words = {"transfer", "five", "hundred", "dollars", "to", "account", "nine", "nine"};
        int idx = 0;
        for (AudioChunk chunk : input) {
            Thread.sleep(20);
            String word = words[idx % words.length];
            var textChunk = new TextChunk(chunk.sequenceId(), word, 0.95 - idx * 0.01);
            channel.send(textChunk);
            System.out.printf("    [SpeechToText] Transcribed chunk #%d → \"%s\"%n",
                    chunk.sequenceId(), word);
            idx++;
        }
        System.out.println("    [SpeechToText] Transcription complete");
    };

    // ── Normal operators ──────────────────────────────────────────────────────

    @OperatorMeta(layer = OperatorLayer.CAPABILITY, tags = {"voice", "auth", "biometric"},
            description = "Authenticates the caller via voiceprint analysis",
            owner = "security-team")
    static final Operator<AuthInput, AuthResult> VOICEPRINT_AUTH = (input, ctx) -> {
        Thread.sleep(60);
        if (input.audio() == null || input.audio().isEmpty()) {
            throw new IllegalStateException("No audio data available for authentication");
        }
        return new AuthResult(input.accountId(), "TOKEN-" + input.accountId(), 0.94, true);
    };

    @OperatorMeta(layer = OperatorLayer.CAPABILITY, tags = {"voice", "nlp", "intent"},
            description = "Detects banking intent from the materialised transcript",
            owner = "ai-team")
    static final Operator<IntentInput, IntentResult> INTENT_DETECTOR = (input, ctx) -> {
        Thread.sleep(35);
        String rawText = input.transcript().stream()
                .map(TextChunk::text)
                .reduce("", (a, b) -> a.isEmpty() ? b : a + " " + b);
        String lower = rawText.toLowerCase();
        if (lower.contains("transfer")) {
            return new IntentResult("transfer", rawText, 500.0, "99");
        } else if (lower.contains("balance")) {
            return new IntentResult("balance", rawText, 0.0, "");
        } else {
            return new IntentResult("agent", rawText, 0.0, "");
        }
    };

    @OperatorMeta(layer = OperatorLayer.DOMAIN, tags = {"banking", "context"},
            description = "Aggregates authentication and intent results into a transaction context",
            owner = "banking-team")
    static final Operator<Map<String, Object>, TxnContext> TXN_CONTEXT_BUILDER = (input, ctx) -> {
        Thread.sleep(15);
        var auth = (AuthResult) input.get("auth");
        var intent = (IntentResult) input.get("intent");
        return new TxnContext(auth.accountId(), auth.authToken(), auth.confidenceScore(),
                intent.intent(), intent.amount(), intent.targetAccount());
    };

    @OperatorMeta(layer = OperatorLayer.DOMAIN, tags = {"banking", "transfer"},
            description = "Executes a funds transfer for the authenticated session",
            owner = "payments-team")
    static final Operator<TxnContext, TransferResult> EXECUTE_TRANSFER = (input, ctx) -> {
        Thread.sleep(40);
        return new TransferResult("TXN-" + input.accountId() + "-001", "completed", input.amount());
    };

    @OperatorMeta(layer = OperatorLayer.DOMAIN, tags = {"banking", "balance"},
            description = "Queries the account balance for the authenticated session",
            owner = "accounts-team")
    static final Operator<TxnContext, BalanceResult> QUERY_BALANCE = (input, ctx) -> {
        Thread.sleep(30);
        return new BalanceResult(input.accountId(), 4250.75, "USD");
    };

    @OperatorMeta(layer = OperatorLayer.DOMAIN, tags = {"banking", "routing"},
            description = "Routes the caller to a live banking agent",
            owner = "contact-centre-team")
    static final Operator<TxnContext, AgentRoutingResult> ROUTE_TO_AGENT = (input, ctx) -> {
        Thread.sleep(20);
        return new AgentRoutingResult("AGENT-FIN-07", "voice");
    };

    @OperatorMeta(layer = OperatorLayer.INFRASTRUCTURE, tags = {"banking", "compliance"},
            description = "Records the session for regulatory compliance",
            owner = "compliance-team")
    static final Operator<Map<String, Object>, ComplianceRecord> COMPLIANCE_RECORDER = (input, ctx) -> {
        Thread.sleep(25);
        String sessionId = (String) input.getOrDefault("sessionId", "UNKNOWN");
        String intent = (String) input.getOrDefault("intent", "unknown");
        return new ComplianceRecord(sessionId, "recorded", "REC-" + sessionId + "-" + intent);
    };

    // ── Graph construction ────────────────────────────────────────────────────

    public static Graph buildGraph() {
        return Graph.builder("voiceBanking")
                .node("audioCapture", (input, ctx) -> null)
                    .meta("__streaming__", "true")
                    .meta("__bufferSize__", "32")
                .node("voiceprintAuth", VOICEPRINT_AUTH)
                    .dependsOn("audioCapture")
                    .input((results, ctx) -> new AuthInput(
                            ctx.get("accountId", String.class),
                            (List<AudioChunk>) results.getRaw("audioCapture")))
                    .timeout(Duration.ofSeconds(10))
                    .retry(2, Duration.ofSeconds(1), BackoffStrategy.FIXED)
                .node("speechToText", (input, ctx) -> null)
                    .dependsOn("voiceprintAuth")
                    .meta("__streaming__", "true")
                    .meta("__bufferSize__", "32")
                    .input((results, ctx) -> (List<AudioChunk>) results.getRaw("audioCapture"))
                .node("intentDetection", INTENT_DETECTOR)
                    .dependsOn("speechToText")
                    .input((results, ctx) -> new IntentInput(
                            (List<TextChunk>) results.getRaw("speechToText")))
                .node("txnContext", TXN_CONTEXT_BUILDER)
                    .dependsOn("intentDetection", "voiceprintAuth")
                    .input((results, ctx) -> Map.of(
                            "auth", results.get("voiceprintAuth", AuthResult.class),
                            "intent", results.get("intentDetection", IntentResult.class)))
                .node("executeTransfer", EXECUTE_TRANSFER)
                    .dependsOn("txnContext")
                    .input((results, ctx) -> results.get("txnContext", TxnContext.class))
                    .timeout(Duration.ofSeconds(15))
                .node("queryBalance", QUERY_BALANCE)
                    .dependsOn("txnContext")
                    .input((results, ctx) -> results.get("txnContext", TxnContext.class))
                .node("routeToAgent", ROUTE_TO_AGENT)
                    .dependsOn("txnContext")
                    .input((results, ctx) -> results.get("txnContext", TxnContext.class))
                .branch("txnContext")
                    .on("intent")
                    .when(val -> "transfer".equals(val), "executeTransfer")
                    .when(val -> "balance".equals(val), "queryBalance")
                    .otherwise("routeToAgent")
                .node("complianceRecording", COMPLIANCE_RECORDER)
                    .dependsOn("intentDetection")
                    .input((results, ctx) -> Map.of(
                            "sessionId", ctx.get("sessionId", String.class),
                            "intent", results.get("intentDetection", IntentResult.class).intent()))
                    .fallback(ex -> new ComplianceRecord("SESS-001", "recorded_partial", "FALLBACK"))
                .build();
    }

    // ── Entry point ───────────────────────────────────────────────────────────

    public static void main(String[] args) {
        var registry = new DefaultOperatorRegistry();

        registry.registerRaw("audioCapture", AUDIO_CAPTURE);
        registry.registerRaw("speechToText", SPEECH_TO_TEXT);
        registry.registerRaw("voiceprintAuth", VOICEPRINT_AUTH);
        registry.registerRaw("intentDetection", INTENT_DETECTOR);
        registry.registerRaw("txnContext", TXN_CONTEXT_BUILDER);
        registry.registerRaw("executeTransfer", EXECUTE_TRANSFER);
        registry.registerRaw("queryBalance", QUERY_BALANCE);
        registry.registerRaw("routeToAgent", ROUTE_TO_AGENT);
        registry.registerRaw("complianceRecording", COMPLIANCE_RECORDER);

        var engine = GraphEngine.builder()
                .registry(registry)
                .interceptors(List.of())
                .listeners(List.of(new LoggingListener()))
                .build();
        Graph graph = buildGraph();

        var ctx = new GraphContext(Map.of(
                "accountId", "ACC-9921",
                "sessionId", "VB-001"
        ));

        GraphResult result = engine.execute(graph, ctx);

        System.out.println("\n═══ Voice Banking Result ═══");
        System.out.println("Success: " + result.isSuccess());
        System.out.println("Elapsed: " + result.elapsed().toMillis() + "ms");
        System.out.println();

        for (var entry : result.statusMap().entrySet()) {
            System.out.printf("  %-24s → %s%n", entry.getKey(), entry.getValue());
        }
        System.out.println();

        if (result.getStatus("voiceprintAuth") == NodeStatus.COMPLETED) {
            AuthResult auth = result.getOutput("voiceprintAuth", AuthResult.class);
            System.out.println("Auth           : token=" + auth.authToken()
                    + ", score=" + auth.confidenceScore()
                    + ", ok=" + auth.authenticated());
        }

        if (result.getStatus("intentDetection") == NodeStatus.COMPLETED) {
            IntentResult intent = result.getOutput("intentDetection", IntentResult.class);
            System.out.println("Intent         : " + intent.intent()
                    + " | amount=" + intent.amount()
                    + " | target=" + intent.targetAccount());
        }

        if (result.getStatus("executeTransfer") == NodeStatus.COMPLETED) {
            TransferResult transfer = result.getOutput("executeTransfer", TransferResult.class);
            System.out.println("Transfer       : " + transfer);
        } else if (result.getStatus("queryBalance") == NodeStatus.COMPLETED) {
            BalanceResult balance = result.getOutput("queryBalance", BalanceResult.class);
            System.out.println("Balance        : " + balance);
        } else if (result.getStatus("routeToAgent") == NodeStatus.COMPLETED) {
            AgentRoutingResult routing = result.getOutput("routeToAgent", AgentRoutingResult.class);
            System.out.println("Agent routing  : " + routing);
        }

        if (result.getStatus("complianceRecording") == NodeStatus.COMPLETED) {
            ComplianceRecord compliance = result.getOutput("complianceRecording", ComplianceRecord.class);
            System.out.println("Compliance     : " + compliance);
        }

        if (result.getStatus("audioCapture") == NodeStatus.COMPLETED) {
            System.out.println("\naudioCapture chunks  : "
                    + ((List<?>) result.results().getRaw("audioCapture")).size());
        }
        if (result.getStatus("speechToText") == NodeStatus.COMPLETED) {
            System.out.println("speechToText chunks  : "
                    + ((List<?>) result.results().getRaw("speechToText")).size());
        }
    }
}

package com.leanowtech.bloge.examples.finance;

import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.engine.GraphEngine;
import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.model.NodeStatus;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.operator.StreamingOperator;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.dsl.compiler.GraphLoader;
import com.leanowtech.bloge.examples.common.LoggingListener;

import java.util.List;
import java.util.Map;

/**
 * DSL version of the voice banking workflow.
 *
 * <p>All operators use {@code Map<String,Object>} I/O so they can be registered
 * by PascalCase name and resolved by the DSL compiler.  The workflow compiles
 * a {@code voiceBanking} graph from an inline DSL string and executes it with
 * simulated audio, voiceprint authentication, and banking intent routing.
 *
 * <p>Graph layout:
 * <pre>
 * stream audioCapture
 *   → voiceprintAuth (timeout=10s, retry=2/1s/fixed)
 *   → stream speechToText
 *   → intentDetection (timeout=5s)
 *   → transform txnContext (aggregates accountId, authToken, authScore, intent, amount, targetAccount)
 *      → (transfer)  executeTransfer (timeout=15s)
 *      → (balance)   queryBalance
 *      → (otherwise) routeToAgent
 * intentDetection → complianceRecording (fallback)
 * </pre>
 *
 * <p>Run {@link #main(String[])} to compile and execute the DSL graph.
 */
@SuppressWarnings({"unchecked", "preview"})
public class VoiceBankingDslExample {

    // ── Streaming operators ───────────────────────────────────────────────────

    // Streaming operators must use registerRaw() — the engine checks instanceof StreamingOperator
    // to route to the streaming execution path; register() only accepts Operator<?,?>.

    static final StreamingOperator<Map<String, Object>, Map<String, Object>> AUDIO_CAPTURE =
            (input, channel, ctx) -> {
                // Emits 8 audio chunks at 16 kHz (20 ms each, 320 bytes per chunk).
                // These are fed to both voiceprintAuth (via .output) and speechToText.
                System.out.println("    [AudioCapture] Starting audio capture...");
                for (int i = 0; i < 8; i++) {
                    Thread.sleep(20);
                    channel.send(Map.of("sequenceId", i, "sampleRate", 16000, "bytes", i * 320));
                    System.out.printf("    [AudioCapture] Emitted chunk #%d%n", i);
                }
                System.out.println("    [AudioCapture] Audio capture complete");
            };

    static final StreamingOperator<Map<String, Object>, Map<String, Object>> SPEECH_TO_TEXT =
            (input, channel, ctx) -> {
                // input.get("audio") is the materialized List from audioCapture.output (DirectEdge).
                // Simulates "transfer five hundred dollars to account ninety-nine" — drives
                // the INTENT_DETECTOR to return intent="transfer".
                var audioChunks = (List<Map<String, Object>>) input.get("audio");
                System.out.println("    [SpeechToText] Processing " + audioChunks.size() + " audio chunks...");
                String[] words = {"transfer", "five", "hundred", "dollars", "to", "account", "nine", "nine"};
                int idx = 0;
                for (Map<String, Object> chunk : audioChunks) {
                    Thread.sleep(20);
                    int seqId = (Integer) chunk.get("sequenceId");
                    String word = words[idx % words.length];
                    channel.send(Map.of("sequenceId", seqId, "text", word, "confidence", 0.95 - idx * 0.01));
                    System.out.printf("    [SpeechToText] Transcribed chunk #%d → \"%s\"%n", seqId, word);
                    idx++;
                }
                System.out.println("    [SpeechToText] Transcription complete");
            };

    // ── Normal operators ──────────────────────────────────────────────────────

    static final Operator<Map<String, Object>, Map<String, Object>> VOICEPRINT_AUTH = (input, ctx) -> {
        // input.get("audio") = materialized List<Map> from audioCapture.output.
        // Validates that audio is present; in production this would call a biometric API.
        // Returns { accountId, authToken, confidenceScore, authenticated } —
        // authToken and confidenceScore are projected into txnContext by the transform block.
        Thread.sleep(60);
        var audioList = (List<?>) input.get("audio");
        if (audioList == null || audioList.isEmpty()) {
            throw new IllegalStateException("No audio data available for authentication");
        }
        String accountId = (String) input.getOrDefault("accountId", "UNKNOWN");
        return Map.of(
                "accountId", accountId,
                "authToken", "TOKEN-" + accountId,
                "confidenceScore", 0.94,
                "authenticated", true);
    };

    static final Operator<Map<String, Object>, Map<String, Object>> INTENT_DETECTOR = (input, ctx) -> {
        // input.get("transcript") = List<Map> from speechToText.output.
        // Joins token texts into a raw sentence and applies keyword classification.
        // Returns { intent, rawText, amount, targetAccount } consumed by the transform.
        Thread.sleep(35);
        var chunks = (List<Map<String, Object>>) input.get("transcript");
        if (chunks == null) chunks = List.of();
        String rawText = chunks.stream()
                .map(c -> (String) c.getOrDefault("text", ""))
                .reduce("", (a, b) -> a.isEmpty() ? b : a + " " + b);
        String lower = rawText.toLowerCase();
        if (lower.contains("transfer")) {
            return Map.of("intent", "transfer", "rawText", rawText, "amount", 500.0, "targetAccount", "99");
        } else if (lower.contains("balance")) {
            return Map.of("intent", "balance", "rawText", rawText, "amount", 0.0, "targetAccount", "");
        } else {
            return Map.of("intent", "agent", "rawText", rawText, "amount", 0.0, "targetAccount", "");
        }
    };

    static final Operator<Map<String, Object>, Map<String, Object>> EXECUTE_TRANSFER = (input, ctx) -> {
        Thread.sleep(40);
        String accountId = (String) input.getOrDefault("accountId", "UNKNOWN");
        double amount = ((Number) input.getOrDefault("amount", 0.0)).doubleValue();
        return Map.of(
                "txnId", "TXN-" + accountId + "-001",
                "status", "completed",
                "amount", amount);
    };

    static final Operator<Map<String, Object>, Map<String, Object>> QUERY_BALANCE = (input, ctx) -> {
        Thread.sleep(30);
        String accountId = (String) input.getOrDefault("accountId", "UNKNOWN");
        return Map.of(
                "accountId", accountId,
                "balance", 4250.75,
                "currency", "USD");
    };

    static final Operator<Map<String, Object>, Map<String, Object>> ROUTE_TO_AGENT = (input, ctx) -> {
        Thread.sleep(20);
        return Map.of(
                "agentId", "AGENT-FIN-07",
                "channel", "voice");
    };

    static final Operator<Map<String, Object>, Map<String, Object>> COMPLIANCE_RECORDER = (input, ctx) -> {
        Thread.sleep(25);
        String sessionId = (String) input.getOrDefault("sessionId", "UNKNOWN");
        String intent = (String) input.getOrDefault("intent", "unknown");
        return Map.of(
                "sessionId", sessionId,
                "status", "recorded",
                "recordingId", "REC-" + sessionId + "-" + intent);
    };

    // ── Entry point ───────────────────────────────────────────────────────────

    public static void main(String[] args) {
        var registry = new DefaultOperatorRegistry();

        // Streaming operators must use registerRaw with PascalCase name
        registry.registerRaw("AudioCapture", AUDIO_CAPTURE);
        registry.registerRaw("SpeechToText", SPEECH_TO_TEXT);

        // Regular operators registered by PascalCase DSL operator name
        registry.register("VoiceprintAuthenticator", VOICEPRINT_AUTH);
        registry.register("BankingIntentDetector", INTENT_DETECTOR);
        registry.register("TransferExecutor", EXECUTE_TRANSFER);
        registry.register("BalanceQuerier", QUERY_BALANCE);
        registry.register("AgentRouter", ROUTE_TO_AGENT);
        registry.register("ComplianceRecorder", COMPLIANCE_RECORDER);

        var loader = new GraphLoader(registry);

        String dsl = """
                graph voiceBanking {

                  /// Audio source: emits chunks at 16 kHz consumed by both auth and STT.
                  stream node audioCapture : AudioCapture {
                    buffer = 64
                  }

                  /// Voiceprint authentication gates the rest of the pipeline.
                  /// retry = fixed: retries up to 2× with a constant 1-second wait between
                  /// attempts; if all attempts fail the node fails and the graph stops.
                  node voiceprintAuth : VoiceprintAuthenticator {
                    depends_on = [audioCapture]
                    input {
                      audio     = audioCapture.output
                      accountId = ctx.accountId
                    }
                    timeout = 10s
                    retry = { attempts: 2, backoff: 1s, strategy: fixed }
                  }

                  /// Transcription only starts after auth succeeds (depends_on voiceprintAuth).
                  /// Re-uses the same audio buffer from audioCapture.output.
                  stream node speechToText : SpeechToText {
                    depends_on = [voiceprintAuth]
                    input {
                      audio = audioCapture.output
                    }
                    buffer = 32
                  }

                  /// Classifies the banking intent from the transcript text tokens.
                  /// Returns { intent, amount, targetAccount } used by the transform below.
                  node intentDetection : BankingIntentDetector {
                    depends_on = [speechToText]
                    input {
                      transcript = speechToText.output
                    }
                    timeout = 5s
                  }

                  /// Aggregates auth and intent fields into a single transaction context.
                  /// transform is a zero-cost projection — no operator is executed;
                  /// the engine resolves fields from upstream node outputs at graph evaluation.
                  transform txnContext {
                    accountId     = ctx.accountId
                    authToken     = voiceprintAuth.output.authToken
                    authScore     = voiceprintAuth.output.confidenceScore
                    intent        = intentDetection.output.intent
                    amount        = intentDetection.output.amount
                    targetAccount = intentDetection.output.targetAccount
                  }

                  /// Fund transfer — active only when txnContext.intent = "transfer".
                  node executeTransfer : TransferExecutor {
                    depends_on = [txnContext]
                    input {
                      accountId     = txnContext.accountId
                      authToken     = txnContext.authToken
                      amount        = txnContext.amount
                      targetAccount = txnContext.targetAccount
                    }
                    timeout = 15s
                  }

                  /// Balance query — active only when txnContext.intent = "balance".
                  node queryBalance : BalanceQuerier {
                    depends_on = [txnContext]
                    input {
                      accountId = txnContext.accountId
                      authToken = txnContext.authToken
                    }
                  }

                  /// Live-agent routing — catch-all for unrecognised intents.
                  node routeToAgent : AgentRouter {
                    depends_on = [txnContext]
                    input {
                      accountId = txnContext.accountId
                    }
                  }

                  /// Routes to exactly one transaction node; remaining nodes are SKIPPED.
                  branch on txnContext.intent {
                    "transfer" -> executeTransfer
                    "balance"  -> queryBalance
                    otherwise  -> routeToAgent
                  }

                  /// Compliance recording runs in parallel with the branch.
                  /// fallback = { } means: if the recorder operator throws, substitute the
                  /// static JSON as the node result instead of failing the entire graph.
                  node complianceRecording : ComplianceRecorder {
                    depends_on = [intentDetection]
                    input {
                      accountId = ctx.accountId
                      intent    = intentDetection.output.intent
                      sessionId = ctx.sessionId
                    }
                    fallback = { status: "recorded_partial", recordingId: "FALLBACK" }
                  }
                }
                """;

        Graph graph = loader.load(dsl);

        var engine = GraphEngine.builder()
                .registry(registry)
                .interceptors(List.of())
                .listeners(List.of(new LoggingListener()))
                .build();
        var ctx = new GraphContext(Map.of(
                "accountId", "ACC-9921",
                "sessionId", "VB-001"
        ));

        GraphResult result = engine.execute(graph, ctx);

        System.out.println("\n═══ DSL Voice Banking Result ═══");
        System.out.println("Success: " + result.isSuccess());
        System.out.println("Elapsed: " + result.elapsed().toMillis() + "ms");
        System.out.println();

        for (var entry : result.statusMap().entrySet()) {
            System.out.printf("  %-24s → %s%n", entry.getKey(), entry.getValue());
        }
        System.out.println();

        if (result.getStatus("voiceprintAuth") == NodeStatus.COMPLETED) {
            System.out.println("Auth           : " + result.results().getRaw("voiceprintAuth"));
        }

        if (result.getStatus("intentDetection") == NodeStatus.COMPLETED) {
            System.out.println("Intent         : " + result.results().getRaw("intentDetection"));
        }

        if (result.getStatus("txnContext") == NodeStatus.COMPLETED) {
            System.out.println("TxnContext     : " + result.results().getRaw("txnContext"));
        }

        if (result.getStatus("executeTransfer") == NodeStatus.COMPLETED) {
            System.out.println("Transfer       : " + result.results().getRaw("executeTransfer"));
        } else if (result.getStatus("queryBalance") == NodeStatus.COMPLETED) {
            System.out.println("Balance        : " + result.results().getRaw("queryBalance"));
        } else if (result.getStatus("routeToAgent") == NodeStatus.COMPLETED) {
            System.out.println("Agent routing  : " + result.results().getRaw("routeToAgent"));
        }

        if (result.getStatus("complianceRecording") == NodeStatus.COMPLETED) {
            System.out.println("Compliance     : " + result.results().getRaw("complianceRecording"));
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

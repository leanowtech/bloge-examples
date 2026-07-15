package com.leanowtech.bloge.gateway.testkit;

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** Writes payload-free test-run summaries as CI-compatible JUnit XML. */
public final class JUnitXmlReportWriter {

    /**
     * Report counters and process-exit projection.
     *
     * @param tests emitted testcase count
     * @param failures emitted failure count
     */
    public record Report(int tests, int failures) {
        /**
         * Projects the report outcome to a conventional process exit code.
         *
         * @return zero when every reported run passed, otherwise one
         */
        public int exitCode() {
            return failures == 0 ? 0 : 1;
        }
    }

    private JUnitXmlReportWriter() {
    }

    /**
     * Writes one testcase per run. Only ids, states, and fingerprints are emitted; diagnostics,
     * node input/output, and the raw response are intentionally excluded.
     *
     * @param output destination XML file
     * @param suiteName CI-visible suite name
     * @param runs ordered run results
     * @return emitted report counters
     * @throws IOException when directories or XML cannot be written
     */
    public static Report write(Path output, String suiteName, List<TestRun> runs) throws IOException {
        Objects.requireNonNull(output, "output");
        List<TestRun> values = runs == null ? List.of() : List.copyOf(runs);
        int failures = (int) values.stream().filter(run -> !run.passed()).count();
        Path parent = output.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        XMLOutputFactory factory = XMLOutputFactory.newFactory();
        try (OutputStream stream = Files.newOutputStream(output)) {
            XMLStreamWriter xml = factory.createXMLStreamWriter(stream, StandardCharsets.UTF_8.name());
            xml.writeStartDocument(StandardCharsets.UTF_8.name(), "1.0");
            xml.writeStartElement("testsuite");
            xml.writeAttribute("name", bounded(suiteName, 512));
            xml.writeAttribute("tests", Integer.toString(values.size()));
            xml.writeAttribute("failures", Integer.toString(failures));
            xml.writeAttribute("errors", "0");
            xml.writeAttribute("skipped", "0");
            for (int index = 0; index < values.size(); index++) {
                writeRun(xml, values.get(index), index);
            }
            xml.writeEndElement();
            xml.writeEndDocument();
            xml.flush();
            xml.close();
        } catch (XMLStreamException failure) {
            throw new IOException("Unable to write Resource Gateway JUnit XML", failure);
        }
        return new Report(values.size(), failures);
    }

    private static void writeRun(XMLStreamWriter xml, TestRun run, int index) throws XMLStreamException {
        xml.writeStartElement("testcase");
        xml.writeAttribute("classname", "resource-gateway.graph-contract");
        xml.writeAttribute("name", bounded(run.runId().isBlank() ? "run-" + index : run.runId(), 512));
        if (!run.passed()) {
            xml.writeStartElement("failure");
            xml.writeAttribute("type", "ResourceGateway." + run.status());
            xml.writeAttribute("message", "Run " + bounded(run.runId(), 256)
                    + " completed with status " + run.status());
            xml.writeCharacters("Inspect the authorized testing API using runId="
                    + bounded(run.runId(), 256) + ".");
            xml.writeEndElement();
        }
        xml.writeStartElement("system-out");
        xml.writeCharacters("runId=" + bounded(run.runId(), 256)
                + "; status=" + run.status()
                + "; evidenceClass=" + run.evidenceClass()
                + "; targetFingerprint=" + bounded(run.targetFingerprint(), 80)
                + "; fixtureBundleFingerprint=" + bounded(run.fixtureBundleFingerprint(), 80)
                + "; planFingerprint=" + bounded(run.planFingerprint(), 80));
        xml.writeEndElement();
        xml.writeEndElement();
    }

    private static String bounded(String value, int maximum) {
        String normalized = value == null ? "" : value.trim();
        return normalized.length() <= maximum ? normalized : normalized.substring(0, maximum);
    }
}

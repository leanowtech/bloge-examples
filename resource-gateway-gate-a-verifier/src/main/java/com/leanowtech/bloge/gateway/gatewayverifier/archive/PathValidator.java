package com.leanowtech.bloge.gateway.gatewayverifier.archive;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Map;
import java.util.Objects;

/**
 * Strict path validator for ZIP entry names.
 *
 * Validates each entry name against a set of path-safety rules:
 * - AK-PATH-NUL: raw byte 0x00 in name
 * - AK-PATH-ABSOLUTE: starts with /
 * - AK-PATH-BACKSLASH: contains \
 * - AK-PATH-DOT-SEGMENT: contains . or .. as a path segment
 * - AK-PATH-NFC-MISMATCH: UTF-8 bytes not in NFC form
 *
 * No filesystem operations are performed. All args in result maps use only
 * stable, UTF-8-decoded or explicitly constructed values — no free-text
 * system messages are included.
 */
public final class PathValidator {

    private static final byte NUL = 0x00;
    private static final byte BACKSLASH = 0x5C;

    private PathValidator() {}

    /**
     * Validates a raw entry name.
     *
     * <p>Priority: NUL → UTF-8 decode → absolute → backslash → dot-segment → NFC.
     *
     * @param nameRaw raw byte array; must not be null
     * @return immutable PathCheckResult
     * @throws NullPointerException if nameRaw is null
     */
    public static PathCheckResult validate(byte[] nameRaw) {
        Objects.requireNonNull(nameRaw, "nameRaw must not be null");

        // 1. NUL byte check
        int nulIndex = indexOf(nameRaw, NUL);
        if (nulIndex >= 0) {
            return buildNulFailure(nameRaw);
        }

        // 2. Strict UTF-8 decode — REPORT on any malformation
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        String nameUtf8;
        try {
            nameUtf8 = decoder.decode(ByteBuffer.wrap(nameRaw)).toString();
        } catch (CharacterCodingException e) {
            // Decode failure maps to AK-PATH-NFC-MISMATCH.
            // Args contain only stable partial-decoded values — no exception text.
            return buildDecodeFailure(nameRaw);
        }

        // 3. Absolute path check
        if (nameUtf8.startsWith("/")) {
            return buildFailure(nameRaw, nameUtf8, "AK-PATH-ABSOLUTE",
                    Map.of("AK-PATH-ABSOLUTE", Map.of("entryName", nameUtf8)));
        }

        // 4. Backslash check (on raw bytes)
        if (indexOf(nameRaw, BACKSLASH) >= 0) {
            return buildFailure(nameRaw, nameUtf8, "AK-PATH-BACKSLASH",
                    Map.of("AK-PATH-BACKSLASH", Map.of("entryName", nameUtf8)));
        }

        // 5. Dot-segment check
        String dotSegmentReason = checkDotSegments(nameUtf8);
        if (dotSegmentReason != null) {
            return buildFailure(nameRaw, nameUtf8, dotSegmentReason,
                    Map.of(dotSegmentReason, Map.of("entryName", nameUtf8)));
        }

        // 6. NFC normalization check — stable UTF-8 round-trip
        String nfcName = Normalizer.normalize(nameUtf8, Normalizer.Form.NFC);
        byte[] nfcBytes = nfcName.getBytes(StandardCharsets.UTF_8);
        if (!java.util.Arrays.equals(nameRaw, nfcBytes)) {
            return buildFailure(nameRaw, nameUtf8, "AK-PATH-NFC-MISMATCH",
                    Map.of("AK-PATH-NFC-MISMATCH", Map.of(
                            "entryName", nameUtf8,
                            "decodedForm", nfcName)));
        }

        return new PathCheckResult(nameRaw, nameUtf8);
    }

    /**
     * Rejects dot-segment names per the ZIP spec security note.
     * Rejects: ".", "..", "./", "../", embedded "/./", "/../".
     * Accepts: trailing dots in filename segments ("a/b.").
     */
    private static String checkDotSegments(String path) {
        if (path.equals(".") || path.equals("..")) {
            return "AK-PATH-DOT-SEGMENT";
        }
        if (path.startsWith("./") || path.startsWith("../")) {
            return "AK-PATH-DOT-SEGMENT";
        }

        for (int i = 0; i < path.length() - 2; i++) {
            if (path.charAt(i) == '/') {
                int remaining = path.length() - i;
                if (path.charAt(i + 1) == '.') {
                    if (remaining == 2) {
                        return "AK-PATH-DOT-SEGMENT";
                    }
                    if (path.charAt(i + 2) == '/') {
                        return "AK-PATH-DOT-SEGMENT";
                    }
                    if (remaining >= 3 && path.charAt(i + 2) == '.') {
                        if (remaining == 3) {
                            return "AK-PATH-DOT-SEGMENT";
                        }
                        if (path.charAt(i + 3) == '/') {
                            return "AK-PATH-DOT-SEGMENT";
                        }
                    }
                }
            }
        }
        return null;
    }

    private static int indexOf(byte[] array, byte value) {
        for (int i = 0; i < array.length; i++) {
            if (array[i] == value) return i;
        }
        return -1;
    }

    /**
     * NUL failure: args contain only entryName (stable partial decode).
     * nulIndex is not included per frozen protocol.
     */
    private static PathCheckResult buildNulFailure(byte[] nameRaw) {
        int nulIdx = indexOf(nameRaw, NUL);
        String partial;
        if (nulIdx > 0) {
            try {
                partial = new String(nameRaw, 0, nulIdx, StandardCharsets.UTF_8);
            } catch (Exception e) {
                partial = "";
            }
        } else {
            partial = "";
        }
        return new PathCheckResult(
                nameRaw, partial,
                java.util.List.of("AK-PATH-NUL"),
                Map.of("AK-PATH-NUL", Map.of("entryName", partial)));
    }

    /**
     * Maps a strict UTF-8 decode failure to AK-PATH-NFC-MISMATCH.
     * Args contain entryName (stable partial decode via lenient decoder)
     * and decodedForm (same stable partial decode) — no exception text.
     */
    private static PathCheckResult buildDecodeFailure(byte[] nameRaw) {
        String decoded;
        try {
            CharsetDecoder lenient = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.IGNORE)
                    .onUnmappableCharacter(CodingErrorAction.IGNORE);
            decoded = lenient.decode(ByteBuffer.wrap(nameRaw)).toString();
        } catch (Exception e) {
            decoded = "";
        }
        return new PathCheckResult(
                nameRaw, decoded,
                java.util.List.of("AK-PATH-NFC-MISMATCH"),
                Map.of("AK-PATH-NFC-MISMATCH", Map.of(
                        "entryName", decoded,
                        "decodedForm", decoded)));
    }

    private static PathCheckResult buildFailure(byte[] nameRaw, String nameUtf8,
                                                String reason,
                                                Map<String, Map<String, Object>> args) {
        return new PathCheckResult(nameRaw, nameUtf8, java.util.List.of(reason), args);
    }
}

package com.leanowtech.bloge.gateway.testing.world;

import java.util.ArrayList;
import java.util.List;

/** Strict JSON Pointer codec used by the state asset and fragment boundary. */
final class StatePointer {
    private StatePointer() {
    }

    static String normalize(String pointer) {
        return encode(decode(pointer));
    }

    static List<String> decode(String pointer) {
        if (pointer == null || pointer.isBlank() || !pointer.startsWith("/")) {
            throw invalid();
        }
        String[] rawSegments = pointer.substring(1).split("/", -1);
        List<String> segments = new ArrayList<>(rawSegments.length);
        for (String raw : rawSegments) {
            if (raw.isEmpty()) {
                throw invalid();
            }
            segments.add(unescape(raw));
        }
        return List.copyOf(segments);
    }

    static String encode(List<String> segments) {
        if (segments == null || segments.isEmpty()
                || segments.stream().anyMatch(segment -> segment == null || segment.isEmpty())) {
            throw invalid();
        }
        StringBuilder pointer = new StringBuilder();
        for (String segment : segments) {
            pointer.append('/').append(escape(segment));
        }
        return pointer.toString();
    }

    static String append(String pointer, String segment) {
        List<String> segments = new ArrayList<>(decode(pointer));
        if (segment == null || segment.isEmpty()) {
            throw invalid();
        }
        segments.add(segment);
        return encode(segments);
    }

    static boolean isPrefix(String prefix, String candidate) {
        List<String> left = decode(prefix);
        List<String> right = decode(candidate);
        return left.size() < right.size() && right.subList(0, left.size()).equals(left);
    }

    private static String unescape(String segment) {
        StringBuilder decoded = new StringBuilder(segment.length());
        for (int index = 0; index < segment.length(); index++) {
            char current = segment.charAt(index);
            if (current != '~') {
                decoded.append(current);
                continue;
            }
            if (++index >= segment.length()) {
                throw invalid();
            }
            char escaped = segment.charAt(index);
            if (escaped == '0') {
                decoded.append('~');
            } else if (escaped == '1') {
                decoded.append('/');
            } else {
                throw invalid();
            }
        }
        return decoded.toString();
    }

    private static String escape(String segment) {
        return segment.replace("~", "~0").replace("/", "~1");
    }

    private static WorldModelException invalid() {
        return new WorldModelException(WorldModelException.Code.STATE_NOT_SUPPORTED);
    }
}

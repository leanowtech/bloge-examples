package com.leanowtech.bloge.gateway.visual.reference;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Pure domain service for bounded candidate discovery and exact resolution. */
public final class ReferenceCandidateService {
    private static final String CURSOR_VERSION = "v1";
    private static final Comparator<ReferenceCandidate> TIE_BREAKER = Comparator
            .comparing(ReferenceCandidate::kind)
            .thenComparing(ReferenceCandidate::id)
            .thenComparing(ReferenceCandidate::revision, Comparator.reverseOrder())
            .thenComparing(ReferenceCandidate::fingerprint);

    private final ReferenceCandidateProvider provider;

    public ReferenceCandidateService(ReferenceCandidateProvider provider) {
        this.provider = Objects.requireNonNull(provider, "provider");
    }

    public Page search(SearchRequest request) {
        Objects.requireNonNull(request, "request");
        String queryFingerprint = queryFingerprint(request);
        ReferenceCandidateProvider.ProviderSnapshot snapshot = provider.snapshot(request);
        Cursor cursor = decodeCursor(request.cursor());
        if (cursor != null) {
            if (!queryFingerprint.equals(cursor.queryFingerprint())) {
                throw new ReferenceSearchException(ReferenceSearchException.Code.QUERY_FINGERPRINT_MISMATCH,
                        "cursor belongs to a different search query");
            }
            if (snapshot.generation() != cursor.generation()) {
                throw new ReferenceSearchException(ReferenceSearchException.Code.CURSOR_STALE,
                        "catalog generation changed while paging");
            }
        }

        List<ReferenceCandidate> ranked = rank(request, snapshot.candidates());
        int start = startAfter(ranked, cursor);
        int end = Math.min(start + request.limit(), ranked.size());
        List<ReferenceCandidate> items = ranked.subList(start, end);
        String nextCursor = end < ranked.size()
                ? encodeCursor(new Cursor(snapshot.generation(), queryFingerprint, coordinate(items.getLast())))
                : "";
        return new Page(Page.SCHEMA_VERSION, items, nextCursor, queryFingerprint, snapshot.generation());
    }

    public ResolveResult resolve(ResolveRequest request) {
        Objects.requireNonNull(request, "request");
        ReferenceCandidateProvider.ProviderResolution resolution = provider.resolve(request);
        if (resolution.status() != ResolveResult.Status.RESOLVED) {
            ReferenceCandidate projected = resolution.candidate();
            if (projected != null && !sameSubject(projected, request)) {
                return new ResolveResult(ResolveResult.SCHEMA_VERSION,
                        ResolveResult.Status.NOT_FOUND, null,
                        errorCode(ResolveResult.Status.NOT_FOUND));
            }
            if (projected != null && !request.scope().matches(projected.scope())) {
                return new ResolveResult(ResolveResult.SCHEMA_VERSION,
                        ResolveResult.Status.FORBIDDEN, null,
                        errorCode(ResolveResult.Status.FORBIDDEN));
            }
            return new ResolveResult(ResolveResult.SCHEMA_VERSION, resolution.status(), resolution.candidate(),
                    errorCode(resolution.status()));
        }
        ReferenceCandidate candidate = resolution.candidate();
        if (!sameSubject(candidate, request)) {
            return new ResolveResult(ResolveResult.SCHEMA_VERSION, ResolveResult.Status.NOT_FOUND, null,
                    errorCode(ResolveResult.Status.NOT_FOUND));
        }
        if (!request.scope().matches(candidate.scope())) {
            return new ResolveResult(ResolveResult.SCHEMA_VERSION, ResolveResult.Status.FORBIDDEN, null,
                    errorCode(ResolveResult.Status.FORBIDDEN));
        }
        if (!candidate.exactCoordinateEquals(request.kind(), request.id(), request.revision(), request.fingerprint())) {
            return new ResolveResult(ResolveResult.SCHEMA_VERSION, ResolveResult.Status.DRIFTED, candidate,
                    errorCode(ResolveResult.Status.DRIFTED));
        }
        return new ResolveResult(ResolveResult.SCHEMA_VERSION, ResolveResult.Status.RESOLVED, candidate, "");
    }

    public static String queryFingerprint(SearchRequest request) {
        Objects.requireNonNull(request, "request");
        String canonical = String.join("\u0000",
                request.schemaVersion(), request.kind(), request.query(), Integer.toString(request.limit()),
                request.scope().tenantId(), request.scope().organizationId(), request.scope().projectId(),
                request.scope().environmentId(), request.scope().region(),
                request.lifecycle(), request.compatibleWith());
        return sha256(canonical);
    }

    private static List<ReferenceCandidate> rank(SearchRequest request, List<ReferenceCandidate> candidates) {
        String query = request.query().toLowerCase(Locale.ROOT);
        LinkedHashMap<String, ReferenceCandidate> unique = new LinkedHashMap<>();
        for (ReferenceCandidate candidate : candidates) {
            if (!request.kind().isEmpty() && !request.kind().equals(candidate.kind())) {
                continue;
            }
            if (!request.scope().matches(candidate.scope())) {
                continue;
            }
            if (!request.lifecycle().isEmpty()
                    && !request.lifecycle().equals(candidate.lifecycle().name())) {
                continue;
            }
            if (!request.compatibleWith().isEmpty()
                    && !request.compatibleWith().equals(candidate.compatibility().name())) {
                continue;
            }
            if (!matchesQuery(candidate, query)) {
                continue;
            }
            unique.putIfAbsent(coordinate(candidate), candidate);
        }
        List<ReferenceCandidate> ranked = new ArrayList<>(unique.values());
        ranked.sort(Comparator
                .comparingInt((ReferenceCandidate candidate) -> relevance(candidate, query)).reversed()
                .thenComparing(Comparator.comparingInt(ReferenceCandidateService::lifecycleRank).reversed())
                .thenComparing(Comparator.comparingInt(ReferenceCandidateService::compatibilityRank).reversed())
                .thenComparing(TIE_BREAKER));
        return List.copyOf(ranked);
    }

    private static boolean matchesQuery(ReferenceCandidate candidate, String query) {
        if (query.isEmpty()) {
            return true;
        }
        return candidate.id().toLowerCase(Locale.ROOT).contains(query)
                || candidate.displayName().toLowerCase(Locale.ROOT).contains(query)
                || candidate.description().toLowerCase(Locale.ROOT).contains(query)
                || candidate.labels().stream().map(label -> label.toLowerCase(Locale.ROOT)).anyMatch(label -> label.contains(query));
    }

    private static int relevance(ReferenceCandidate candidate, String query) {
        if (query.isEmpty()) {
            return 0;
        }
        String id = candidate.id().toLowerCase(Locale.ROOT);
        String name = candidate.displayName().toLowerCase(Locale.ROOT);
        if (candidate.id().equals(query)) {
            return 10_000;
        }
        if (id.equals(query)) {
            return 9_000;
        }
        if (name.equals(query)) {
            return 8_000;
        }
        if (id.startsWith(query)) {
            return 7_000;
        }
        if (name.startsWith(query)) {
            return 6_000;
        }
        if (candidate.labels().stream().map(label -> label.toLowerCase(Locale.ROOT)).anyMatch(label -> label.startsWith(query))) {
            return 5_000;
        }
        return 1_000;
    }

    private static int lifecycleRank(ReferenceCandidate candidate) {
        return switch (candidate.lifecycle()) {
            case ACTIVE -> 4;
            case DRAFT -> 3;
            case DEPRECATED -> 2;
            case SUPERSEDED -> 1;
        };
    }

    private static int compatibilityRank(ReferenceCandidate candidate) {
        return switch (candidate.compatibility()) {
            case COMPATIBLE -> 4;
            case REVIEW -> 3;
            case UNKNOWN -> 2;
            case INCOMPATIBLE -> 1;
        };
    }

    private static int startAfter(List<ReferenceCandidate> candidates, Cursor cursor) {
        if (cursor == null) {
            return 0;
        }
        for (int index = 0; index < candidates.size(); index++) {
            if (coordinate(candidates.get(index)).equals(cursor.lastCoordinate())) {
                return index + 1;
            }
        }
        throw new ReferenceSearchException(ReferenceSearchException.Code.CURSOR_STALE,
                "cursor position is no longer present in the catalog snapshot");
    }

    private static String encodeCursor(Cursor cursor) {
        String encodedCoordinate = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(cursor.lastCoordinate().getBytes(StandardCharsets.UTF_8));
        String encodedCursor = String.join("|", CURSOR_VERSION, Long.toString(cursor.generation()),
                cursor.queryFingerprint(), encodedCoordinate);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(encodedCursor.getBytes(StandardCharsets.UTF_8));
    }

    private static Cursor decodeCursor(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        try {
            String decodedCursor = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            String[] parts = decodedCursor.split("\\|", -1);
            if (parts.length != 4 || !CURSOR_VERSION.equals(parts[0]) || parts[2].isBlank() || parts[3].isBlank()) {
                throw new IllegalArgumentException("invalid cursor");
            }
            String coordinate = new String(Base64.getUrlDecoder().decode(parts[3]), StandardCharsets.UTF_8);
            return new Cursor(Long.parseLong(parts[1]), parts[2], coordinate);
        } catch (RuntimeException exception) {
            throw new ReferenceSearchException(ReferenceSearchException.Code.CURSOR_STALE,
                    "cursor is malformed or expired");
        }
    }

    private static String coordinate(ReferenceCandidate candidate) {
        return String.join("|", candidate.kind(), candidate.id(), Long.toString(candidate.revision()), candidate.fingerprint());
    }

    private static boolean sameSubject(ReferenceCandidate candidate, ResolveRequest request) {
        return candidate.kind().equals(request.kind()) && candidate.id().equals(request.id());
    }

    private static String errorCode(ResolveResult.Status status) {
        return switch (status) {
            case NOT_FOUND -> "RG.REFERENCE.NOT_FOUND";
            case DRIFTED -> "RG.REFERENCE.DRIFTED";
            case FORBIDDEN -> "RG.REFERENCE.FORBIDDEN";
            case RESOLVED -> "";
        };
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                result.append("%02x".formatted(item & 0xff));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }

    private record Cursor(long generation, String queryFingerprint, String lastCoordinate) {
    }
}

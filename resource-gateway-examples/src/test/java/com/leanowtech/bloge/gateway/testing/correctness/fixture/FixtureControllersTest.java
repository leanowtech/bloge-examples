package com.leanowtech.bloge.gateway.testing.correctness.fixture;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.leanowtech.bloge.gateway.integration.IntegrationAccessAuditRecord;
import com.leanowtech.bloge.gateway.integration.IntegrationAccessAuditRepository;
import com.leanowtech.bloge.gateway.integration.IntegrationIdentityResolver;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemHandler;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestAuthenticator;
import com.leanowtech.bloge.gateway.integration.IntegrationWorkloadIdentity;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.AuditMetadata;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactAssetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactSchemaRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactTargetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.PrincipalKind;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.PrincipalRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.TargetKind;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureAssetDescriptor;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureAssetDescriptor.FixtureLifecycle;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureAssetDescriptor.FixtureSource;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureAssetDescriptor.QualityProfile;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureAssetDescriptor.RedactionDescriptor;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureAssetDescriptor.RetentionDescriptor;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureAssetDescriptor.SourceKind;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureMaterialProtocolV2.FixtureSubject;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureMaterialProtocolV2.Material;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureMaterialProtocolV2.Receipt;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureMaterialProtocolV2.WriteRequest;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.StoredFixtureAsset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class FixtureControllersTest {

    private static final Instant NOW = Instant.parse("2026-08-15T12:00:00Z");

    private ObjectMapper mapper;
    private FixtureCatalogService catalog;
    private FixtureMaterialService materials;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper().findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        catalog = mock(FixtureCatalogService.class);
        materials = mock(FixtureMaterialService.class);
        IntegrationRequestAuthenticator authenticator = new IntegrationRequestAuthenticator(
                new FixtureIdentityResolver(), new RecordingAudit());
        mvc = MockMvcBuilders.standaloneSetup(
                        new FixtureAssetController(catalog, authenticator),
                        new FixtureMaterialController(materials, authenticator))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                .setControllerAdvice(new IntegrationProblemHandler())
                .build();
    }

    @Test
    void exposesCatalogLifecycleWithCasAndIdempotentOwnerApproval() throws Exception {
        FixtureAssetDescriptor draft = descriptor(0, FixtureLifecycle.DRAFT);
        StoredFixtureAsset storedDraft = stored(descriptor(1, FixtureLifecycle.DRAFT));
        StoredFixtureAsset proposed = stored(descriptor(2, FixtureLifecycle.PROPOSED));
        StoredFixtureAsset approved = stored(descriptor(3, FixtureLifecycle.APPROVED));
        when(catalog.saveDraft(anyLong(), any(), any())).thenReturn(storedDraft);
        when(catalog.submitForReview(any(), anyString(), anyLong(), any())).thenReturn(proposed);
        when(catalog.approveIdempotently(
                any(), anyString(), anyLong(), anyString(), any(), anyString()))
                .thenReturn(new FixtureCatalogService.ApprovalResult(approved, false));

        mvc.perform(put("/api/visual/fixture-assets/prime-applicant")
                        .header("Authorization", "Bearer author-token")
                        .header("X-Purpose", "CORRECTNESS_WRITE")
                        .header("If-Match", "0")
                        .contentType("application/json")
                        .content(mapper.writeValueAsBytes(draft)))
                .andExpect(status().isCreated())
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.data.descriptor.lifecycle").value("DRAFT"));

        mvc.perform(post("/api/visual/fixture-assets/prime-applicant:review-ready")
                        .header("Authorization", "Bearer author-token")
                        .header("X-Purpose", "CORRECTNESS_WRITE")
                        .header("If-Match", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.descriptor.lifecycle").value("PROPOSED"));

        mvc.perform(post("/api/visual/fixture-assets/prime-applicant:approve")
                        .header("Authorization", "Bearer reviewer-token")
                        .header("X-Purpose", "CORRECTNESS_REVIEW")
                        .header("If-Match", "2")
                        .header("Idempotency-Key", "fixture-approve-1")
                        .contentType("application/json")
                        .content("{\"comment\":\"Owner reviewed lineage\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotency-Replayed", "false"))
                .andExpect(jsonPath("$.data.stored.descriptor.lifecycle").value("APPROVED"));

        verify(catalog).approveIdempotently(
                scope(), "prime-applicant", 2, "Owner reviewed lineage",
                new PrincipalRef("reviewer-a", PrincipalKind.USER, ""), "fixture-approve-1");
    }

    @Test
    void materialSurfaceUsesDedicatedPurposesAndAlwaysReturnsNoStore() throws Exception {
        WriteRequest request = writeRequest();
        Receipt receipt = receipt();
        when(materials.write(any(), any())).thenReturn(receipt);
        when(materials.read(anyString(), anyLong(), anyString(), any()))
                .thenReturn(new Material("", receipt, Map.of("score", 760), true));

        mvc.perform(post("/api/visual/fixture-materials")
                        .header("Authorization", "Bearer material-token")
                        .header("X-Purpose", FixtureMaterialService.WRITE_PURPOSE)
                        .contentType("application/json")
                        .content(mapper.writeValueAsBytes(request)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", "no-store, private"))
                .andExpect(header().string("Pragma", "no-cache"))
                .andExpect(jsonPath("$.payloadReturned").value(false))
                .andExpect(jsonPath("$.payload").doesNotExist());

        mvc.perform(get("/api/visual/fixture-materials/prime-applicant")
                        .queryParam("revision", "1")
                        .queryParam("fingerprint", receipt.materialRef().fingerprint())
                        .header("Authorization", "Bearer material-token")
                        .header("X-Purpose", FixtureMaterialService.READ_PURPOSE))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store, private"))
                .andExpect(jsonPath("$.payload.score").value(760));

        mvc.perform(get("/api/visual/fixture-materials/prime-applicant")
                        .queryParam("revision", "1")
                        .queryParam("fingerprint", receipt.materialRef().fingerprint())
                        .header("Authorization", "Bearer author-token")
                        .header("X-Purpose", "CORRECTNESS_READ"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("RG.INTEGRATION.PURPOSE_FORBIDDEN"));
    }

    @Test
    void mapsCatalogAndMaterialFailuresWithoutPayloadDiagnostics() throws Exception {
        when(materials.read(anyString(), anyLong(), anyString(), any()))
                .thenThrow(new FixtureMaterialCommandException(
                        410, "RG.CORRECTNESS.FIXTURE_MATERIAL_EXPIRED", "Material expired"));
        mvc.perform(get("/api/visual/fixture-materials/prime-applicant")
                        .queryParam("revision", "1")
                        .queryParam("fingerprint", fingerprint('c'))
                        .header("Authorization", "Bearer material-token")
                        .header("X-Purpose", FixtureMaterialService.READ_PURPOSE))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code")
                        .value("RG.CORRECTNESS.FIXTURE_MATERIAL_EXPIRED"))
                .andExpect(jsonPath("$.details.payload").doesNotExist());

        mvc.perform(put("/api/visual/fixture-assets/prime-applicant")
                        .header("Authorization", "Bearer author-token")
                        .header("X-Purpose", "CORRECTNESS_WRITE")
                        .contentType("application/json")
                        .content(mapper.writeValueAsBytes(descriptor(0, FixtureLifecycle.DRAFT))))
                .andExpect(status().is(428))
                .andExpect(jsonPath("$.code")
                        .value("RG.CORRECTNESS.PRECONDITION_REQUIRED"));
    }

    private StoredFixtureAsset stored(FixtureAssetDescriptor descriptor) {
        return StoredFixtureAsset.verified(mapper, descriptor);
    }

    private static FixtureAssetDescriptor descriptor(long revision, FixtureLifecycle lifecycle) {
        return new FixtureAssetDescriptor(
                "", "prime-applicant", revision, scope(), "Prime applicant",
                receipt().source(), receipt().materialRef(), receipt().schemaRef(), "prime",
                lifecycle, "RESTRICTED", owner(), receipt().redaction(), receipt().retention(),
                new QualityProfile(true, true, 0, 0), List.of("loan"), metadata(author()));
    }

    private static WriteRequest writeRequest() {
        Receipt receipt = receipt();
        return new WriteRequest(
                "", "prime-applicant", 0, receipt.source(), FixtureSubject.GRAPH,
                target(), receipt.schemaRef(), "RESTRICTED", receipt.retention(),
                receipt.redaction(), Map.of("score", 760));
    }

    private static Receipt receipt() {
        return new Receipt(
                "", "prime-applicant", asset("FIXTURE_MATERIAL", "prime-applicant", 1, 'c'),
                fingerprint('c'), new FixtureSource(SourceKind.SAMPLE, null),
                FixtureSubject.GRAPH, target(), schema(), "RESTRICTED",
                new RetentionDescriptor("retention-v2", 30,
                        Instant.parse("2026-09-15T00:00:00Z")),
                new RedactionDescriptor("redaction-v2", List.of("/phone"), true),
                List.of(), true, false);
    }

    private static EnterpriseScope scope() {
        return new EnterpriseScope("tenant-a", "org-a", "credit", "test", "sg");
    }

    private static ExactTargetRef target() {
        return new ExactTargetRef(TargetKind.GRAPH, "loan-graph", 7, fingerprint('a'));
    }

    private static ExactSchemaRef schema() {
        return new ExactSchemaRef("loan-request", 3, fingerprint('b'));
    }

    private static PrincipalRef author() {
        return new PrincipalRef("author-a", PrincipalKind.USER, "Author A");
    }

    private static PrincipalRef owner() {
        return new PrincipalRef("owner-a", PrincipalKind.USER, "Owner A");
    }

    private static AuditMetadata metadata(PrincipalRef actor) {
        return new AuditMetadata(NOW, NOW, actor, actor);
    }

    private static ExactAssetRef asset(String kind, String id, long revision, char seed) {
        return new ExactAssetRef(kind, id, revision, fingerprint(seed));
    }

    private static String fingerprint(char seed) {
        return "sha256:" + String.valueOf(seed).repeat(64);
    }

    private static IntegrationWorkloadIdentity identity(
            String id, String actor, Set<String> purposes) {
        return new IntegrationWorkloadIdentity(
                id, "tenant-a", "org-a", "credit", "test", "sg", "USER", actor, "",
                purposes, Instant.MAX, true);
    }

    private static final class FixtureIdentityResolver implements IntegrationIdentityResolver {
        @Override
        public Optional<IntegrationWorkloadIdentity> resolve(String credential) {
            return switch (credential) {
                case "author-token" -> Optional.of(identity(
                        "author", "author-a", Set.of("CORRECTNESS_WRITE", "CORRECTNESS_READ")));
                case "reviewer-token" -> Optional.of(identity(
                        "reviewer", "reviewer-a", Set.of("CORRECTNESS_REVIEW")));
                case "material-token" -> Optional.of(identity(
                        "material", "material-a", Set.of(
                                FixtureMaterialService.WRITE_PURPOSE,
                                FixtureMaterialService.READ_PURPOSE)));
                default -> Optional.empty();
            };
        }

        @Override
        public Descriptor descriptor() {
            return new Descriptor("TEST", "TEST", true, true, false);
        }
    }

    private static final class RecordingAudit implements IntegrationAccessAuditRepository {
        private final List<IntegrationAccessAuditRecord> records = new ArrayList<>();

        @Override
        public IntegrationAccessAuditRecord append(IntegrationAccessAuditRecord record) {
            IntegrationAccessAuditRecord stored = record.withSequence(records.size() + 1L);
            records.add(stored);
            return stored;
        }

        @Override
        public List<IntegrationAccessAuditRecord> recent(int limit) {
            return records.stream().limit(limit).toList();
        }
    }
}

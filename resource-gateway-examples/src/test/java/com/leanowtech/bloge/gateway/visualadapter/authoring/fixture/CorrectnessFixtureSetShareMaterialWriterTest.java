package com.leanowtech.bloge.gateway.visualadapter.authoring.fixture;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactAssetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureAssetDescriptor;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureMaterialProtocolV2.Receipt;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureMaterialProtocolV2.WriteRequest;
import com.leanowtech.bloge.gateway.testing.correctness.fixture.FixtureCatalogService;
import com.leanowtech.bloge.gateway.testing.correctness.fixture.FixtureMaterialService;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.StoredFixtureAsset;
import com.leanowtech.bloge.gateway.visual.authoring.application.fixture.FixtureSetShareMaterialWriter;
import com.leanowtech.bloge.gateway.visual.authoring.application.fixture.FixtureShareIdentity;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSetCommand;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureShareCommand;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.GeneratedDefaultFixture;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.WholeFlowFixtureMaterializer;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowVersion;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringScope;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static com.leanowtech.bloge.gateway.visual.authoring.fixture.WholeFlowFixtureMaterializerTest.command;
import static com.leanowtech.bloge.gateway.visual.authoring.fixture.WholeFlowFixtureMaterializerTest.output;
import static com.leanowtech.bloge.gateway.visual.authoring.fixture.WholeFlowFixtureMaterializerTest.version;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CorrectnessFixtureSetShareMaterialWriterTest {

    @Test
    void writesProtectedMaterialAndSubmitsPayloadFreeDescriptorForReview() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        FixtureMaterialService materials = mock(FixtureMaterialService.class);
        FixtureCatalogService catalog = mock(FixtureCatalogService.class);
        FixtureShareIdentity identity = identity();
        IntegrationRequestContext context = context(identity);
        when(materials.write(any(), eq(context))).thenAnswer(invocation -> {
            WriteRequest request = invocation.getArgument(0);
            String payloadFingerprint = fingerprint('d');
            return new Receipt(Receipt.SCHEMA_VERSION, request.fixtureAssetId(),
                    new ExactAssetRef("FIXTURE_MATERIAL", request.fixtureAssetId(), 1,
                            payloadFingerprint), payloadFingerprint, request.source(), request.subject(),
                    request.target(), request.schemaRef(), request.classification(), request.retention(),
                    request.redaction(), List.of(request.source().sourceRef()), true, false);
        });
        AtomicReference<StoredFixtureAsset> draft = new AtomicReference<>();
        when(catalog.saveDraft(eq(0L), any(), any())).thenAnswer(invocation -> {
            FixtureAssetDescriptor candidate = invocation.getArgument(1);
            StoredFixtureAsset stored = StoredFixtureAsset.verified(
                    mapper, candidate.persistedAs(1, candidate.metadata()));
            draft.set(stored);
            return stored;
        });
        when(catalog.submitForReview(any(), any(), eq(1L), any())).thenAnswer(invocation ->
                StoredFixtureAsset.verified(mapper, draft.get().descriptor()
                        .withLifecycle(FixtureAssetDescriptor.FixtureLifecycle.PROPOSED)
                        .persistedAs(2, draft.get().descriptor().metadata())));
        CorrectnessFixtureSetShareMaterialWriter writer =
                new CorrectnessFixtureSetShareMaterialWriter(materials, catalog, mapper,
                        Clock.fixed(Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC));
        ReusableFlowVersion version = version();
        GeneratedDefaultFixture source = new WholeFlowFixtureMaterializer().generate("cases", version,
                command(version.subject(), FixtureSetCommand.Target.subject(),
                        FixtureSetCommand.Behavior.returned(
                                FixtureSetCommand.Material.inline(output())),
                        FixtureSetCommand.Fidelity.OUTPUT_LEVEL));
        FixtureShareCommand.Policy policy = new FixtureShareCommand.Policy("CONFIDENTIAL", 30,
                new FixtureShareCommand.Redaction("default-v1", List.of("/email")));

        FixtureSetCommand.Material.FixtureAsset asset = writer.write(
                new FixtureSetShareMaterialWriter.Request("share-asset", source.view(), 2,
                        "review-1", "approved", "Approved", version.contract().output(),
                        policy, output()), identity);

        assertThat(asset.fixtureAssetId()).isEqualTo("share-asset");
        assertThat(asset.revision()).isEqualTo(2);
        assertThat(asset.schemaFingerprint()).startsWith("sha256:");
        ArgumentCaptor<WriteRequest> material = ArgumentCaptor.forClass(WriteRequest.class);
        verify(materials).write(material.capture(), eq(context));
        assertThat(context.clearance()).isEqualTo("RESTRICTED");
        assertThat((com.fasterxml.jackson.databind.JsonNode)
                mapper.valueToTree(material.getValue().payload())).isEqualTo(output());
        assertThat(material.getValue().classification()).isEqualTo("CONFIDENTIAL");
        ArgumentCaptor<FixtureAssetDescriptor> descriptor =
                ArgumentCaptor.forClass(FixtureAssetDescriptor.class);
        verify(catalog).saveDraft(eq(0L), descriptor.capture(), any());
        assertThat(descriptor.getValue().lifecycle())
                .isEqualTo(FixtureAssetDescriptor.FixtureLifecycle.DRAFT);
        assertThat(descriptor.getValue().materialRef()).isEqualTo(
                new ExactAssetRef("FIXTURE_MATERIAL", "share-asset", 1, fingerprint('d')));
        verify(catalog).submitForReview(any(), eq("share-asset"), eq(1L), any());
    }

    private static FixtureShareIdentity identity() {
        return new FixtureShareIdentity(new AuthoringScope("tenant", "project", "dev"),
                "org", "sg", "HUMAN", "author", "RESTRICTED", "corr");
    }

    private static IntegrationRequestContext context(FixtureShareIdentity identity) {
        return new IntegrationRequestContext("tenant", "org", "project", "dev", "sg",
                "HUMAN", "author", "", FixtureMaterialService.WRITE_PURPOSE, "corr",
                Set.of(), "RESTRICTED", "");
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }
}

package com.leanowtech.bloge.gateway.authoring.scenario;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.visual.catalog.VisualOperatorCatalog;
import com.leanowtech.bloge.gateway.visual.contract.ContractDraftProjectionService;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraftRepository;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScenarioTableBoundedReadTest {

    @Test
    void authoringServiceDoesNotLoadTheCanonicalPayloadOnANormalPageRead() {
        ScenarioDraftSetRepository repository = mock(ScenarioDraftSetRepository.class);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        ScenarioDraftSet.EnterpriseScope scope = new ScenarioDraftSet.EnterpriseScope(
                "tenant-a", "org-a", "project-a", "test", "sg");
        String sourceFingerprint = ScenarioValidationServiceTest.fingerprint('a');
        ScenarioTableHead head = new ScenarioTableHead(
                "suite-a", 9, sourceFingerprint, "INTERNAL", 10_000);
        ScenarioTablePage expected = new ScenarioTablePage(
                "", "suite-a", 9, sourceFingerprint,
                ScenarioValidationServiceTest.fingerprint('b'), 10_000, List.of(), "next");
        ScenarioTablePageQuery query = new ScenarioTablePageQuery(
                ScenarioTablePageQuery.SCHEMA_VERSION, 9, sourceFingerprint, "",
                List.of(), ScenarioTablePageQuery.SortField.CANONICAL,
                ScenarioTablePageQuery.SortDirection.ASC, "", 100);
        when(repository.findTableHead(scope, "suite-a")).thenReturn(Optional.of(head));
        when(repository.queryPage(any(), anyString(), any(), anyString()))
                .thenReturn(Optional.of(expected));
        ScenarioDraftSetAuthoringService service = new ScenarioDraftSetAuthoringService(
                repository,
                mock(GraphDraftRepository.class),
                mock(VisualOperatorCatalog.class),
                mock(ContractDraftProjectionService.class),
                mock(ScenarioValidationService.class),
                mapper);

        assertThat(service.queryPage("suite-a", query, identity())).isSameAs(expected);
        verify(repository, never()).find(any(), anyString());
    }

    private static IntegrationRequestContext identity() {
        return new IntegrationRequestContext(
                "tenant-a", "org-a", "project-a", "test", "sg",
                "WORKLOAD", "author-a", "", "TEST_SUITE_READ", "correlation-a",
                java.util.Set.of(), "RESTRICTED", "");
    }
}

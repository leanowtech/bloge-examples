package com.leanowtech.bloge.gateway.integration;

import com.leanowtech.bloge.gateway.businessmirror.authoring.DomainCapabilityPackageController;
import com.leanowtech.bloge.gateway.businessmirror.compilation.BuiltInGraphAssetAuthority;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilitySnapshot;
import com.leanowtech.bloge.gateway.testing.correctness.workspace.CorrectnessWorkspaceController;
import com.leanowtech.bloge.gateway.visualadapter.reference.CorrectnessDefinitionCandidateController;
import com.leanowtech.bloge.gateway.visualadapter.reference.ReferenceCandidateController;
import com.leanowtech.bloge.gateway.visualadapter.authoring.link.AuthoringLinkResolverController;
import com.leanowtech.bloge.gateway.visualadapter.authoring.link.AuthoringLinkResolverService;
import com.leanowtech.bloge.gateway.visualadapter.authoring.link.AuthoringLinkSourceAuthority;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stage 4 anti-entropy checks for the guided authoring integration contract.
 *
 * <p>The controller annotations are the transport authority. The capability probe is a
 * projection of that authority, so a route rename must fail this test before it reaches an
 * integrating Tool Studio client.</p>
 */
class GuidedProtocolAntiEntropyTest {

    @Test
    void advertisedGuidedFeaturesKeepTheirControllerRoutes() {
        ToolStudioIntegrationService service = new ToolStudioIntegrationService(null, null, null, null);
        service.configureCorrectnessAuthoringRuntime(
                new CorrectnessAuthoringRuntimeAvailability(
                        true, true, false, true, true, false, true, true, true,
                        true, true, true, true));
        service.configureAuthoringLinkResolver(new AuthoringLinkResolverService(
                new AuthoringLinkSourceAuthority() {
                    @Override
                    public List<String> graphNames() {
                        return List.of();
                    }

                    @Override
                    public BuiltInGraphAssetAuthority.Snapshot resolve(
                            CapabilitySnapshot.Scope scope,
                            String graphName) {
                        throw new IllegalStateException("not used by route anti-entropy");
                    }
                }));

        IntegrationCapabilities capabilities = service.capabilities().payload();

        assertFeatureRoutes(capabilities, "referenceCandidateApi", Set.of(
                route("GET", "/api/visual/reference-candidates"),
                route("POST", "/api/visual/reference-candidates:resolve")),
                ReferenceCandidateController.class);
        assertFeatureRoutes(capabilities, "correctnessTargetCatalogApi", Set.of(
                route("GET", "/api/visual/correctness-targets"),
                route("GET", "/api/visual/correctness-targets/{kind}/{id}/definitions")),
                CorrectnessDefinitionCandidateController.class);
        assertFeatureRoutes(capabilities, "guidedWorkspaceLauncher", Set.of(
                route("GET", "/api/visual/correctness-workspaces/{targetKind}/{targetId}")),
                CorrectnessWorkspaceController.class);
        assertFeatureRoutes(capabilities, "businessMirrorGuidedRemediation", Set.of(
                route("POST", "/api/business-mirror/packages"),
                route("PUT", "/api/business-mirror/packages/{packageId}"),
                route("GET", "/api/business-mirror/packages"),
                route("GET", "/api/business-mirror/packages/{packageId}"),
                route("GET", "/api/business-mirror/packages/{packageId}/revisions"),
                route("GET", "/api/business-mirror/packages/{packageId}/revisions/{revision}")),
                DomainCapabilityPackageController.class);
        assertFeatureRoutes(capabilities, "authoringLinkResolverApi", Set.of(
                route("POST", "/api/visual/authoring-links:resolve")),
                AuthoringLinkResolverController.class);
    }

    @Test
    void capabilityProbeDoesNotAdvertiseGuidedCatalogRoutesWhenRuntimeIsAbsent() {
        IntegrationCapabilities capabilities = new ToolStudioIntegrationService(
                null, null, null, null).capabilities().payload();

        assertThat(capabilities.features())
                .containsEntry("referenceCandidateApi", true)
                .containsEntry("correctnessTargetCatalogApi", false)
                .containsEntry("guidedWorkspaceLauncher", false)
                .containsEntry("authoringLinkResolverApi", false)
                .containsEntry("businessMirrorGuidedRemediation", true);
        assertThat(capabilities.endpoints())
                .extracting(IntegrationCapabilities.Endpoint::path)
                .doesNotContain(
                        "/api/visual/correctness-targets",
                        "/api/visual/correctness-targets/{kind}/{id}/definitions",
                        "/api/visual/correctness-workspaces/{targetKind}/{targetId}",
                        "/api/visual/authoring-links:resolve");
    }

    private static void assertFeatureRoutes(
            IntegrationCapabilities capabilities,
            String feature,
            Set<Route> expected,
            Class<?> controller) {
        assertThat(capabilities.features()).as(feature).containsEntry(feature, true);
        Set<Route> advertised = capabilities.endpoints().stream()
                .map(endpoint -> route(endpoint.method(), endpoint.path()))
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        assertThat(advertised).as(feature + " capability routes").containsAll(expected);
        assertThat(controllerRoutes(controller)).as(feature + " controller routes")
                .containsAll(expected);
    }

    private static Set<Route> controllerRoutes(Class<?> controller) {
        String prefix = pathOf(controller.getAnnotation(RequestMapping.class));
        Set<Route> routes = new LinkedHashSet<>();
        for (Method method : controller.getDeclaredMethods()) {
            addRoutes(routes, prefix, method.getAnnotation(GetMapping.class), "GET");
            addRoutes(routes, prefix, method.getAnnotation(PostMapping.class), "POST");
            addRoutes(routes, prefix, method.getAnnotation(PutMapping.class), "PUT");
            addRoutes(routes, prefix, method.getAnnotation(DeleteMapping.class), "DELETE");
        }
        return routes;
    }

    private static void addRoutes(Set<Route> routes, String prefix, Object mapping, String method) {
        if (mapping == null) return;
        String[] paths;
        if (mapping instanceof GetMapping annotation) paths = first(annotation.path(), annotation.value());
        else if (mapping instanceof PostMapping annotation) paths = first(annotation.path(), annotation.value());
        else if (mapping instanceof PutMapping annotation) paths = first(annotation.path(), annotation.value());
        else if (mapping instanceof DeleteMapping annotation) paths = first(annotation.path(), annotation.value());
        else return;
        for (String path : paths) routes.add(route(method, join(prefix, path)));
    }

    private static String pathOf(RequestMapping mapping) {
        if (mapping == null) return "";
        String[] paths = first(mapping.path(), mapping.value());
        return paths.length == 0 ? "" : paths[0];
    }

    private static String[] first(String[] primary, String[] fallback) {
        if (primary.length > 0) return primary;
        if (fallback.length > 0) return fallback;
        return new String[]{""};
    }

    private static String join(String prefix, String path) {
        if (prefix.isEmpty()) return path;
        if (path.isEmpty()) return prefix;
        return prefix.endsWith("/") && path.startsWith("/")
                ? prefix + path.substring(1) : prefix + path;
    }

    private static Route route(String method, String path) {
        return new Route(method, path);
    }

    private record Route(String method, String path) { }
}

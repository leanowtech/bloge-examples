package com.leanowtech.bloge.gateway.visual.runtime;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VisualRunControlControllerTest {

    private final VisualGraphRunService runner = mock(VisualGraphRunService.class);
    private final VisualRunControlController controller = new VisualRunControlController(runner);

    @Test
    void requiresFenceForLifecycleLookupAndMapsFenceMismatchToForbidden() {
        VisualRunControlResult denied = new VisualRunControlResult(false, "RG.RUN_CONTROL.FENCE_MISMATCH",
                "wrong fence", VisualRunControlView.unmanaged());
        when(runner.runControl("run-1", "wrong")).thenReturn(denied);

        assertThat(controller.get("run-1", "wrong")).satisfies(response -> {
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(response.getBody()).isEqualTo(denied);
        });
    }

    @Test
    void forwardsFencedCancellationAndReturnsAcceptedLifecycle() {
        VisualRunControlView cancelling = new VisualRunControlView("", "run-1", "execution-1",
                "CANCEL_REQUESTED", "USER_CANCEL_REQUESTED", 4, null, null, null, null, false, true);
        VisualRunControlResult accepted = new VisualRunControlResult(true, "RG.RUN_CONTROL.CANCEL_ACCEPTED", "",
                cancelling);
        VisualRunControlCommand command = new VisualRunControlCommand("run-1", "fence-1", 3, "author cancelled");
        when(runner.cancel(command)).thenReturn(accepted);

        assertThat(controller.cancel("run-1", new VisualRunCancelRequest("fence-1", 3, "author cancelled")))
                .satisfies(response -> {
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
                    assertThat(response.getBody()).isEqualTo(accepted);
                });
        verify(runner).cancel(command);
    }

    @Test
    void mapsMissingRunAndRevisionConflictWithoutMutatingLifecycle() {
        VisualRunControlResult missing = new VisualRunControlResult(false, "RG.RUN_CONTROL.NOT_FOUND", "missing",
                VisualRunControlView.unmanaged());
        when(runner.runControl("missing", "fence")).thenReturn(missing);
        assertThat(controller.get("missing", "fence").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        VisualRunControlResult conflict = new VisualRunControlResult(false, "RG.RUN_CONTROL.REVISION_CONFLICT",
                "stale", new VisualRunControlView("", "run-1", "", "RUNNING", "EXECUTION_STARTED", 7,
                        null, null, null, null, false, false));
        when(runner.cancel(new VisualRunControlCommand("run-1", "fence", 6, "stale"))).thenReturn(conflict);
        assertThat(controller.cancel("run-1", new VisualRunCancelRequest("fence", 6, "stale")).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }
}

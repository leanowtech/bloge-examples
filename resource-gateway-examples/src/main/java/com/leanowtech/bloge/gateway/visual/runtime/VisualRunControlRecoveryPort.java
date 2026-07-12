package com.leanowtech.bloge.gateway.visual.runtime;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Read-only control-plane facts required to recover missing visual run evidence. */
public interface VisualRunControlRecoveryPort {

    List<String> recoveryCandidates(Instant missingControlCutoff,
                                    Instant terminalControlCutoff,
                                    Instant leaseExpiryCutoff,
                                    int limit);

    Optional<State> find(String requestId, Instant now);

    record State(VisualRunControlView control, String recoveryDisposition) {
        public State {
            control = control == null ? VisualRunControlView.unmanaged() : control;
            recoveryDisposition = recoveryDisposition == null ? "" : recoveryDisposition;
        }
    }
}

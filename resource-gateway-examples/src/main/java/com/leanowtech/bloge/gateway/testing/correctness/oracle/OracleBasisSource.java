package com.leanowtech.bloge.gateway.testing.correctness.oracle;

import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactBasisRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactTargetRef;

import java.util.List;

/** Resolves whether every policy/SOP/contract basis coordinate still identifies exact content. */
@FunctionalInterface
public interface OracleBasisSource {

    boolean referencesAreCurrent(
            EnterpriseScope scope,
            ExactTargetRef target,
            List<ExactBasisRef> basisRefs);

    static OracleBasisSource denyAll() {
        return (scope, target, basisRefs) -> false;
    }
}

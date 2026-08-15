package com.leanowtech.bloge.gateway.testing.correctness.compilation;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.leanowtech.bloge.gateway.testing.correctness.domain.AssertionSet;
import com.leanowtech.bloge.gateway.testing.correctness.domain.BusinessOracle;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessDefinition;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactAssetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessPublication.CompilationCoordinate;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CoverageInventory;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureAssetDescriptor;
import com.leanowtech.bloge.gateway.testing.correctness.domain.ScenarioDraftSetV2;
import com.leanowtech.bloge.gateway.testing.domain.ProtocolJsonValue;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Exact, detached input passed to the pure correctness compiler.
 *
 * <p>This type may contain protected Fixture material. It must remain inside the authorized
 * compilation boundary and therefore deliberately exposes no record-generated payload-bearing
 * {@code toString()}.</p>
 */
public final class FrozenCompilationInput {

    private final EnterpriseScope scope;
    private final CompilationCoordinate coordinate;
    private final CorrectnessDefinition definition;
    private final CoverageInventory inventory;
    private final ScenarioDraftSetV2 scenarioDraftSet;
    private final List<BusinessOracle> oracles;
    private final List<AssertionSet> assertionSets;
    private final List<MaterializedFixture> fixtures;

    public FrozenCompilationInput(
            EnterpriseScope scope,
            CompilationCoordinate coordinate,
            CorrectnessDefinition definition,
            CoverageInventory inventory,
            ScenarioDraftSetV2 scenarioDraftSet,
            List<BusinessOracle> oracles,
            List<AssertionSet> assertionSets,
            List<MaterializedFixture> fixtures
    ) {
        this.scope = Objects.requireNonNull(scope, "scope");
        this.coordinate = Objects.requireNonNull(coordinate, "coordinate");
        this.definition = Objects.requireNonNull(definition, "definition");
        this.inventory = Objects.requireNonNull(inventory, "inventory");
        this.scenarioDraftSet = Objects.requireNonNull(scenarioDraftSet, "scenarioDraftSet");
        this.oracles = sorted(oracles, BusinessOracle::oracleId, BusinessOracle::revision);
        this.assertionSets = sorted(
                assertionSets, AssertionSet::assertionSetId, AssertionSet::revision);
        this.fixtures = fixtures == null ? List.of() : fixtures.stream()
                .sorted(Comparator.comparing((MaterializedFixture value) ->
                                value.descriptorRef().id())
                        .thenComparingLong(value -> value.descriptorRef().revision()))
                .toList();
        requireUnique(this.oracles.stream().map(BusinessOracle::oracleId).toList(), "Oracle");
        requireUnique(this.assertionSets.stream().map(AssertionSet::assertionSetId).toList(),
                "Assertion Set");
        requireUnique(this.fixtures.stream().map(value -> value.descriptorRef().id()).toList(),
                "Fixture Asset");
    }

    public EnterpriseScope scope() {
        return scope;
    }

    public CompilationCoordinate coordinate() {
        return coordinate;
    }

    public CorrectnessDefinition definition() {
        return definition;
    }

    public CoverageInventory inventory() {
        return inventory;
    }

    public ScenarioDraftSetV2 scenarioDraftSet() {
        return scenarioDraftSet;
    }

    public List<BusinessOracle> oracles() {
        return oracles;
    }

    public List<AssertionSet> assertionSets() {
        return assertionSets;
    }

    public List<MaterializedFixture> fixtures() {
        return fixtures;
    }

    @Override
    public String toString() {
        return "FrozenCompilationInput[coordinate=" + coordinate
                + ", oracleCount=" + oracles.size()
                + ", assertionSetCount=" + assertionSets.size()
                + ", fixtureCount=" + fixtures.size() + ']';
    }

    /** One verified descriptor/material pair; payload is never included in diagnostics or logs. */
    public static final class MaterializedFixture {
        private final ExactAssetRef descriptorRef;
        private final FixtureAssetDescriptor descriptor;
        private final ExactAssetRef materialRef;
        private final Object payload;

        public MaterializedFixture(
                ExactAssetRef descriptorRef,
                FixtureAssetDescriptor descriptor,
                ExactAssetRef materialRef,
                Object payload
        ) {
            this.descriptorRef = Objects.requireNonNull(descriptorRef, "descriptorRef");
            this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
            this.materialRef = Objects.requireNonNull(materialRef, "materialRef");
            this.payload = ProtocolJsonValue.freeze(
                    Objects.requireNonNull(payload, "payload"));
            if (!"FIXTURE_ASSET".equals(descriptorRef.kind())
                    || !"FIXTURE_MATERIAL".equals(materialRef.kind())
                    || !descriptor.fixtureAssetId().equals(descriptorRef.id())
                    || descriptor.revision() != descriptorRef.revision()
                    || !descriptor.materialRef().equals(materialRef)) {
                throw new IllegalArgumentException(
                        "Materialized Fixture must bind exact descriptor and material refs");
            }
        }

        public ExactAssetRef descriptorRef() {
            return descriptorRef;
        }

        public FixtureAssetDescriptor descriptor() {
            return descriptor;
        }

        public ExactAssetRef materialRef() {
            return materialRef;
        }

        @JsonIgnore
        public Object payload() {
            return payload;
        }

        @Override
        public String toString() {
            return "MaterializedFixture[descriptorRef=" + descriptorRef
                    + ", materialRef=" + materialRef + ", payload=PROTECTED]";
        }
    }

    private static <T> List<T> sorted(
            List<T> values,
            java.util.function.Function<T, String> id,
            java.util.function.ToLongFunction<T> revision
    ) {
        return values == null ? List.of() : values.stream()
                .sorted(Comparator.comparing(id).thenComparingLong(revision))
                .toList();
    }

    private static void requireUnique(List<String> ids, String kind) {
        if (ids.stream().distinct().count() != ids.size()) {
            throw new IllegalArgumentException(kind + " ids must be unique");
        }
    }
}

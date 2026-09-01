package com.leanowtech.bloge.gateway.visual.authoring.fixture;

/** Exact governed content and payload-free receipt produced under a locked private source. */
public record FixtureShareMaterialization(GeneratedDefaultFixture generated,
                                          FixtureShareReceipt receipt) {
    public FixtureShareMaterialization {
        if (generated == null || receipt == null
                || !generated.view().fixtureSetId().equals(receipt.fixtureSetId())
                || generated.view().revision() != receipt.revision()
                || !generated.view().fingerprint().equals(receipt.fingerprint())
                || generated.view().status() != receipt.status()
                || generated.view().statusRevision() != receipt.statusRevision()
                || containsInlineMaterial(generated)) {
            throw new IllegalArgumentException("Fixture share materialization is incomplete");
        }
    }

    private static boolean containsInlineMaterial(GeneratedDefaultFixture generated) {
        return generated.view().cases().stream().flatMap(value -> value.controls().stream())
                .map(FixtureSetCommand.Control::behavior)
                .filter(FixtureSetCommand.Behavior.Return.class::isInstance)
                .map(FixtureSetCommand.Behavior.Return.class::cast)
                .anyMatch(value -> value.material() instanceof FixtureSetCommand.Material.Inline);
    }
}

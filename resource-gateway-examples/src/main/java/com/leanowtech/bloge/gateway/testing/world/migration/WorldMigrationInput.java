package com.leanowtech.bloge.gateway.testing.world.migration;

import com.leanowtech.bloge.gateway.testing.api.StoredFixtureBundle;
import com.leanowtech.bloge.gateway.testing.api.StoredTestSuite;
import com.leanowtech.bloge.gateway.testing.planning.InvocationInventory;
import com.leanowtech.bloge.gateway.testing.world.WorldScenarioCompilation;

/** Exact, server-owned input set for one migration attempt. */
public record WorldMigrationInput(
        String tenantId,
        StoredFixtureBundle fixtureBundle,
        StoredTestSuite testSuite,
        WorldScenarioCompilation compilation,
        InvocationInventory inventory) {
    public WorldMigrationInput {
        tenantId = MigrationSupport.text(tenantId);
        if (fixtureBundle == null || testSuite == null || compilation == null || inventory == null) {
            throw MigrationSupport.fail(WorldMigrationException.Code.INVALID_INPUT);
        }
    }
}

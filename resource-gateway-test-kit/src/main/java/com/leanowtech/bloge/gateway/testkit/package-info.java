/**
 * Standalone client-side test kit for the Resource Gateway testing control plane.
 *
 * <p>The package depends on wire contracts rather than Resource Gateway implementation classes so
 * application test suites and the server can evolve and build independently. It also contains
 * typed asynchronous suite-stability job control, offline evidence, and exact-inventory rollout
 * verifiers whose trust anchors are supplied by the caller rather than inferred from the Resource
 * Gateway response.</p>
 */
package com.leanowtech.bloge.gateway.testkit;

#!/usr/bin/env bash

set -euo pipefail

# Customer-retention demo for the Resource Gateway authoring HTTP surface.
# The script creates isolated identifiers by default and performs no real egress:
# every simulation uses an exact saved Fixture Case and DENY/DENY egress policy.

BASE_URL="${RG_BASE_URL:-http://localhost:8081}"
TOKEN="${RG_TOKEN:-bloge-aneke-demo-token}"
PURPOSE="${RG_PURPOSE:-API_RESOURCE_AUTHORING}"
DEMO_ID="${RG_DEMO_ID:-retention-$(date +%Y%m%d%H%M%S)}"

for dependency in curl jq; do
    if ! command -v "${dependency}" >/dev/null 2>&1; then
        echo "Missing required command: ${dependency}" >&2
        exit 1
    fi
done

if [[ ! "${DEMO_ID}" =~ ^[A-Za-z0-9][A-Za-z0-9._:-]{0,47}$ ]]; then
    echo "RG_DEMO_ID must be a 1-48 character Resource Gateway identifier." >&2
    exit 1
fi

WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/rg-fixture-demo.XXXXXX")"
if [[ "${RG_KEEP_DEMO_FILES:-false}" == "true" ]]; then
    echo "Request and response files: ${WORK_DIR}"
else
    trap 'rm -rf "${WORK_DIR}"' EXIT
fi

PROFILE_RESOURCE="${DEMO_ID}.customer-profile"
ACCOUNT_RESOURCE="${DEMO_ID}.account-summary"
OFFER_RESOURCE="${DEMO_ID}.retention-offer"
FLOW_ID="${DEMO_ID}.retention-tool"
FLOW_FIXTURES="${DEMO_ID}.tool-fixtures"

auth_header() {
    printf 'Authorization: Bearer %s' "${TOKEN}"
}

put_create() {
    local path="$1" key="$2" body="$3" response="$4" headers="$5"
    local content_type="${6:-application/json}"
    if ! curl --fail-with-body --silent --show-error \
        --request PUT "${BASE_URL}${path}" \
        --header "$(auth_header)" \
        --header "X-Purpose: ${PURPOSE}" \
        --header "Content-Type: ${content_type}" \
        --header 'If-None-Match: *' \
        --header "Idempotency-Key: ${key}" \
        --data-binary "@${body}" \
        --dump-header "${headers}" \
        --output "${response}"; then
        echo "PUT ${path} failed:" >&2
        jq . "${response}" >&2 2>/dev/null || sed -n '1,120p' "${response}" >&2
        return 1
    fi
}

post_json() {
    local path="$1" key="$2" body="$3" response="$4" headers="$5"
    if ! curl --fail-with-body --silent --show-error \
        --request POST "${BASE_URL}${path}" \
        --header "$(auth_header)" \
        --header "X-Purpose: ${PURPOSE}" \
        --header 'Content-Type: application/json' \
        --header "Idempotency-Key: ${key}" \
        --data-binary "@${body}" \
        --dump-header "${headers}" \
        --output "${response}"; then
        echo "POST ${path} failed:" >&2
        jq . "${response}" >&2 2>/dev/null || sed -n '1,120p' "${response}" >&2
        return 1
    fi
}

show() {
    local title="$1" file="$2"
    printf '\n=== %s ===\n' "${title}"
    jq . "${file}"
}

require_jq() {
    local expression="$1" file="$2" message="$3"
    if ! jq --exit-status "${expression}" "${file}" >/dev/null; then
        echo "Assertion failed: ${message}" >&2
        jq . "${file}" >&2
        exit 1
    fi
}

echo "Demo id: ${DEMO_ID}"
echo "Resource Gateway: ${BASE_URL}"

# 1. Fail before mutation if the two authoring capabilities are not enabled.
curl --fail-with-body --silent --show-error \
    "${BASE_URL}/api/authoring/availability" > "${WORK_DIR}/availability.json"
show "Authoring availability" "${WORK_DIR}/availability.json"
require_jq '.apiResource == true and .reusableFlow == true' \
    "${WORK_DIR}/availability.json" \
    'Run scripts/start-caller-directed-fixture-demo.sh before this demo.'

# 2. Define three exact API Resources. Connections are credential-free and are
#    never contacted by this demo because every execution is Fixture-controlled.
jq -n '{
  schemaVersion: "bloge.apiResourceSaveCommand.v1",
  connection: {mode: "CREATE", command: {
    schemaVersion: "bloge.apiConnectionCommand.v1",
    displayName: "Customer CRM API", baseUrl: "https://crm.example.test",
    auth: {kind: "NONE"}, defaults: {timeoutMs: 3000, headers: {Accept: "application/json"}}
  }},
  resource: {
    displayName: "Get customer retention profile",
    description: "Returns the segment and churn risk used by the retention tool.",
    operation: {method: "GET", path: "/customers/{customerId}/retention-profile", bindings: [
      {from: "$.customerId", to: {location: "PATH", name: "customerId"}}
    ]},
    contract: {
      input: {format: "json-schema", version: "2020-12", schema: {
        type: "object", properties: {customerId: {type: "string"}},
        required: ["customerId"], additionalProperties: false
      }},
      output: {format: "json-schema", version: "2020-12", schema: {
        type: "object", properties: {
          customerId: {type: "string"}, segment: {type: "string"}, churnRisk: {type: "string"}
        }, required: ["customerId", "segment", "churnRisk"], additionalProperties: false
      }}
    },
    response: {success: {kind: "HTTP_STATUS", codes: [200]}},
    effect: {kind: "READ_ONLY"}, examples: [{
      name: "vip-risk", input: {customerId: "C-1001"},
      output: {customerId: "C-1001", segment: "VIP", churnRisk: "HIGH"}
    }]
  },
  defaultFixture: {kind: "FROM_EXAMPLES", displayName: "VIP churn-risk customer",
                   exampleNames: ["vip-risk"]}
}' > "${WORK_DIR}/profile-resource-command.json"

jq -n '{
  schemaVersion: "bloge.apiResourceSaveCommand.v1",
  connection: {mode: "CREATE", command: {
    schemaVersion: "bloge.apiConnectionCommand.v1",
    displayName: "Billing Summary API", baseUrl: "https://billing.example.test",
    auth: {kind: "NONE"}, defaults: {timeoutMs: 3000, headers: {Accept: "application/json"}}
  }},
  resource: {
    displayName: "Get customer account summary",
    description: "Returns recent monthly spend used to size a retention offer.",
    operation: {method: "GET", path: "/accounts/{customerId}/summary", bindings: [
      {from: "$.customerId", to: {location: "PATH", name: "customerId"}}
    ]},
    contract: {
      input: {format: "json-schema", version: "2020-12", schema: {
        type: "object", properties: {customerId: {type: "string"}},
        required: ["customerId"], additionalProperties: false
      }},
      output: {format: "json-schema", version: "2020-12", schema: {
        type: "object", properties: {
          customerId: {type: "string"}, monthlySpend: {type: "number"}, currency: {type: "string"}
        }, required: ["customerId", "monthlySpend", "currency"], additionalProperties: false
      }}
    },
    response: {success: {kind: "HTTP_STATUS", codes: [200]}},
    effect: {kind: "READ_ONLY"}, examples: [{
      name: "high-value", input: {customerId: "C-1001"},
      output: {customerId: "C-1001", monthlySpend: 1200, currency: "SGD"}
    }]
  },
  defaultFixture: {kind: "FROM_EXAMPLES", displayName: "High-value account",
                   exampleNames: ["high-value"]}
}' > "${WORK_DIR}/account-resource-command.json"

jq -n '{
  schemaVersion: "bloge.apiResourceSaveCommand.v1",
  connection: {mode: "CREATE", command: {
    schemaVersion: "bloge.apiConnectionCommand.v1",
    displayName: "Retention Offer API", baseUrl: "https://offers.example.test",
    auth: {kind: "NONE"}, defaults: {timeoutMs: 3000, headers: {Accept: "application/json"}}
  }},
  resource: {
    displayName: "Recommend customer retention offer",
    description: "Selects an offer from customer segment and monthly spend.",
    operation: {method: "GET", path: "/retention/offers/recommend", bindings: [
      {from: "$.customerId", to: {location: "QUERY", name: "customerId"}},
      {from: "$.segment", to: {location: "QUERY", name: "segment"}},
      {from: "$.monthlySpend", to: {location: "QUERY", name: "monthlySpend"}}
    ]},
    contract: {
      input: {format: "json-schema", version: "2020-12", schema: {
        type: "object", properties: {
          customerId: {type: "string"}, segment: {type: "string"}, monthlySpend: {type: "number"}
        }, required: ["customerId", "segment", "monthlySpend"], additionalProperties: false
      }},
      output: {format: "json-schema", version: "2020-12", schema: {
        type: "object", properties: {
          customerId: {type: "string"}, offerCode: {type: "string"},
          discountPercent: {type: "number"}, message: {type: "string"}
        }, required: ["customerId", "offerCode", "discountPercent", "message"],
        additionalProperties: false
      }}
    },
    response: {success: {kind: "HTTP_STATUS", codes: [200]}},
    effect: {kind: "READ_ONLY"}, examples: [{
      name: "save20", input: {customerId: "C-1001", segment: "VIP", monthlySpend: 1200},
      output: {customerId: "C-1001", offerCode: "SAVE20", discountPercent: 20,
               message: "20% off the next renewal"}
    }]
  },
  defaultFixture: {kind: "FROM_EXAMPLES", displayName: "VIP retention offer",
                   exampleNames: ["save20"]}
}' > "${WORK_DIR}/offer-resource-command.json"

put_create "/api/authoring/resources/${PROFILE_RESOURCE}" \
    "${DEMO_ID}:resource:profile" "${WORK_DIR}/profile-resource-command.json" \
    "${WORK_DIR}/profile-resource.json" "${WORK_DIR}/profile-resource.headers"
put_create "/api/authoring/resources/${ACCOUNT_RESOURCE}" \
    "${DEMO_ID}:resource:account" "${WORK_DIR}/account-resource-command.json" \
    "${WORK_DIR}/account-resource.json" "${WORK_DIR}/account-resource.headers"
put_create "/api/authoring/resources/${OFFER_RESOURCE}" \
    "${DEMO_ID}:resource:offer" "${WORK_DIR}/offer-resource-command.json" \
    "${WORK_DIR}/offer-resource.json" "${WORK_DIR}/offer-resource.headers"

show "Profile API Resource receipt" "${WORK_DIR}/profile-resource.json"
show "Account API Resource receipt" "${WORK_DIR}/account-resource.json"
show "Offer API Resource receipt" "${WORK_DIR}/offer-resource.json"

PROFILE_REVISION="$(jq -r '.resource.revision' "${WORK_DIR}/profile-resource.json")"
PROFILE_FINGERPRINT="$(jq -r '.resource.fingerprint' "${WORK_DIR}/profile-resource.json")"
ACCOUNT_REVISION="$(jq -r '.resource.revision' "${WORK_DIR}/account-resource.json")"
ACCOUNT_FINGERPRINT="$(jq -r '.resource.fingerprint' "${WORK_DIR}/account-resource.json")"
OFFER_REVISION="$(jq -r '.resource.revision' "${WORK_DIR}/offer-resource.json")"
OFFER_FINGERPRINT="$(jq -r '.resource.fingerprint' "${WORK_DIR}/offer-resource.json")"

# 3. Resource examples and FROM_EXAMPLES were committed atomically as private
#    Fixture Sets. Read their server-generated exact coordinates from receipts.
PROFILE_FIXTURES="$(jq -r '.defaultFixture.fixtureSetId' "${WORK_DIR}/profile-resource.json")"
PROFILE_FIXTURE_REVISION="$(jq -r '.defaultFixture.revision' "${WORK_DIR}/profile-resource.json")"
PROFILE_FIXTURE_FINGERPRINT="$(jq -r '.defaultFixture.fingerprint' "${WORK_DIR}/profile-resource.json")"
ACCOUNT_FIXTURES="$(jq -r '.defaultFixture.fixtureSetId' "${WORK_DIR}/account-resource.json")"
ACCOUNT_FIXTURE_REVISION="$(jq -r '.defaultFixture.revision' "${WORK_DIR}/account-resource.json")"
ACCOUNT_FIXTURE_FINGERPRINT="$(jq -r '.defaultFixture.fingerprint' "${WORK_DIR}/account-resource.json")"
OFFER_FIXTURES="$(jq -r '.defaultFixture.fixtureSetId' "${WORK_DIR}/offer-resource.json")"
OFFER_FIXTURE_REVISION="$(jq -r '.defaultFixture.revision' "${WORK_DIR}/offer-resource.json")"
OFFER_FIXTURE_FINGERPRINT="$(jq -r '.defaultFixture.fingerprint' "${WORK_DIR}/offer-resource.json")"

# 4. Execute one API Resource by explicitly selecting its exact Fixture Case.
jq -n --arg id "${PROFILE_RESOURCE}" --arg fp "${PROFILE_FINGERPRINT}" \
    --arg fixtureId "${PROFILE_FIXTURES}" --arg fixtureFp "${PROFILE_FIXTURE_FINGERPRINT}" \
    --argjson revision "${PROFILE_REVISION}" \
    --argjson fixtureRevision "${PROFILE_FIXTURE_REVISION}" '{
  schemaVersion: "bloge.simulationCommand.v2",
  subject: {kind: "API_RESOURCE", resourceId: $id, revision: $revision, fingerprint: $fp},
  input: {kind: "INLINE", value: {customerId: "C-1001"}},
  fixturePlan: {kind: "CASE_CONTROLS", fixtureSet: {
    fixtureSetId: $fixtureId, revision: $fixtureRevision, fingerprint: $fixtureFp
  }, caseId: "vip-risk", unmatched: "BLOCK"},
  executionPolicy: {externalReads: {kind: "DENY"}, externalWrites: {kind: "DENY"}}
}' > "${WORK_DIR}/profile-simulation-command.json"

post_json "/api/authoring/simulations" "${DEMO_ID}:simulate:profile" \
    "${WORK_DIR}/profile-simulation-command.json" "${WORK_DIR}/profile-simulation.json" \
    "${WORK_DIR}/profile-simulation.headers"
show "Single API Fixture simulation" "${WORK_DIR}/profile-simulation.json"
require_jq '.status == "SUCCEEDED" and .output.segment == "VIP"
  and .invocations[0].execution == "MOCKED"
  and .invocations[0].egress.decision == "FIXTURE"' \
    "${WORK_DIR}/profile-simulation.json" \
    'The API Resource must return the saved Fixture without external egress.'

# 5. Compose the three exact API Resource revisions with human-readable BLOGE DSL.
cat > "${WORK_DIR}/customer-retention.bloge" <<'BLOGE_DSL'
graph customerRetentionOffer {
  input {
    customerId: String
  }
  output {
    customerId: String
    offerCode: String
    discountPercent: Decimal
    message: String
  }

  node profile : "resource:customer-profile" {
    input {
      customerId = ctx.customerId
    }
  }

  node account : "resource:account-summary" {
    input {
      customerId = ctx.customerId
    }
  }

  node offer : "resource:retention-offer" {
    input {
      customerId = ctx.customerId
      segment = profile.output.segment
      monthlySpend = account.output.monthlySpend
    }
  }
}
BLOGE_DSL

jq -n \
    --rawfile dsl "${WORK_DIR}/customer-retention.bloge" \
    --arg profileId "${PROFILE_RESOURCE}" --arg profileFp "${PROFILE_FINGERPRINT}" \
    --arg accountId "${ACCOUNT_RESOURCE}" --arg accountFp "${ACCOUNT_FINGERPRINT}" \
    --arg offerId "${OFFER_RESOURCE}" --arg offerFp "${OFFER_FINGERPRINT}" \
    --argjson profileRevision "${PROFILE_REVISION}" \
    --argjson accountRevision "${ACCOUNT_REVISION}" \
    --argjson offerRevision "${OFFER_REVISION}" '{
  schemaVersion: "bloge.reusableFlowDslSaveCommand.v1",
  displayName: "Customer retention offer tool", kind: "TOOL",
  description: "Combines CRM profile, account value and offer recommendation APIs.",
  source: {sourceId: "customer-retention.bloge", dsl: $dsl},
  dependencyPins: {
    "resource:customer-profile": {
      kind: "API_RESOURCE", resourceId: $profileId,
      revision: $profileRevision, fingerprint: $profileFp
    },
    "resource:account-summary": {
      kind: "API_RESOURCE", resourceId: $accountId,
      revision: $accountRevision, fingerprint: $accountFp
    },
    "resource:retention-offer": {
      kind: "API_RESOURCE", resourceId: $offerId,
      revision: $offerRevision, fingerprint: $offerFp
    }
  }
}' > "${WORK_DIR}/flow-command.json"

put_create "/api/authoring/flows/${FLOW_ID}" "${DEMO_ID}:flow:save" \
    "${WORK_DIR}/flow-command.json" "${WORK_DIR}/flow-save.json" \
    "${WORK_DIR}/flow-save.headers" \
    'application/vnd.bloge.reusable-flow-dsl+json'
show "Tool draft receipt" "${WORK_DIR}/flow-save.json"

FLOW_DRAFT_ID="$(jq -r '.draft.draftId' "${WORK_DIR}/flow-save.json")"
FLOW_DRAFT_REVISION="$(jq -r '.draft.revision' "${WORK_DIR}/flow-save.json")"
FLOW_DRAFT_FINGERPRINT="$(jq -r '.draft.fingerprint' "${WORK_DIR}/flow-save.json")"

jq -n --arg draftId "${FLOW_DRAFT_ID}" --arg fingerprint "${FLOW_DRAFT_FINGERPRINT}" \
    --argjson revision "${FLOW_DRAFT_REVISION}" '{
  schemaVersion: "bloge.reusableFlowPublishCommand.v1",
  source: {kind: "FLOW_DRAFT", draftId: $draftId, revision: $revision, fingerprint: $fingerprint}
}' > "${WORK_DIR}/flow-publish-command.json"

post_json "/api/authoring/flows/${FLOW_ID}:publish" "${DEMO_ID}:flow:publish" \
    "${WORK_DIR}/flow-publish-command.json" "${WORK_DIR}/flow-publish.json" \
    "${WORK_DIR}/flow-publish.headers"
show "Published Tool receipt" "${WORK_DIR}/flow-publish.json"

PUBLICATION_ID="$(jq -r '.version.publicationId' "${WORK_DIR}/flow-publish.json")"
FLOW_VERSION_REVISION="$(jq -r '.version.revision' "${WORK_DIR}/flow-publish.json")"
FLOW_VERSION_FINGERPRINT="$(jq -r '.version.fingerprint' "${WORK_DIR}/flow-publish.json")"

# 6. Execute the real DAG semantics while every node is replaced by its exact API Fixture Case.
jq -n \
    --arg publicationId "${PUBLICATION_ID}" --arg flowFp "${FLOW_VERSION_FINGERPRINT}" \
    --arg profileFixture "${PROFILE_FIXTURES}" --arg profileFixtureFp "${PROFILE_FIXTURE_FINGERPRINT}" \
    --arg accountFixture "${ACCOUNT_FIXTURES}" --arg accountFixtureFp "${ACCOUNT_FIXTURE_FINGERPRINT}" \
    --arg offerFixture "${OFFER_FIXTURES}" --arg offerFixtureFp "${OFFER_FIXTURE_FINGERPRINT}" \
    --argjson flowRevision "${FLOW_VERSION_REVISION}" \
    --argjson profileFixtureRevision "${PROFILE_FIXTURE_REVISION}" \
    --argjson accountFixtureRevision "${ACCOUNT_FIXTURE_REVISION}" \
    --argjson offerFixtureRevision "${OFFER_FIXTURE_REVISION}" '{
  schemaVersion: "bloge.simulationCommand.v2",
  subject: {kind: "FLOW_VERSION", publicationId: $publicationId,
            revision: $flowRevision, fingerprint: $flowFp},
  input: {kind: "INLINE", value: {customerId: "C-1001"}},
  fixturePlan: {kind: "BINDINGS", unmatched: "BLOCK", bindings: [
    {target: {kind: "NODE_PATH", nodePath: ["profile"]}, selection: {
      kind: "EXACT_CASE", fixtureSet: {fixtureSetId: $profileFixture,
        revision: $profileFixtureRevision, fingerprint: $profileFixtureFp}, caseId: "vip-risk"}},
    {target: {kind: "NODE_PATH", nodePath: ["account"]}, selection: {
      kind: "EXACT_CASE", fixtureSet: {fixtureSetId: $accountFixture,
        revision: $accountFixtureRevision, fingerprint: $accountFixtureFp}, caseId: "high-value"}},
    {target: {kind: "NODE_PATH", nodePath: ["offer"]}, selection: {
      kind: "EXACT_CASE", fixtureSet: {fixtureSetId: $offerFixture,
        revision: $offerFixtureRevision, fingerprint: $offerFixtureFp}, caseId: "save20"}}
  ]},
  executionPolicy: {externalReads: {kind: "DENY"}, externalWrites: {kind: "DENY"}}
}' > "${WORK_DIR}/dag-simulation-command.json"

post_json "/api/authoring/simulations" "${DEMO_ID}:simulate:dag" \
    "${WORK_DIR}/dag-simulation-command.json" "${WORK_DIR}/dag-simulation.json" \
    "${WORK_DIR}/dag-simulation.headers"
show "Three-API DAG simulation" "${WORK_DIR}/dag-simulation.json"
require_jq '.status == "SUCCEEDED" and .output.offerCode == "SAVE20"
  and (.invocations | length) == 3
  and all(.invocations[]; .execution == "MOCKED" and .egress.decision == "FIXTURE")' \
    "${WORK_DIR}/dag-simulation.json" \
    'The Tool DAG must execute three exact mocked nodes and return SAVE20 without egress.'

# 7. Define a whole-Tool Fixture. This is a second capability: it replaces the
#    published Tool as one subject instead of running its internal DAG nodes.
jq -n --arg publicationId "${PUBLICATION_ID}" --arg fingerprint "${FLOW_VERSION_FINGERPRINT}" \
    --argjson revision "${FLOW_VERSION_REVISION}" '{
  schemaVersion: "bloge.fixtureSetCommand.v1",
  displayName: "Approved retention-tool outcome",
  subject: {kind: "FLOW_VERSION", publicationId: $publicationId,
            revision: $revision, fingerprint: $fingerprint},
  cases: [{
    caseId: "save20-tool", name: "Whole tool returns approved SAVE20 outcome",
    input: {customerId: "C-1001"},
    controls: [{target: {kind: "SUBJECT"}, behavior: {kind: "RETURN", material: {
      kind: "INLINE", value: {
        customerId: "C-1001", offerCode: "SAVE20", discountPercent: 20,
        message: "20% off the next renewal"
      }
    }}, fidelity: "OUTPUT_LEVEL"}],
    expect: {output: {
      customerId: "C-1001", offerCode: "SAVE20", discountPercent: 20,
      message: "20% off the next renewal"
    }}
  }]
}' > "${WORK_DIR}/tool-fixture-command.json"

put_create "/api/authoring/fixture-sets/${FLOW_FIXTURES}" \
    "${DEMO_ID}:fixture:tool" "${WORK_DIR}/tool-fixture-command.json" \
    "${WORK_DIR}/tool-fixture.json" "${WORK_DIR}/tool-fixture.headers"
show "Whole-Tool Fixture receipt" "${WORK_DIR}/tool-fixture.json"

FLOW_FIXTURE_REVISION="$(jq -r '.revision' "${WORK_DIR}/tool-fixture.json")"
FLOW_FIXTURE_FINGERPRINT="$(jq -r '.fingerprint' "${WORK_DIR}/tool-fixture.json")"

# 8. Execute the published Tool as one mocked subject. Whole-Tool replacement uses the
# frozen v1 FIXTURE_CASE source; v2 CASE_CONTROLS is reserved for API/component targets
# and DAG node/call-site bindings.
jq -n --arg fixtureId "${FLOW_FIXTURES}" \
    --argjson fixtureRevision "${FLOW_FIXTURE_REVISION}" '{
  schemaVersion: "bloge.simulationRequest.v1",
  source: {kind: "FIXTURE_CASE", fixtureSetId: $fixtureId,
           revision: $fixtureRevision, caseId: "save20-tool"},
  executionPolicy: {externalReads: {kind: "DENY"}, externalWrites: {kind: "DENY"}}
}' > "${WORK_DIR}/tool-simulation-command.json"

post_json "/api/authoring/simulations" "${DEMO_ID}:simulate:tool" \
    "${WORK_DIR}/tool-simulation-command.json" "${WORK_DIR}/tool-simulation.json" \
    "${WORK_DIR}/tool-simulation.headers"
show "Whole-Tool Fixture simulation" "${WORK_DIR}/tool-simulation.json"
require_jq '.status == "SUCCEEDED" and .output.offerCode == "SAVE20"
  and (.nodes | length) == 1
  and .nodes[0].nodeId == "subject"
  and .nodes[0].execution == "MOCKED"
  and .nodes[0].egress.decision == "FIXTURE"
  and .verdicts.assertions == "PASSED"' \
    "${WORK_DIR}/tool-simulation.json" \
    'The whole Tool must be replaced by one saved Fixture Case without running internal nodes.'

printf '\nDemo completed successfully.\n'
printf '  API Resource simulation run: %s\n' "$(jq -r '.runId' "${WORK_DIR}/profile-simulation.json")"
printf '  DAG simulation run:          %s\n' "$(jq -r '.runId' "${WORK_DIR}/dag-simulation.json")"
printf '  Whole-Tool simulation run:   %s\n' "$(jq -r '.runId' "${WORK_DIR}/tool-simulation.json")"
printf '  Published Tool:              %s@%s\n' "${PUBLICATION_ID}" "${FLOW_VERSION_REVISION}"
printf '  No real external request was authorized or attempted.\n'

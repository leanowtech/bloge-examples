package protocolcert

import (
	"bytes"
	"encoding/json"
	"os"
	"path/filepath"
	"runtime"
	"testing"
)

func TestVerifiesServerProducedObservationWithoutJavaDependencies(t *testing.T) {
	result := VerifyFixture(loadFixture(t))

	expected := VerificationResult{
		Outcome:                "VERIFIED",
		ReasonCode:             "VERIFIED",
		ObservationID:          "outcome-refund-boundary",
		ObservationFingerprint: "sha256:5a12fd6db51178921b8098a0e61c04cd0dfc33524c410a25cc9e77494574534d",
		UnitID:                 "refund-boundary",
		Reconciliation:         "MATCH",
		KeyID:                  "memory-ed25519:2598df82-59b9-4954-a5fd-ee08f7c50e89",
	}
	if result != expected {
		t.Fatalf("verification mismatch:\nactual:   %#v\nexpected: %#v", result, expected)
	}
}

func TestRejectsProducerClaimThatDisagreesWithAuthorityClosure(t *testing.T) {
	value := loadFixture(t)
	observation(value)["reconciliation"] = "MISMATCH"

	assertReason(t, value, "OUTCOME_RECONCILIATION_DERIVATION_INVALID")
}

func TestRejectsContentAddressAuthorityIdentityAndSignatureTampering(t *testing.T) {
	fingerprintTamper := loadFixture(t)
	observation(fingerprintTamper)["observationFingerprint"] =
		"sha256:ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"
	assertReason(t, fingerprintTamper, "OUTCOME_OBSERVATION_FINGERPRINT_INVALID")

	authorityTamper := loadFixture(t)
	facts := observation(authorityTamper)["authorityFacts"].([]any)
	facts[0].(map[string]any)["subjectFingerprint"] =
		"sha256:ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"
	assertReason(t, authorityTamper, "OUTCOME_ATTRIBUTION_CLOSURE_INVALID")

	signatureTamper := loadFixture(t)
	seal := observation(signatureTamper)["observationSeal"].(map[string]any)
	signature := seal["signature"].(string)
	replacement := "A"
	if signature[0] == 'A' {
		replacement = "B"
	}
	seal["signature"] = replacement + signature[1:]
	assertReason(t, signatureTamper, "OUTCOME_OBSERVATION_SIGNATURE_INVALID")
}

func TestRejectsUnknownFieldsInsteadOfAcceptingProtocolDrift(t *testing.T) {
	value := loadFixture(t)
	observation(value)["producerSaysValid"] = true

	assertReason(t, value, "OUTCOME_OBSERVATION_SCHEMA_INVALID")
}

func TestRejectsJavaNonCanonicalInstantSpelling(t *testing.T) {
	value := loadFixture(t)
	value.(map[string]any)["verificationTime"] = "2026-07-26T04:00:00.000Z"

	assertReason(t, value, "OUTCOME_OBSERVATION_VERIFICATION_TIME_INVALID")
}

func TestRetiredKeyStillVerifiesHistoricalEvidence(t *testing.T) {
	value := loadFixture(t)
	value.(map[string]any)["verificationKey"].(map[string]any)["state"] = "RETIRED"

	if result := VerifyFixture(value); result.Outcome != "VERIFIED" {
		t.Fatalf("retired historical key was rejected: %#v", result)
	}
}

func assertReason(t *testing.T, value any, expected string) {
	t.Helper()
	if actual := VerifyFixture(value).ReasonCode; actual != expected {
		t.Fatalf("reason mismatch: actual=%s expected=%s", actual, expected)
	}
}

func observation(value any) map[string]any {
	return value.(map[string]any)["observation"].(map[string]any)
}

func loadFixture(t *testing.T) any {
	t.Helper()
	_, source, _, ok := runtime.Caller(0)
	if !ok {
		t.Fatal("cannot locate test source")
	}
	path := filepath.Join(
		filepath.Dir(source),
		"..", "..", "..", "docs", "schemas", "resource-gateway-mirror",
		"authoritative-outcome-observation-stage1-v1.fixture.json",
	)
	content, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	decoder := json.NewDecoder(bytes.NewReader(content))
	decoder.UseNumber()
	var value any
	if err := decoder.Decode(&value); err != nil {
		t.Fatal(err)
	}
	return value
}

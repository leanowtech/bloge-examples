package protocolcert

import (
	"bytes"
	"crypto/ed25519"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"math"
	"regexp"
	"sort"
	"strings"
	"time"
)

// VerificationResult is a bounded, payload-free cross-language certification result.
type VerificationResult struct {
	Outcome                string
	ReasonCode             string
	ObservationID          string
	ObservationFingerprint string
	UnitID                 string
	Reconciliation         string
	KeyID                  string
}

const (
	fixtureVersion            = "resourceGateway.authoritativeOutcomeObservationCompatibility.v1"
	observationVersion        = "resourceGateway.authoritativeOutcomeObservation.v1"
	keyVersion                = "toolStudio.resourceGateway.evidenceVerificationKey.v1"
	sealVersion               = "bloge.visualRunEvidenceSeal.v1"
	maximumObservationBytes   = 4 * 1024 * 1024
	maximumAttestationBytes   = 16 * 1024
	maximumClockSkew          = 2 * time.Minute
	keyCreationSkew           = 5 * time.Minute
	attestationDomain         = "RESOURCE_GATEWAY_AUTHORITATIVE_OUTCOME_OBSERVATION_V1"
	schemaInvalid             = "OUTCOME_OBSERVATION_SCHEMA_INVALID"
	attributionClosureInvalid = "OUTCOME_ATTRIBUTION_CLOSURE_INVALID"
)

var (
	identifierPattern  = regexp.MustCompile(`^[A-Za-z0-9][A-Za-z0-9@._:/-]{0,511}$`)
	fingerprintPattern = regexp.MustCompile(`^sha256:[a-f0-9]{64}$`)
	instantPattern     = regexp.MustCompile(`^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{3}(?:\d{3}(?:\d{3})?)?)?Z$`)

	fixtureFields = []string{
		"schemaVersion", "verificationTime", "verificationKey", "observation",
	}
	keyFields = []string{
		"schemaVersion", "keyId", "algorithm", "encodedPublicKey", "createdAt",
		"state", "provider",
	}
	observationFields = []string{
		"schemaVersion", "observationId", "revision", "observationFingerprint",
		"scope", "inventoryRef", "unitId", "scenarioCaseRef",
		"targetCapabilityRef", "outcomeDefinitionRef", "attributionPolicyRef",
		"authoritySetRef", "selectionProof", "subjectFingerprint",
		"attributionKeyFingerprint", "modelOutcomeFingerprint",
		"attributionWindow", "reconciledAt", "attestedAt",
		"authorityWatermarks", "authorityFacts", "reconciliation",
		"evidenceComplete", "observationSeal",
	}
	scopeFields = []string{
		"tenantId", "organizationId", "projectId", "environmentId", "region",
	}
	artifactFields  = []string{"kind", "id", "revision", "fingerprint"}
	selectionFields = []string{
		"cohortRef", "samplingFrameRef", "stratumId", "inclusionFingerprint",
		"selectedAt", "eligiblePopulationSize", "selectedPopulationSize",
		"sampleOrdinal", "selectionMode",
	}
	windowFields    = []string{"actionOccurredAt", "opensAt", "closesAt"}
	watermarkFields = []string{
		"authorityId", "watermarkRef", "eventTimeThrough", "publishedAt",
	}
	factFields = []string{
		"authorityId", "sourceRef", "subjectFingerprint",
		"attributionKeyFingerprint", "outcomeFingerprint", "occurredAt",
		"recordedAt", "evidenceComplete",
	}
	sealFields = []string{
		"schemaVersion", "materialFingerprint", "algorithm", "keyId", "signedAt",
		"signature",
	}
)

type verificationFailure struct {
	reasonCode string
}

// DecodeFixture preserves JSON integer spelling for canonical cross-language verification.
func DecodeFixture(reader io.Reader) any {
	decoder := json.NewDecoder(reader)
	decoder.UseNumber()
	var value any
	if err := decoder.Decode(&value); err != nil {
		fail(schemaInvalid)
	}
	var trailing any
	if err := decoder.Decode(&trailing); err != io.EOF {
		fail(schemaInvalid)
	}
	return value
}

// VerifyFixture independently verifies one fixed public-only observation fixture.
func VerifyFixture(value any) (result VerificationResult) {
	coordinates := coordinatesFrom(value)
	defer func() {
		if recovered := recover(); recovered != nil {
			reason := "OUTCOME_OBSERVATION_CLOSURE_INVALID"
			if expected, ok := recovered.(verificationFailure); ok {
				reason = expected.reasonCode
			}
			outcome := "INVALID"
			if isPolicyReason(reason) {
				outcome = "POLICY_REJECTED"
			}
			result = resultFor(outcome, reason, coordinates)
		}
	}()

	fixture := object(value, schemaInvalid)
	exactFields(fixture, fixtureFields, schemaInvalid)
	requireText(fixture, "schemaVersion", fixtureVersion)
	verificationTime := instant(
		fixture["verificationTime"],
		"OUTCOME_OBSERVATION_VERIFICATION_TIME_INVALID",
	)
	key := object(fixture["verificationKey"], schemaInvalid)
	exactFields(key, keyFields, schemaInvalid)
	requireText(key, "schemaVersion", keyVersion)
	verifyKeyShape(key)
	observation := object(fixture["observation"], schemaInvalid)

	verifyShape(observation)
	attestedAt := verifySemantics(observation)
	verifyFingerprint(observation)
	verifySeal(observation, key, attestedAt, verificationTime)
	return resultFor("VERIFIED", "VERIFIED", coordinates)
}

func verifyShape(observation map[string]any) {
	exactFields(observation, observationFields, schemaInvalid)
	requireText(observation, "schemaVersion", observationVersion)
	identifier(observation["observationId"])
	identifier(observation["unitId"])
	positiveInteger(observation["revision"])
	fingerprint(observation["observationFingerprint"])
	scope := object(observation["scope"], schemaInvalid)
	exactFields(scope, scopeFields, schemaInvalid)
	for _, field := range scopeFields {
		identifier(scope[field])
	}

	references := [][2]string{
		{"inventoryRef", "DOMAIN_FIDELITY_INVENTORY"},
		{"scenarioCaseRef", "SCENARIO_CASE"},
		{"targetCapabilityRef", "CAPABILITY"},
		{"outcomeDefinitionRef", "OUTCOME_DEFINITION"},
		{"attributionPolicyRef", "OUTCOME_ATTRIBUTION_POLICY"},
		{"authoritySetRef", "OUTCOME_AUTHORITY_SET"},
	}
	for _, reference := range references {
		artifactRef(observation[reference[0]], reference[1])
	}
	fingerprint(observation["subjectFingerprint"])
	fingerprint(observation["attributionKeyFingerprint"])
	fingerprint(observation["modelOutcomeFingerprint"])

	selection := object(observation["selectionProof"], schemaInvalid)
	exactFields(selection, selectionFields, schemaInvalid)
	artifactRef(selection["cohortRef"], "OUTCOME_CALIBRATION_COHORT")
	artifactRef(selection["samplingFrameRef"], "OUTCOME_SAMPLING_FRAME")
	identifier(selection["stratumId"])
	fingerprint(selection["inclusionFingerprint"])
	instant(selection["selectedAt"], "OUTCOME_COHORT_SELECTION_INVALID")
	positiveInteger(selection["eligiblePopulationSize"])
	positiveInteger(selection["selectedPopulationSize"])
	positiveInteger(selection["sampleOrdinal"])
	oneOf(selection["selectionMode"], "CENSUS", "HASH_PARTITION", "STRATIFIED_RANDOM")

	window := object(observation["attributionWindow"], schemaInvalid)
	exactFields(window, windowFields, schemaInvalid)
	for _, field := range windowFields {
		instant(window[field], "OUTCOME_ATTRIBUTION_TIME_INVALID")
	}
	instant(observation["reconciledAt"], "OUTCOME_RECONCILIATION_TIME_INVALID")
	instant(observation["attestedAt"], "OUTCOME_ATTESTATION_TIME_INVALID")
	oneOf(observation["reconciliation"], "MATCH", "MISMATCH", "PENDING", "CENSORED", "CONFLICT")
	boolean(observation["evidenceComplete"])

	watermarks := array(observation["authorityWatermarks"], schemaInvalid)
	if len(watermarks) < 1 || len(watermarks) > 64 {
		fail(schemaInvalid)
	}
	for _, raw := range watermarks {
		watermark := object(raw, schemaInvalid)
		exactFields(watermark, watermarkFields, schemaInvalid)
		identifier(watermark["authorityId"])
		artifactRef(watermark["watermarkRef"], "AUTHORITATIVE_OUTCOME_SOURCE_WATERMARK")
		instant(watermark["eventTimeThrough"], "OUTCOME_AUTHORITY_WATERMARK_INVALID")
		instant(watermark["publishedAt"], "OUTCOME_AUTHORITY_WATERMARK_INVALID")
	}

	facts := array(observation["authorityFacts"], schemaInvalid)
	if len(facts) > 1024 {
		fail(schemaInvalid)
	}
	for _, raw := range facts {
		fact := object(raw, schemaInvalid)
		exactFields(fact, factFields, schemaInvalid)
		identifier(fact["authorityId"])
		artifactRef(fact["sourceRef"], "AUTHORITATIVE_OUTCOME_SOURCE_RECORD")
		fingerprint(fact["subjectFingerprint"])
		fingerprint(fact["attributionKeyFingerprint"])
		fingerprint(fact["outcomeFingerprint"])
		instant(fact["occurredAt"], attributionClosureInvalid)
		instant(fact["recordedAt"], attributionClosureInvalid)
		boolean(fact["evidenceComplete"])
	}

	seal := object(observation["observationSeal"], schemaInvalid)
	exactFields(seal, sealFields, schemaInvalid)
	requireText(seal, "schemaVersion", sealVersion)
	fingerprint(seal["materialFingerprint"])
	requireText(seal, "algorithm", "Ed25519")
	identifier(seal["keyId"])
	instant(seal["signedAt"], "OUTCOME_OBSERVATION_SEAL_TIME_INVALID")
	if len(decodeBase64(seal["signature"], schemaInvalid)) != ed25519.SignatureSize {
		fail(schemaInvalid)
	}
}

func verifyKeyShape(key map[string]any) {
	identifier(key["keyId"])
	if text(key["algorithm"]) == "" ||
		len(decodeBase64(key["encodedPublicKey"], schemaInvalid)) == 0 ||
		instant(key["createdAt"], schemaInvalid).Equal(time.Unix(0, 0)) ||
		text(key["state"]) == "" {
		fail(schemaInvalid)
	}
	text(key["provider"])
}

func verifySemantics(observation map[string]any) time.Time {
	selection := object(observation["selectionProof"], "OUTCOME_COHORT_SELECTION_INVALID")
	window := object(observation["attributionWindow"], "OUTCOME_ATTRIBUTION_TIME_INVALID")
	actionAt := instant(window["actionOccurredAt"], "OUTCOME_ATTRIBUTION_TIME_INVALID")
	opensAt := instant(window["opensAt"], "OUTCOME_ATTRIBUTION_TIME_INVALID")
	closesAt := instant(window["closesAt"], "OUTCOME_ATTRIBUTION_TIME_INVALID")
	selectedAt := instant(selection["selectedAt"], "OUTCOME_COHORT_SELECTION_INVALID")
	reconciledAt := instant(observation["reconciledAt"], "OUTCOME_RECONCILIATION_TIME_INVALID")
	attestedAt := instant(observation["attestedAt"], "OUTCOME_ATTESTATION_TIME_INVALID")
	eligible := integer(selection["eligiblePopulationSize"])
	selected := integer(selection["selectedPopulationSize"])
	ordinal := integer(selection["sampleOrdinal"])
	if !selectedAt.Before(actionAt) ||
		opensAt.Before(actionAt) ||
		!closesAt.After(opensAt) ||
		closesAt.Sub(opensAt) > 365*24*time.Hour ||
		reconciledAt.Before(actionAt) ||
		attestedAt.Before(reconciledAt) ||
		selected > eligible ||
		ordinal > selected ||
		text(selection["selectionMode"]) == "CENSUS" && selected != eligible {
		fail("OUTCOME_COHORT_SELECTION_INVALID")
	}

	authorities := map[string]struct{}{}
	watermarkRefs := map[string]struct{}{}
	previousAuthority := ""
	var minimumEventTime time.Time
	for _, raw := range array(observation["authorityWatermarks"], "OUTCOME_AUTHORITY_WATERMARK_INVALID") {
		watermark := object(raw, "OUTCOME_AUTHORITY_WATERMARK_INVALID")
		authority := text(watermark["authorityId"])
		through := instant(watermark["eventTimeThrough"], "OUTCOME_AUTHORITY_WATERMARK_INVALID")
		published := instant(watermark["publishedAt"], "OUTCOME_AUTHORITY_WATERMARK_INVALID")
		reference := artifactIdentity(watermark["watermarkRef"])
		_, duplicateAuthority := authorities[authority]
		_, duplicateReference := watermarkRefs[reference]
		if duplicateAuthority ||
			duplicateReference ||
			authority <= previousAuthority ||
			through.After(published) ||
			published.After(reconciledAt) {
			fail("OUTCOME_AUTHORITY_WATERMARK_INVALID")
		}
		authorities[authority] = struct{}{}
		watermarkRefs[reference] = struct{}{}
		previousAuthority = authority
		if minimumEventTime.IsZero() || through.Before(minimumEventTime) {
			minimumEventTime = through
		}
	}

	sourceRefs := map[string]struct{}{}
	outcomes := map[string]struct{}{}
	var previous *factOrder
	evidenceComplete := true
	for _, raw := range array(observation["authorityFacts"], attributionClosureInvalid) {
		fact := object(raw, attributionClosureInvalid)
		authority := text(fact["authorityId"])
		occurredAt := instant(fact["occurredAt"], attributionClosureInvalid)
		recordedAt := instant(fact["recordedAt"], attributionClosureInvalid)
		reference := artifactIdentity(fact["sourceRef"])
		source := object(fact["sourceRef"], attributionClosureInvalid)
		order := factOrder{
			authorityID:       authority,
			occurredAt:        occurredAt,
			sourceFingerprint: text(source["fingerprint"]),
		}
		_, authorityExists := authorities[authority]
		_, duplicateReference := sourceRefs[reference]
		if !authorityExists ||
			duplicateReference ||
			previous != nil && previous.compare(order) >= 0 ||
			fact["subjectFingerprint"] != observation["subjectFingerprint"] ||
			fact["attributionKeyFingerprint"] != observation["attributionKeyFingerprint"] ||
			occurredAt.Before(opensAt) ||
			occurredAt.After(closesAt) ||
			recordedAt.Before(occurredAt) ||
			recordedAt.After(reconciledAt) {
			fail(attributionClosureInvalid)
		}
		sourceRefs[reference] = struct{}{}
		copy := order
		previous = &copy
		outcomes[text(fact["outcomeFingerprint"])] = struct{}{}
		evidenceComplete = evidenceComplete && boolean(fact["evidenceComplete"])
	}

	derived := ""
	if minimumEventTime.Before(closesAt) {
		derived = "PENDING"
	} else if len(outcomes) == 0 {
		derived = "CENSORED"
	} else if len(outcomes) > 1 {
		derived = "CONFLICT"
	} else if _, matches := outcomes[text(observation["modelOutcomeFingerprint"])]; matches {
		derived = "MATCH"
	} else {
		derived = "MISMATCH"
	}
	if derived != observation["reconciliation"] {
		fail("OUTCOME_RECONCILIATION_DERIVATION_INVALID")
	}
	if evidenceComplete != boolean(observation["evidenceComplete"]) {
		fail("OUTCOME_EVIDENCE_COMPLETENESS_INVALID")
	}
	return attestedAt
}

func verifyFingerprint(observation map[string]any) {
	actual := sha256Fingerprint(
		producerFingerprintMaterial(observation),
		maximumObservationBytes,
	)
	if text(observation["observationFingerprint"]) != actual {
		fail("OUTCOME_OBSERVATION_FINGERPRINT_INVALID")
	}
}

func verifySeal(
	observation map[string]any,
	key map[string]any,
	attestedAt time.Time,
	verificationTime time.Time,
) {
	seal := object(observation["observationSeal"], schemaInvalid)
	if seal["keyId"] != key["keyId"] {
		fail("OUTCOME_OBSERVATION_KEY_ID_MISMATCH")
	}
	if key["algorithm"] != "Ed25519" ||
		seal["algorithm"] != key["algorithm"] {
		fail("OUTCOME_OBSERVATION_SIGNATURE_ALGORITHM_REJECTED")
	}
	if key["state"] != "ACTIVE" && key["state"] != "RETIRED" {
		fail("OUTCOME_OBSERVATION_KEY_POLICY_REJECTED")
	}
	keyCreatedAt := instant(key["createdAt"], "OUTCOME_OBSERVATION_KEY_POLICY_REJECTED")
	signedAt := instant(seal["signedAt"], "OUTCOME_OBSERVATION_SEAL_TIME_INVALID")
	if attestedAt.Before(keyCreatedAt.Add(-keyCreationSkew)) ||
		attestedAt.After(verificationTime.Add(maximumClockSkew)) {
		fail("OUTCOME_OBSERVATION_KEY_POLICY_REJECTED")
	}
	if signedAt.Before(attestedAt.Add(-maximumClockSkew)) ||
		signedAt.After(attestedAt.Add(maximumClockSkew)) {
		fail("OUTCOME_OBSERVATION_SEAL_TIME_INVALID")
	}
	materialFingerprint := sha256Fingerprint(
		attestationMaterial(observation),
		maximumAttestationBytes,
	)
	if materialFingerprint != seal["materialFingerprint"] {
		fail("OUTCOME_OBSERVATION_ATTESTATION_MATERIAL_INVALID")
	}

	encodedKey := decodeBase64(
		key["encodedPublicKey"],
		"OUTCOME_OBSERVATION_SIGNATURE_MATERIAL_INVALID",
	)
	parsedKey, err := x509.ParsePKIXPublicKey(encodedKey)
	if err != nil {
		fail("OUTCOME_OBSERVATION_SIGNATURE_MATERIAL_INVALID")
	}
	publicKey, ok := parsedKey.(ed25519.PublicKey)
	if !ok {
		fail("OUTCOME_OBSERVATION_SIGNATURE_MATERIAL_INVALID")
	}
	signature := decodeBase64(
		seal["signature"],
		"OUTCOME_OBSERVATION_SIGNATURE_MATERIAL_INVALID",
	)
	if !ed25519.Verify(publicKey, []byte(materialFingerprint), signature) {
		fail("OUTCOME_OBSERVATION_SIGNATURE_INVALID")
	}
}

func producerFingerprintMaterial(observation map[string]any) map[string]any {
	selection := object(observation["selectionProof"], attributionClosureInvalid)
	watermarks := make([]any, 0)
	for _, raw := range array(observation["authorityWatermarks"], attributionClosureInvalid) {
		value := object(raw, attributionClosureInvalid)
		watermarks = append(watermarks, map[string]any{
			"authorityId":      value["authorityId"],
			"watermarkRef":     orderedArtifact(value["watermarkRef"]),
			"eventTimeThrough": value["eventTimeThrough"],
			"publishedAt":      value["publishedAt"],
		})
	}
	facts := make([]any, 0)
	for _, raw := range array(observation["authorityFacts"], attributionClosureInvalid) {
		value := object(raw, attributionClosureInvalid)
		facts = append(facts, map[string]any{
			"authorityId":               value["authorityId"],
			"sourceRef":                 orderedArtifact(value["sourceRef"]),
			"subjectFingerprint":        value["subjectFingerprint"],
			"attributionKeyFingerprint": value["attributionKeyFingerprint"],
			"outcomeFingerprint":        value["outcomeFingerprint"],
			"occurredAt":                value["occurredAt"],
			"recordedAt":                value["recordedAt"],
			"evidenceComplete":          value["evidenceComplete"],
		})
	}
	return map[string]any{
		"schemaVersion":          observation["schemaVersion"],
		"observationId":          observation["observationId"],
		"revision":               observation["revision"],
		"observationFingerprint": "",
		"scope":                  ordered(object(observation["scope"], attributionClosureInvalid), scopeFields),
		"inventoryRef":           orderedArtifact(observation["inventoryRef"]),
		"unitId":                 observation["unitId"],
		"scenarioCaseRef":        orderedArtifact(observation["scenarioCaseRef"]),
		"targetCapabilityRef":    orderedArtifact(observation["targetCapabilityRef"]),
		"outcomeDefinitionRef":   orderedArtifact(observation["outcomeDefinitionRef"]),
		"attributionPolicyRef":   orderedArtifact(observation["attributionPolicyRef"]),
		"authoritySetRef":        orderedArtifact(observation["authoritySetRef"]),
		"selectionProof": map[string]any{
			"cohortRef":              orderedArtifact(selection["cohortRef"]),
			"samplingFrameRef":       orderedArtifact(selection["samplingFrameRef"]),
			"stratumId":              selection["stratumId"],
			"inclusionFingerprint":   selection["inclusionFingerprint"],
			"selectedAt":             selection["selectedAt"],
			"eligiblePopulationSize": selection["eligiblePopulationSize"],
			"selectedPopulationSize": selection["selectedPopulationSize"],
			"sampleOrdinal":          selection["sampleOrdinal"],
			"selectionMode":          selection["selectionMode"],
		},
		"subjectFingerprint":        observation["subjectFingerprint"],
		"attributionKeyFingerprint": observation["attributionKeyFingerprint"],
		"modelOutcomeFingerprint":   observation["modelOutcomeFingerprint"],
		"attributionWindow": ordered(
			object(observation["attributionWindow"], attributionClosureInvalid),
			windowFields,
		),
		"reconciledAt":        observation["reconciledAt"],
		"attestedAt":          observation["attestedAt"],
		"authorityWatermarks": watermarks,
		"authorityFacts":      facts,
		"reconciliation":      observation["reconciliation"],
		"evidenceComplete":    observation["evidenceComplete"],
		"observationSeal": map[string]any{
			"schemaVersion":       sealVersion,
			"materialFingerprint": "",
			"algorithm":           "",
			"keyId":               "",
			"signedAt":            "1970-01-01T00:00:00Z",
			"signature":           "",
		},
	}
}

func attestationMaterial(observation map[string]any) map[string]any {
	return map[string]any{
		"domain":                 attestationDomain,
		"schemaVersion":          observation["schemaVersion"],
		"observationId":          observation["observationId"],
		"revision":               observation["revision"],
		"inventoryRef":           orderedArtifact(observation["inventoryRef"]),
		"unitId":                 observation["unitId"],
		"reconciledAt":           observation["reconciledAt"],
		"attestedAt":             observation["attestedAt"],
		"observationFingerprint": observation["observationFingerprint"],
	}
}

func sha256Fingerprint(value any, maximumBytes int) string {
	var buffer bytes.Buffer
	encoder := json.NewEncoder(&buffer)
	encoder.SetEscapeHTML(false)
	if err := encoder.Encode(value); err != nil {
		fail("OUTCOME_OBSERVATION_CANONICAL_ENCODING_INVALID")
	}
	encoded := bytes.TrimSuffix(buffer.Bytes(), []byte("\n"))
	if len(encoded) > maximumBytes {
		fail("OUTCOME_OBSERVATION_CANONICAL_SIZE_INVALID")
	}
	digest := sha256.Sum256(encoded)
	return "sha256:" + hex.EncodeToString(digest[:])
}

func artifactRef(value any, expectedKind string) {
	reference := object(value, schemaInvalid)
	exactFields(reference, artifactFields, schemaInvalid)
	requireText(reference, "kind", expectedKind)
	identifier(reference["id"])
	positiveInteger(reference["revision"])
	fingerprint(reference["fingerprint"])
}

func artifactIdentity(value any) string {
	reference := object(value, attributionClosureInvalid)
	return fmt.Sprintf(
		"%s\x00%s\x00%d\x00%s",
		text(reference["kind"]),
		text(reference["id"]),
		integer(reference["revision"]),
		text(reference["fingerprint"]),
	)
}

func orderedArtifact(value any) map[string]any {
	return ordered(object(value, attributionClosureInvalid), artifactFields)
}

func ordered(source map[string]any, fields []string) map[string]any {
	result := make(map[string]any, len(fields))
	for _, field := range fields {
		result[field] = source[field]
	}
	return result
}

func exactFields(value map[string]any, expected []string, reasonCode string) {
	actual := make([]string, 0, len(value))
	for field := range value {
		actual = append(actual, field)
	}
	sort.Strings(actual)
	required := append([]string(nil), expected...)
	sort.Strings(required)
	if len(actual) != len(required) {
		fail(reasonCode)
	}
	for index := range actual {
		if actual[index] != required[index] {
			fail(reasonCode)
		}
	}
}

func object(value any, reasonCode string) map[string]any {
	result, ok := value.(map[string]any)
	if !ok {
		fail(reasonCode)
	}
	return result
}

func array(value any, reasonCode string) []any {
	result, ok := value.([]any)
	if !ok {
		fail(reasonCode)
	}
	return result
}

func text(value any) string {
	result, ok := value.(string)
	if !ok {
		fail(schemaInvalid)
	}
	return result
}

func boolean(value any) bool {
	result, ok := value.(bool)
	if !ok {
		fail(schemaInvalid)
	}
	return result
}

func integer(value any) int64 {
	switch candidate := value.(type) {
	case json.Number:
		result, err := candidate.Int64()
		if err != nil {
			fail(schemaInvalid)
		}
		return result
	case float64:
		if math.Trunc(candidate) != candidate ||
			candidate < math.MinInt64 ||
			candidate > math.MaxInt64 {
			fail(schemaInvalid)
		}
		return int64(candidate)
	case int:
		return int64(candidate)
	case int64:
		return candidate
	default:
		fail(schemaInvalid)
	}
	return 0
}

func positiveInteger(value any) int64 {
	result := integer(value)
	if result < 1 {
		fail(schemaInvalid)
	}
	return result
}

func identifier(value any) string {
	result := text(value)
	if !identifierPattern.MatchString(result) {
		fail(schemaInvalid)
	}
	return result
}

func fingerprint(value any) string {
	result := text(value)
	if !fingerprintPattern.MatchString(result) {
		fail(schemaInvalid)
	}
	return result
}

func instant(value any, reasonCode string) time.Time {
	candidate, ok := value.(string)
	if !ok || !instantPattern.MatchString(candidate) {
		fail(reasonCode)
	}
	result, err := time.Parse(time.RFC3339Nano, candidate)
	if err != nil || canonicalInstant(result) != candidate {
		fail(reasonCode)
	}
	return result
}

func canonicalInstant(value time.Time) string {
	result := value.UTC().Format("2006-01-02T15:04:05")
	nanoseconds := value.Nanosecond()
	if nanoseconds == 0 {
		return result + "Z"
	}
	fraction := fmt.Sprintf("%09d", nanoseconds)
	switch {
	case nanoseconds%1_000_000 == 0:
		fraction = fraction[:3]
	case nanoseconds%1_000 == 0:
		fraction = fraction[:6]
	}
	return result + "." + fraction + "Z"
}

func decodeBase64(value any, reasonCode string) []byte {
	candidate, ok := value.(string)
	if !ok || candidate == "" {
		fail(reasonCode)
	}
	decoded, err := base64.StdEncoding.DecodeString(candidate)
	if err != nil || base64.StdEncoding.EncodeToString(decoded) != candidate {
		fail(reasonCode)
	}
	return decoded
}

func requireText(value map[string]any, field string, expected string) {
	if candidate, ok := value[field].(string); !ok || candidate != expected {
		fail(schemaInvalid)
	}
}

func oneOf(value any, allowed ...string) string {
	candidate := text(value)
	for _, permitted := range allowed {
		if candidate == permitted {
			return candidate
		}
	}
	fail(schemaInvalid)
	return ""
}

func coordinatesFrom(value any) VerificationResult {
	result := VerificationResult{}
	fixture, ok := value.(map[string]any)
	if !ok {
		return result
	}
	observation, ok := fixture["observation"].(map[string]any)
	if !ok {
		return result
	}
	result.ObservationID, _ = observation["observationId"].(string)
	result.ObservationFingerprint, _ = observation["observationFingerprint"].(string)
	result.UnitID, _ = observation["unitId"].(string)
	result.Reconciliation, _ = observation["reconciliation"].(string)
	if seal, ok := observation["observationSeal"].(map[string]any); ok {
		result.KeyID, _ = seal["keyId"].(string)
	}
	return result
}

func resultFor(outcome string, reasonCode string, coordinates VerificationResult) VerificationResult {
	coordinates.Outcome = outcome
	coordinates.ReasonCode = reasonCode
	return coordinates
}

func isPolicyReason(reasonCode string) bool {
	return reasonCode == "OUTCOME_OBSERVATION_KEY_POLICY_REJECTED" ||
		reasonCode == "OUTCOME_OBSERVATION_SIGNATURE_ALGORITHM_REJECTED"
}

func fail(reasonCode string) {
	panic(verificationFailure{reasonCode: reasonCode})
}

type factOrder struct {
	authorityID       string
	occurredAt        time.Time
	sourceFingerprint string
}

func (value factOrder) compare(other factOrder) int {
	if result := strings.Compare(value.authorityID, other.authorityID); result != 0 {
		return result
	}
	if value.occurredAt.Before(other.occurredAt) {
		return -1
	}
	if value.occurredAt.After(other.occurredAt) {
		return 1
	}
	return strings.Compare(value.sourceFingerprint, other.sourceFingerprint)
}

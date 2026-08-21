#!/usr/bin/env python3
import copy
import json
from pathlib import Path

from jsonschema import Draft202012Validator
from referencing import Registry, Resource


ROOT = Path(__file__).resolve().parents[5]
HERE = Path(__file__).resolve().parent
SCHEMA_DIR = ROOT / "docs/schemas/resource-gateway-capability-studio"


def load(path):
    return json.loads(path.read_text(encoding="utf-8"))


def replace(document, pointer, value):
    segments = [segment.replace("~1", "/").replace("~0", "~") for segment in pointer.split("/")[1:]]
    target = document
    for segment in segments[:-1]:
        target = target[int(segment)] if isinstance(target, list) else target[segment]
    last = segments[-1]
    if isinstance(target, list):
        target[int(last)] = value
    else:
        target[last] = value


def load_registry():
    registry = Registry()
    for path in SCHEMA_DIR.glob("*.schema.json"):
        schema = load(path)
        Draft202012Validator.check_schema(schema)
        registry = registry.with_resource(schema["$id"], Resource.from_contents(schema))
    return registry


def derive_a0_terminal(statuses):
    if "UNAVAILABLE" in statuses:
        return "UNAVAILABLE", "A0_UNAVAILABLE"
    if "INVALID" in statuses:
        return "INVALID", "A0_INVALID"
    if "VERIFIED" in statuses:
        return "STRUCTURE_VERIFIED", "A0_STRUCTURE_VERIFIED"
    return "INCOMPLETE", "A0_INCOMPLETE"


def derive_a1_terminal(slot_facts):
    failures = [fact for fact in slot_facts if fact["status"] == "FAIL"]
    if not failures:
        return "VERIFIED", "A1_REPLAY_VERIFIED"
    if any(fact["observedTerminal"] == "UNAVAILABLE" for fact in failures):
        return "UNAVAILABLE", "A1_REPLAY_UNAVAILABLE"
    return "INVALID", "A1_REPLAY_INVALID"


def derive_guard_status(observation):
    if not observation["available"]:
        return "UNAVAILABLE"
    if not observation["present"]:
        return "MISSING"
    return "PASS" if observation["matches"] else "FAIL"


def derive_admission(statuses):
    if "UNAVAILABLE" in statuses:
        return "UNAVAILABLE", "GATE_A_VERIFICATION_UNAVAILABLE", 3
    if "FAIL" in statuses:
        return "FAIL", "GATE_A_VERIFICATION_FAILED", 2
    if "MISSING" in statuses:
        return "OPEN", "GATE_A_REQUIRED_MATERIAL_MISSING", 4
    return "PASS", "GATE_A_ADMITTED", 0


def observation_ref_key(ref):
    fingerprint = ref["rawFingerprint"]
    return (
        ref["uri"],
        fingerprint["kind"],
        fingerprint["algorithm"],
        fingerprint["value"],
    )


def validate_catalog_lineage(catalog, authority_matrix):
    authority_facts = authority_matrix["facts"]
    authority_ids = [fact["factId"] for fact in authority_facts]
    authority_order = {fact_id: index for index, fact_id in enumerate(authority_ids)}
    assert len(authority_ids) == len(set(authority_ids)), "duplicate Authority Matrix factId"
    assert "ADMISSION_DECISION" in authority_order

    for guard in catalog["guards"]:
        sources = guard["sourceFactIds"]
        assert sources, f"{guard['guardId']}: empty sourceFactIds"
        assert len(sources) == len(set(sources)), f"{guard['guardId']}: duplicate sourceFactIds"
        assert all(source in authority_order for source in sources), (
            f"{guard['guardId']}: sourceFactIds outside Authority Matrix"
        )
        assert "ADMISSION_DECISION" not in sources, (
            f"{guard['guardId']}: ADMISSION_DECISION cannot be a Guard source fact"
        )
        positions = [authority_order[source] for source in sources]
        assert positions == sorted(positions), (
            f"{guard['guardId']}: sourceFactIds must follow Authority Matrix order"
        )

    return authority_order


def validate_a2_guard_lineage(document, catalog, authority_order, fixture_name):
    expected = catalog["guards"]
    actual = document["semanticGuardResults"]
    assert len(actual) == len(expected) == 18, f"{fixture_name}: all 18 Guards must be evaluated"

    for index, (guard, result) in enumerate(zip(expected, actual)):
        assert result["guardId"] == guard["guardId"], (
            f"{fixture_name}[{index}]: guardId drift"
        )
        assert result["admissionTarget"] == guard["admissionTarget"], (
            f"{fixture_name}[{index}]: admissionTarget drift"
        )
        assert result["sourceFactIds"] == guard["sourceFactIds"], (
            f"{fixture_name}[{index}]: sourceFactIds drift"
        )
        assert "ADMISSION_DECISION" not in result["sourceFactIds"], (
            f"{fixture_name}[{index}]: cyclic ADMISSION_DECISION source"
        )
        positions = [authority_order[source] for source in result["sourceFactIds"]]
        assert positions == sorted(positions), (
            f"{fixture_name}[{index}]: sourceFactIds order drift"
        )

        ref_keys = [observation_ref_key(ref) for ref in result["observationRefs"]]
        assert ref_keys == sorted(ref_keys), (
            f"{fixture_name}[{index}]: observationRefs are not canonical sorted"
        )
        assert len(ref_keys) == len(set(ref_keys)), (
            f"{fixture_name}[{index}]: duplicate observationRefs"
        )
        assert result["status"] in {"PASS", "FAIL", "MISSING", "UNAVAILABLE"}, (
            f"{fixture_name}[{index}]: Guard was not evaluated"
        )
        print(
            f"A2 lineage matched: {fixture_name}[{index}] -> {guard['guardId']} "
            f"[{result['status']}; sources={len(result['sourceFactIds'])}; refs={len(ref_keys)}]"
        )


def derived_mismatch(guard_id, document):
    if guard_id == "A0_SLOT_COUNT_PROJECTION":
        adapter = {status: 0 for status in ("VERIFIED", "INVALID", "UNAVAILABLE", "NOT_RUN")}
        for result in document["adapterResults"]:
            adapter[result["status"]] += 1
        obligation = {status: 0 for status in ("FAIL", "BLOCKED", "NOT_RUN")}
        for result in document["obligationResults"]:
            obligation[result["status"]] += 1
        projected = (
            document["adapterVerifiedCount"], document["adapterInvalidCount"],
            document["adapterUnavailableCount"], document["adapterNotRunCount"],
            document["obligationFailedCount"], document["obligationBlockedCount"],
            document["obligationNotRunCount"]
        )
        derived = (
            adapter["VERIFIED"], adapter["INVALID"], adapter["UNAVAILABLE"], adapter["NOT_RUN"],
            obligation["FAIL"], obligation["BLOCKED"], obligation["NOT_RUN"]
        )
        return projected != derived
    if guard_id == "A0_TERMINAL_DERIVATION":
        statuses = [result["status"] for result in document["adapterResults"]]
        derived = derive_a0_terminal(statuses)
        return (document["terminal"], document["reasonCode"]) != derived
    if guard_id == "A1_SLOT_COUNT_PROJECTION":
        passed = sum(result["status"] == "PASS" for result in document["testRuns"])
        failed = sum(result["status"] == "FAIL" for result in document["testRuns"])
        return (document["passedCount"], document["failedCount"]) != (passed, failed)
    if guard_id == "A1_SLOT_OUTCOME_BINDING":
        derived = derive_a1_terminal(document["testRuns"])
        return (document["terminal"], document["reasonCode"]) != derived
    if guard_id == "A2_CONCLUSION_PRECEDENCE":
        statuses = [slot["status"] for slot in document["requirements"]]
        statuses.extend(slot["status"] for slot in document["artifacts"])
        statuses.extend(slot["status"] for slot in document["tests"])
        statuses.extend(slot["status"] for slot in document["mandatoryGuards"])
        statuses.append(document["trustedReview"]["status"])
        statuses.extend(slot["status"] for slot in document["semanticGuardResults"])
        derived = derive_admission(statuses)[:2]
        return (document["conclusion"], document["conclusionReasonCode"]) != derived
    raise AssertionError(f"No reference guard for {guard_id}")


def main():
    catalog = load(HERE / "guard-catalog-v1.json")
    guard_ids = [guard["guardId"] for guard in catalog["guards"]]
    assert len(guard_ids) == len(set(guard_ids)), "duplicate guardId"
    guards = {guard["guardId"]: guard for guard in catalog["guards"]}
    authority_matrix = load(HERE / "authority-matrix-v1.json")
    authority_order = validate_catalog_lineage(catalog, authority_matrix)
    vectors = load(HERE / "semantic-guard-vectors-v1.json")
    collector_contract = load(HERE / "collector-contract-vectors-v1.json")
    registry = load_registry()
    design_documents = [
        (catalog, "capability-studio-gate-a-semantic-guard-catalog-v1.schema.json"),
        (authority_matrix, "capability-studio-gate-a-authority-matrix-v1.schema.json"),
        (vectors, "capability-studio-gate-a-semantic-guard-vectors-v1.schema.json"),
        (collector_contract, "capability-studio-gate-a-collector-contract-vectors-v1.schema.json"),
    ]
    for document, schema_name in design_documents:
        schema = load(SCHEMA_DIR / schema_name)
        errors = list(Draft202012Validator(schema, registry=registry).iter_errors(document))
        assert not errors, f"{schema_name}: {errors}"
        print(f"design authority schema matched: {schema_name}")
    for case in vectors["terminalDerivationCases"]:
        if case["stage"] == "A0":
            actual = derive_a0_terminal(case["adapterStatuses"])
        else:
            actual = derive_a1_terminal(case["slotFacts"])
        expected = (case["expected"]["terminal"], case["expected"]["reasonCode"])
        assert actual == expected, f"{case['caseId']}: expected {expected}, got {actual}"
        print(f"terminal derivation matched: {case['caseId']} -> {actual[0]}")
    material_attack_coverage = {case["guardId"] for case in vectors["cases"]}
    contract_coverage = set(material_attack_coverage)
    contract_coverage.update(case["guardId"] for case in collector_contract["cases"])
    assert contract_coverage == set(guard_ids), (
        f"collector contract coverage drift: missing={sorted(set(guard_ids) - contract_coverage)} "
        f"unknown={sorted(contract_coverage - set(guard_ids))}"
    )
    for case in collector_contract["cases"]:
        guard = guards[case["guardId"]]
        expected = case["expected"]
        status = derive_guard_status(case["observation"])
        statuses = case["observation"].get("slotStatuses", [status])
        conclusion, reason, exit_code = derive_admission(statuses)
        assert status == expected["guardStatus"], case["caseId"]
        assert guard["admissionTarget"] == expected["admissionTarget"], case["caseId"]
        assert (conclusion, reason, exit_code) == (
            expected["conclusion"], expected["reasonCode"], expected["exitCode"]
        ), case["caseId"]
        if "evidenceVector" in case:
            assert (HERE / case["evidenceVector"]).resolve().is_file(), case["caseId"]
        print(
            f"guard outcome matched: {case['caseId']} -> {case['guardId']} "
            f"[{guard['owner']} -> {guard['admissionTarget']} -> {conclusion}]"
        )
    for case in vectors["cases"]:
        assert case["guardId"] in guard_ids, case["guardId"]
        document = copy.deepcopy(load((HERE / case["baseFixture"]).resolve()))
        for mutation in case["mutations"]:
            assert mutation["op"] == "replace"
            replace(document, mutation["path"], mutation["value"])
        schema_path = (HERE / case["schema"]).resolve()
        schema = load(schema_path)
        errors = list(Draft202012Validator(schema, registry=registry).iter_errors(document))
        assert not errors, f"{case['caseId']} unexpectedly failed Schema: {errors}"
        assert derived_mismatch(case["guardId"], document), case["caseId"]
        print(f"semantic guard matched: {case['caseId']} -> {case['guardId']}")

    admission_schema = load(
        SCHEMA_DIR / "capability-studio-gate-a-admission-verification-result-v1.schema.json"
    )
    admission_files = sorted(
        (HERE / "../process-results").resolve().glob("valid-admission-verification-result*.json")
    )
    assert len(admission_files) == 4, f"expected exactly 4 A2 fixtures, got {len(admission_files)}"
    for path in admission_files:
        document = load(path)
        errors = list(Draft202012Validator(admission_schema, registry=registry).iter_errors(document))
        assert not errors, f"{path.name} unexpectedly failed Schema: {errors}"
        validate_a2_guard_lineage(document, catalog, authority_order, path.name)
    print(
        f"A2 Guard lineage valid: {len(admission_files)} fixtures x "
        f"{len(catalog['guards'])} Guards = {len(admission_files) * len(catalog['guards'])} evaluations"
    )

    forged_pass = copy.deepcopy(load((HERE / vectors["cases"][-1]["baseFixture"]).resolve()))
    for mutation in vectors["cases"][-1]["mutations"]:
        replace(forged_pass, mutation["path"], mutation["value"])
    errors = list(Draft202012Validator(admission_schema, registry=registry).iter_errors(forged_pass))
    assert not errors, f"A2 failed-Guard PASS attack must remain Schema-valid: {errors}"
    assert derived_mismatch("A2_CONCLUSION_PRECEDENCE", forged_pass), (
        "A2_CONCLUSION_PRECEDENCE must reject Schema-valid PASS with a failed Guard"
    )
    print("A2 conclusion precedence matched: Schema-valid PASS with failed Guard is rejected")

    print(
        "Gate A semantic guard vectors valid: "
        f"{len(vectors['terminalDerivationCases'])} derivations, "
        f"{len(vectors['cases'])} material attacks over {len(material_attack_coverage)}/{len(guard_ids)} guards, "
        f"{len(collector_contract['cases'])} normalized collector contracts, "
        f"{len(contract_coverage)}/{len(guard_ids)} reducer paths covered"
    )


if __name__ == "__main__":
    main()

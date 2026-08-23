#!/usr/bin/env python3
"""Validate Gate A process/result fixtures with Draft 2020-12."""

from __future__ import annotations

import copy
import hashlib
import json
import pathlib
import sys
from datetime import datetime
from typing import Any

from jsonschema import Draft202012Validator
from referencing import Registry, Resource

from validate_run_material import validate_all_run_material


HERE = pathlib.Path(__file__).resolve().parent
SCHEMA_ROOT = HERE.parents[3] / "schemas" / "resource-gateway-capability-studio"
NEGATIVE_EXPECTATIONS = HERE / "negative-fixture-expectations.json"
PROCESS_BINDING_VECTORS = HERE / "process-material-binding-attack-vectors.json"
LAUNCHER_CONTAMINATION_VECTORS = HERE / "process-launcher-contamination-vectors.json"


def load_json(path: pathlib.Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def load_registry() -> Registry:
    registry = Registry()
    for path in sorted(SCHEMA_ROOT.glob("capability-studio-gate-a-*.schema.json")):
        schema = load_json(path)
        Draft202012Validator.check_schema(schema)
        registry = registry.with_resource(schema["$id"], Resource.from_contents(schema))
    return registry


def validator(schema_name: str, registry: Registry) -> Draft202012Validator:
    schema = load_json(SCHEMA_ROOT / schema_name)
    return Draft202012Validator(schema, registry=registry)


def diff_paths(expected: Any, mutated: Any, path: str = "$") -> list[str]:
    if type(expected) is not type(mutated):
        return [path]
    if isinstance(expected, dict):
        paths: list[str] = []
        for key in sorted(expected.keys() | mutated.keys()):
            child = f"{path}.{key}"
            if key not in expected or key not in mutated:
                paths.append(child)
            else:
                paths.extend(diff_paths(expected[key], mutated[key], child))
        return paths
    if isinstance(expected, list):
        if len(expected) != len(mutated):
            return [path]
        paths = []
        for index, (left, right) in enumerate(zip(expected, mutated, strict=True)):
            paths.extend(diff_paths(left, right, f"{path}[{index}]"))
        return paths
    return [] if expected == mutated else [path]


def is_within(path: str, mutation_path: str) -> bool:
    return path == mutation_path or path.startswith(f"{mutation_path}.") or path.startswith(f"{mutation_path}[")


def matching_schemas(document: Any, registry: Registry) -> list[str]:
    matches: list[str] = []
    for path in sorted(SCHEMA_ROOT.glob("capability-studio-gate-a-*.schema.json")):
        if not list(validator(path.name, registry).iter_errors(document)):
            matches.append(path.name)
    return matches


def validate_positive(name: str, registry: Registry) -> list[str]:
    document = load_json(HERE / name)
    matches = matching_schemas(document, registry)
    if len(matches) != 1:
        return [f"{name}: expected exactly one matching schema, got {matches}"]
    schema_name = matches[0]
    errors = list(validator(schema_name, registry).iter_errors(document))
    failures = [f"{name}: {error.json_path} [{error.validator}] {error.message}" for error in errors]
    failures.extend(f"{name}: semantic {code}" for code in semantic_errors(name, document, schema_name))
    return failures


def parse_timestamp(value: str) -> datetime:
    return datetime.fromisoformat(value.replace("Z", "+00:00"))


def local_ref(uri: str) -> pathlib.Path | None:
    candidate = (HERE / uri).resolve()
    try:
        candidate.relative_to(HERE.resolve())
    except ValueError:
        return None
    return candidate if candidate.is_file() else None


def raw_bytes_fingerprint(path: pathlib.Path) -> str:
    return f"sha256:{hashlib.sha256(path.read_bytes()).hexdigest()}"


def json_bytes(document: Any) -> bytes:
    return (json.dumps(document, indent=2, ensure_ascii=False) + "\n").encode("utf-8")


def bytes_fingerprint(value: bytes) -> str:
    return f"sha256:{hashlib.sha256(value).hexdigest()}"


def process_semantic_errors(document: Any) -> list[str]:
    errors: list[str] = []
    if parse_timestamp(document["startedAt"]) > parse_timestamp(document["endedAt"]):
        errors.append("PROCESS_STARTED_AFTER_ENDED")
    observation = document["codeSourceObservation"]
    before = observation["preRead"]
    after = observation["postRead"]
    if before != after:
        errors.append("PROCESS_CODESOURCE_TOCTOU_DRIFT")
        return errors
    code_source = document["codeSource"]
    if (
        before["resolvedPath"] != code_source["artifactPath"]
        or before["fileKey"] != code_source["fileKey"]
        or before["fileSize"] != code_source["fileSize"]
        or before["readRawFingerprint"] != code_source["rawFingerprint"]
    ):
        errors.append("PROCESS_CODESOURCE_PROJECTION_DRIFT")
    elif before["linkCount"] != 1 or int(before["posixMode"], 8) & 0o022:
        errors.append("PROCESS_CODESOURCE_UNSAFE_FILE_IDENTITY")
    return errors


def process_observation_semantic_errors(document: Any) -> list[str]:
    executable = document["javaExecutableObservation"]
    if executable["preRead"] != executable["postRead"]:
        return ["PROCESS_EXECUTABLE_TOCTOU_DRIFT"]

    process_tree = document["processTree"]
    if (
        not process_tree["processTreeQuiescent"]
        or process_tree["descendantsAfterTermination"]
    ):
        return ["PROCESS_TREE_NOT_QUIESCENT"]

    sandbox_profile = document["sandboxProfileRawFingerprint"]
    for stream in ("stdoutCapture", "stderrCapture"):
        capture = document[stream]
        if (
            not capture["complete"]
            or capture["overflow"]
            or capture["leakScan"] != "PASS"
            or capture["quarantineStatus"] != "NOT_REQUIRED"
            or capture["observedBytes"] > capture["limitBytes"]
            or capture["scannerProfileRawFingerprint"] != sandbox_profile
        ):
            return ["PROCESS_OUTPUT_CAPTURE_INCOMPLETE"]

    classpath = {
        entry["role"]: entry["rawFingerprint"]
        for entry in document["effectiveClasspath"]
    }
    expected_origin = {
        "CANDIDATE_CHALLENGE_CLI": "CANDIDATE",
        "CANDIDATE_SPI": "CANDIDATE",
        "TCK_PROVIDER": "TCK_PROVIDER",
        "A1_MAIN": "APPLICATION",
        "HARNESS_MAIN": "APPLICATION",
        "ADMISSION_MAIN": "APPLICATION",
    }
    for origin in document["admittedClassOrigins"]:
        expected = classpath.get(expected_origin[origin["role"]])
        if expected is None or origin["codeSourceRawFingerprint"] != expected:
            return ["PROCESS_CLASS_ORIGIN_DRIFT"]
    return []


def process_material_binding_errors(
    transcript: Any,
    observation: Any,
    sandbox_profile_raw_fingerprint: str,
    expected_launch_kind: str,
    observation_bytes: bytes,
) -> list[str]:
    run_id = transcript["runId"]
    if observation["runId"] != run_id:
        return ["PROCESS_OBSERVATION_RUN_ID_DRIFT"]
    expected_uri = f"run-material/runs/{run_id}/process-observation.json"
    if transcript["processObservationRef"]["uri"] != expected_uri:
        return ["PROCESS_OBSERVATION_REF_ROOT_DRIFT"]
    observed_raw = transcript["processObservationRef"]["rawFingerprint"]["value"]
    if observed_raw != bytes_fingerprint(observation_bytes):
        return ["PROCESS_OBSERVATION_RAW_FINGERPRINT_DRIFT"]
    if observation["launchKind"] != expected_launch_kind:
        return ["PROCESS_LAUNCH_KIND_DRIFT"]
    if observation["sandboxProfileRawFingerprint"]["value"] != sandbox_profile_raw_fingerprint:
        return ["PROCESS_SANDBOX_PROFILE_DRIFT"]
    if any(
        observation[stream]["scannerProfileRawFingerprint"]["value"]
        != sandbox_profile_raw_fingerprint
        for stream in ("stdoutCapture", "stderrCapture")
    ):
        return ["PROCESS_SANDBOX_PROFILE_DRIFT"]
    classpath_role = "CANDIDATE" if expected_launch_kind == "CANDIDATE_CHILD" else "APPLICATION"
    classpath_entry = next(
        (entry for entry in observation["effectiveClasspath"] if entry["role"] == classpath_role),
        None,
    )
    code_source = transcript["codeSource"]
    if classpath_entry is None or (
        code_source["artifactPath"] != classpath_entry["artifactPath"]
        or code_source["rawFingerprint"] != classpath_entry["rawFingerprint"]
    ):
        return ["PROCESS_CODESOURCE_CLASSPATH_DRIFT"]
    return []


def run_reference_closure_errors(bindings: list[dict]) -> list[str]:
    transcript_uris = [binding["transcriptRef"]["uri"] for binding in bindings]
    if len(transcript_uris) != len(set(transcript_uris)):
        return ["PROCESS_TRANSCRIPT_REF_REUSED"]
    seen_uris: set[str] = set()
    for binding in bindings:
        root = f"run-material/runs/{binding['runId']}/"
        for field in ("requestRef", "responseRef", "transcriptRef", "observationRef"):
            uri = binding[field]["uri"]
            if not uri.startswith(root):
                return ["PROCESS_RUN_REF_ROOT_DRIFT"]
            if uri in seen_uris:
                return ["PROCESS_MATERIAL_REF_REUSED"]
            seen_uris.add(uri)
    return []


def challenge_pair_closure_errors(pairs: list[dict]) -> list[str]:
    for pair in pairs:
        if pair["requestChallengeId"] != pair["responseChallengeId"]:
            return ["CHALLENGE_PAIR_ID_DRIFT"]
    challenge_ids = [pair["requestChallengeId"] for pair in pairs]
    if len(challenge_ids) != len(set(challenge_ids)):
        return ["CHALLENGE_ID_REUSED_WITHIN_RUN"]
    return []


def launcher_contract_errors(observation: Any) -> list[str]:
    if observation["environmentNames"]:
        return ["LAUNCHER_ENVIRONMENT_NOT_EMPTY"]
    if observation["jvmInputArguments"]:
        return ["LAUNCHER_JVM_ARGUMENTS_NOT_EMPTY"]
    expected_count = 2 if observation["launchKind"] == "CANDIDATE_CHILD" else 1
    if len(observation["effectiveClasspath"]) != expected_count:
        return ["LAUNCHER_CLASSPATH_DRIFT"]
    classpath = {
        entry["role"]: entry["rawFingerprint"]
        for entry in observation["effectiveClasspath"]
    }
    role_to_classpath = {
        "CANDIDATE_CHALLENGE_CLI": "CANDIDATE",
        "CANDIDATE_SPI": "CANDIDATE",
        "TCK_PROVIDER": "TCK_PROVIDER",
        "A1_MAIN": "APPLICATION",
        "HARNESS_MAIN": "APPLICATION",
        "ADMISSION_MAIN": "APPLICATION",
    }
    if any(
        origin["codeSourceRawFingerprint"]
        != classpath.get(role_to_classpath[origin["role"]])
        for origin in observation["admittedClassOrigins"]
    ):
        return ["LAUNCHER_ADMITTED_CLASS_ORIGIN_DRIFT"]
    return []


def validate_launcher_contamination_vectors() -> list[str]:
    failures: list[str] = []
    vectors = load_json(LAUNCHER_CONTAMINATION_VECTORS)
    base_names = (
        "valid-process-observation.json",
        "valid-process-observation-a1.json",
        "valid-process-observation-harness.json",
        "valid-process-observation-a2.json",
    )
    for base_name in base_names:
        base = load_json(HERE / base_name)
        launch_kind = base["launchKind"]
        if launcher_contract_errors(base):
            failures.append(f"{base_name}: valid launcher observation violates launcher contract")
        for vector in vectors["vectors"]:
            mutated = copy.deepcopy(base)
            target = vector["target"]
            if target in {"environmentNames", "jvmInputArguments"}:
                mutated[target] = [vector["value"]]
            elif target == "effectiveClasspath":
                extra = copy.deepcopy(mutated["effectiveClasspath"][-1])
                extra["ordinal"] = 3
                extra["artifactPath"] = "/opt/injected.jar"
                extra["rawFingerprint"]["value"] = f"sha256:{'9' * 64}"
                mutated[target].append(extra)
            elif target == "applicationCodeSource":
                mutated["effectiveClasspath"][-1]["rawFingerprint"]["value"] = f"sha256:{'8' * 64}"
            elif target == "admittedClassOrigin":
                mutated["admittedClassOrigins"][0]["codeSourceRawFingerprint"]["value"] = f"sha256:{'7' * 64}"
            else:
                failures.append(f"unknown launcher mutation target: {target}")
                continue
            observed = launcher_contract_errors(mutated)
            expected = [vector["expectedSemanticCode"]]
            if observed != expected:
                failures.append(
                    f"{launch_kind}/{vector['vectorId']}: expected {expected}, got {observed}"
                )
            else:
                print(
                    f"launcher contamination matched: {launch_kind}/{vector['vectorId']} -> {expected[0]}"
                )
    return failures


def validate_process_material_binding_vectors() -> list[str]:
    failures: list[str] = []
    transcript = load_json(HERE / "valid-process-transcript.json")
    observation = load_json(HERE / "valid-process-observation.json")
    observation_bytes = (HERE / "valid-process-observation.json").read_bytes()
    sandbox_profile_raw = raw_bytes_fingerprint(HERE / "valid-challenge-sandbox-profile.json")
    challenge_pin = load_json(HERE / ".." / "trust-build" / "valid-challenge-trust-pin.json")
    pinned_sandbox_profile_raw = challenge_pin[
        "expectedChallengeSandboxProfileRawFingerprint"
    ]["value"]
    if pinned_sandbox_profile_raw != sandbox_profile_raw:
        failures.append("Challenge Pin sandbox profile raw binding drift")
    observed = process_material_binding_errors(
        transcript,
        observation,
        pinned_sandbox_profile_raw,
        "CANDIDATE_CHILD",
        observation_bytes,
    )
    if observed:
        failures.append(f"valid process material binding: {observed}")

    def raw_ref(uri: str) -> dict:
        return {
            "uri": uri,
            "rawFingerprint": {
                "kind": "RAW_BYTES",
                "algorithm": "SHA-256",
                "value": f"sha256:{'1' * 64}",
            },
        }

    bindings = []
    for run_id in ("RUN-A1-OUTER-001", "RUN-A1-CHILD-001", "RUN-A1-CHILD-002"):
        root = f"run-material/runs/{run_id}"
        bindings.append({
            "runId": run_id,
            "requestRef": raw_ref(f"{root}/request.json"),
            "responseRef": raw_ref(f"{root}/response.json"),
            "transcriptRef": raw_ref(f"{root}/transcript.json"),
            "observationRef": raw_ref(f"{root}/process-observation.json"),
        })
    if run_reference_closure_errors(bindings):
        failures.append("valid run reference closure is not closed")
    challenge_pairs = [
        {
            "requestChallengeId": f"CHALLENGE-RUN-A1-{ordinal:02d}",
            "responseChallengeId": f"CHALLENGE-RUN-A1-{ordinal:02d}",
        }
        for ordinal in range(1, 10)
    ]
    if challenge_pair_closure_errors(challenge_pairs):
        failures.append("valid challenge pair closure is not closed")

    vectors = load_json(PROCESS_BINDING_VECTORS)
    for vector in vectors["vectors"]:
        mutated_transcript = copy.deepcopy(transcript)
        mutated_observation = copy.deepcopy(observation)
        mutated_observation_bytes = observation_bytes
        expected_launch_kind = "CANDIDATE_CHILD"
        mutated_bindings = copy.deepcopy(bindings)
        mutated_challenge_pairs = copy.deepcopy(challenge_pairs)
        mutation = vector["mutation"]
        if mutation == "OBSERVATION_RUN_ID":
            mutated_observation["runId"] = "RUN-CROSS-002"
            mutated_observation_bytes = json_bytes(mutated_observation)
            mutated_transcript["processObservationRef"]["rawFingerprint"]["value"] = bytes_fingerprint(mutated_observation_bytes)
        elif mutation == "OBSERVATION_RAW_FINGERPRINT":
            mutated_transcript["processObservationRef"]["rawFingerprint"]["value"] = f"sha256:{'0' * 64}"
        elif mutation == "SANDBOX_PROFILE_RAW_FINGERPRINT":
            drift = f"sha256:{'0' * 64}"
            mutated_observation["sandboxProfileRawFingerprint"]["value"] = drift
            for stream in ("stdoutCapture", "stderrCapture"):
                mutated_observation[stream]["scannerProfileRawFingerprint"]["value"] = drift
            mutated_observation_bytes = json_bytes(mutated_observation)
            mutated_transcript["processObservationRef"]["rawFingerprint"]["value"] = bytes_fingerprint(mutated_observation_bytes)
        elif mutation == "CALLER_EXPECTED_LAUNCH_KIND":
            expected_launch_kind = "A1_VERIFIER"
        elif mutation == "TRANSCRIPT_CODESOURCE":
            drift = f"sha256:{'0' * 64}"
            mutated_transcript["codeSource"]["rawFingerprint"]["value"] = drift
            for phase in ("preRead", "postRead"):
                mutated_transcript["codeSourceObservation"][phase]["readRawFingerprint"]["value"] = drift
        elif mutation == "TRANSCRIPT_REF_REUSE":
            mutated_bindings[0]["transcriptRef"] = copy.deepcopy(mutated_bindings[1]["transcriptRef"])
        elif mutation == "REQUEST_REF_CROSS_RUN":
            mutated_bindings[1]["requestRef"] = copy.deepcopy(mutated_bindings[2]["requestRef"])
        elif mutation == "CHALLENGE_PAIR_ID_DRIFT":
            mutated_challenge_pairs[0]["responseChallengeId"] = "CHALLENGE-RUN-A1-OTHER"
        elif mutation == "CHALLENGE_ID_REUSE":
            mutated_challenge_pairs[1] = copy.deepcopy(mutated_challenge_pairs[0])
        else:
            failures.append(f"unknown process material binding mutation: {mutation}")
            continue

        if mutation in {"TRANSCRIPT_REF_REUSE", "REQUEST_REF_CROSS_RUN"}:
            observed = run_reference_closure_errors(mutated_bindings)
        elif mutation in {"CHALLENGE_PAIR_ID_DRIFT", "CHALLENGE_ID_REUSE"}:
            observed = challenge_pair_closure_errors(mutated_challenge_pairs)
        else:
            observed = process_material_binding_errors(
                mutated_transcript,
                mutated_observation,
                pinned_sandbox_profile_raw,
                expected_launch_kind,
                mutated_observation_bytes,
            )
        expected = [vector["expectedSemanticCode"]]
        if observed != expected:
            failures.append(f"{vector['vectorId']}: expected {expected}, got {observed}")
        else:
            print(f"material binding attack matched: {vector['vectorId']} -> {expected[0]}")
    return failures


def admission_result_semantic_errors(document: Any) -> list[str]:
    statuses = [
        entry["status"]
        for field in (
            "requirements",
            "artifacts",
            "tests",
            "mandatoryGuards",
            "semanticGuardResults",
        )
        for entry in document[field]
    ]
    statuses.append(document["trustedReview"]["status"])
    if "UNAVAILABLE" in statuses:
        derived = "UNAVAILABLE"
    elif "FAIL" in statuses:
        derived = "FAIL"
    elif "MISSING" in statuses:
        derived = "OPEN"
    else:
        derived = "PASS"
    return [] if document["conclusion"] == derived else ["A2_CONCLUSION_PRECEDENCE"]


def independent_result_semantic_errors(document: Any) -> list[str]:
    special_outer_index = {
        "VERIFIER_DIGEST_MUTATION_REJECTED": 1,
        "REGISTRY_MUTATION_REJECTED": 2,
        "VERIFIER_TCK_MISMATCH_REJECTED": 3,
    }
    outer = [run["processObservationRawFingerprint"]["value"] for run in document["verificationProcessRuns"]]
    normal = [
        run["processObservationRawFingerprint"]["value"]
        for run in document["testRuns"]
        if run["testId"] not in special_outer_index
    ]
    if len(outer) != len(set(outer)) or len(normal) != len(set(normal)) or not set(outer).isdisjoint(normal):
        return ["A1_PROCESS_OBSERVATION_FINGERPRINT_REUSED"]
    for test_id, outer_index in special_outer_index.items():
        slot = next(run for run in document["testRuns"] if run["testId"] == test_id)
        if slot["processObservationRawFingerprint"] != document["verificationProcessRuns"][outer_index]["processObservationRawFingerprint"]:
            return ["A1_SPECIAL_OBSERVATION_DRIFT"]
    return []


def provider_materialization_semantic_errors(document: Any) -> list[str]:
    create_identity = document["destinationCreateReceipt"]["identity"]
    read_receipt = document["destinationOpenReadReceipt"]
    if not (create_identity == read_receipt["preRead"] == read_receipt["postRead"]):
        return ["A1_PROVIDER_DESTINATION_IDENTITY_DRIFT"]
    return []


def semantic_errors(name: str, document: Any, schema_name: str) -> list[str]:
    errors: list[str] = []
    if schema_name in {
        "capability-studio-gate-a-process-transcript-v1.schema.json",
        "capability-studio-gate-a-harness-process-transcript-v1.schema.json",
    }:
        errors.extend(process_semantic_errors(document))
    if schema_name == "capability-studio-gate-a-process-observation-v1.schema.json":
        errors.extend(process_observation_semantic_errors(document))
    if schema_name == "capability-studio-gate-a-admission-verification-result-v1.schema.json":
        errors.extend(admission_result_semantic_errors(document))
    if schema_name == "capability-studio-gate-a-independent-verification-result-v1.schema.json":
        errors.extend(independent_result_semantic_errors(document))
    if schema_name == "capability-studio-gate-a-provider-materialization-observation-v1.schema.json":
        errors.extend(provider_materialization_semantic_errors(document))
    if schema_name == "capability-studio-gate-a-replay-proof-envelope-v1.schema.json":
        result_path = local_ref(document["replayResultRef"]["uri"])
        transcript_path = local_ref(document["producerProcessTranscriptRef"]["uri"])
        if result_path is None or transcript_path is None:
            errors.append("A1_REFERENCED_MATERIAL_UNAVAILABLE")
            return errors
        result = load_json(result_path)
        transcript = load_json(transcript_path)
        if document["replayResultRef"]["rawFingerprint"]["value"] != raw_bytes_fingerprint(result_path):
            errors.append("A1_RESULT_RAW_FINGERPRINT_MISMATCH")
        if document["producerProcessTranscriptRef"]["rawFingerprint"]["value"] != raw_bytes_fingerprint(transcript_path):
            errors.append("A1_PROCESS_TRANSCRIPT_RAW_FINGERPRINT_MISMATCH")
        if transcript.get("processState") != "COMPLETED":
            errors.append("A1_PRODUCER_PROCESS_NOT_COMPLETED")
            return errors
        expected_exit = {"VERIFIED": 0, "INVALID": 2, "UNAVAILABLE": 3}.get(result.get("terminal"))
        if transcript.get("exitCode") != expected_exit:
            errors.append("A1_PROCESS_OUTCOME_DRIFT")
        if document["observedProcessState"] != transcript.get("processState") or document["observedExitCode"] != transcript.get("exitCode"):
            errors.append("A1_PROCESS_OUTCOME_DRIFT")
        if document["observedTerminal"] != result.get("terminal"):
            errors.append("A1_RESULT_OUTCOME_DRIFT")
    if schema_name == "capability-studio-gate-a-independent-proof-envelope-v1.schema.json":
        result_path = local_ref(document["independentResultRef"]["uri"])
        transcript_path = local_ref(document["harnessProcessTranscriptRef"]["uri"])
        if result_path is None or transcript_path is None:
            errors.append("A1_INDEPENDENT_REFERENCED_MATERIAL_UNAVAILABLE")
            return errors
        transcript = load_json(transcript_path)
        stdout_path = local_ref(transcript["stdoutRef"]["uri"])
        if stdout_path is None:
            errors.append("A1_INDEPENDENT_REFERENCED_MATERIAL_UNAVAILABLE")
            return errors
        if transcript.get("processState") != "COMPLETED" or transcript.get("exitCode") != 0:
            errors.append("A1_HARNESS_PROCESS_OUTCOME_DRIFT")
            return errors
        if stdout_path.read_bytes() != result_path.read_bytes() + b"\n":
            errors.append("A1_HARNESS_STDOUT_REPORT_DRIFT")
    if schema_name == "capability-studio-gate-a-admission-proof-envelope-v1.schema.json":
        result_path = local_ref(document["admissionResultRef"]["uri"])
        transcript_path = local_ref(document["admissionProcessTranscriptRef"]["uri"])
        if result_path is None or transcript_path is None:
            errors.append("A2_REFERENCED_MATERIAL_UNAVAILABLE")
            return errors
        result = load_json(result_path)
        transcript = load_json(transcript_path)
        if (
            document["admissionResultRef"]["rawFingerprint"]["value"]
            != raw_bytes_fingerprint(result_path)
        ):
            errors.append("A2_RESULT_RAW_FINGERPRINT_MISMATCH")
        if (
            document["admissionProcessTranscriptRef"]["rawFingerprint"]["value"]
            != raw_bytes_fingerprint(transcript_path)
        ):
            errors.append("A2_PROCESS_TRANSCRIPT_RAW_FINGERPRINT_MISMATCH")
        if result.get("messageVersion") != "resource-gateway.capability-studio.gate-a.admission-verification-result.v1":
            errors.append("A2_RESULT_KIND_MISMATCH")
        if transcript.get("messageVersion") != "resource-gateway.capability-studio.gate-a.process-transcript.v1":
            errors.append("A2_PROCESS_TRANSCRIPT_KIND_MISMATCH")
        if transcript.get("processState") != "COMPLETED":
            errors.append("A2_PROCESS_NOT_COMPLETED")
            return errors
        if parse_timestamp(transcript["startedAt"]) > parse_timestamp(transcript["endedAt"]):
            errors.append("A2_PROCESS_STARTED_AFTER_ENDED")
        if parse_timestamp(transcript["endedAt"]) > parse_timestamp(document["createdAt"]):
            errors.append("A2_CREATED_BEFORE_PROCESS_ENDED")
        expected_exit = {"PASS": 0, "OPEN": 4, "FAIL": 2, "UNAVAILABLE": 3}.get(result.get("conclusion"))
        if transcript.get("exitCode") != expected_exit:
            errors.append("A2_PROCESS_OUTCOME_DRIFT")
            return errors
        if document["observedProcessState"] != transcript.get("processState") or document["observedExitCode"] != transcript.get("exitCode"):
            errors.append("A2_PROCESS_OUTCOME_DRIFT")
        if document["admissionResultConclusion"] != result.get("conclusion") or document["observedConclusion"] != result.get("conclusion"):
            errors.append("A2_CONCLUSION_DRIFT")
        if document["expectedAdmissionCodeSource"] != document["observedAdmissionCodeSource"]:
            errors.append("A2_EXPECTED_OBSERVED_CODESOURCE_DRIFT")
        if document["observedAdmissionCodeSource"] != transcript.get("codeSource"):
            errors.append("A2_CODESOURCE_TRANSCRIPT_DRIFT")
    return errors


def validate_negative(name: str, expectation: dict[str, str], registry: Registry) -> list[str]:
    failures: list[str] = []
    base = load_json(HERE / expectation["base"])
    mutated = load_json(HERE / name)
    changes = diff_paths(base, mutated)
    mutation_path = expectation["mutationPath"]
    allowed_mutation_paths = [mutation_path, *expectation.get("derivedMutationPaths", [])]
    if not changes:
        failures.append(f"{name}: is identical to {expectation['base']}")
    elif any(not any(is_within(path, allowed) for allowed in allowed_mutation_paths) for path in changes):
        failures.append(f"{name}: changes {changes} escape declared mutation {mutation_path}")

    base_errors = list(validator(expectation["schema"], registry).iter_errors(base))
    if base_errors:
        failures.append(f"{name}: base fixture {expectation['base']} is not valid")

    errors = list(validator(expectation["schema"], registry).iter_errors(mutated))
    if expectation.get("validationMode") == "semantic":
        if errors:
            rendered = [f"{error.json_path} [{error.validator}] {error.message}" for error in errors]
            failures.append(f"{name}: semantic negative must remain Schema-valid: {rendered}")
        observed = semantic_errors(name, mutated, expectation["schema"])
        expected = expectation["expectedSemanticCode"]
        if observed != [expected]:
            failures.append(f"{name}: expected semantic [{expected}], got {observed}")
        else:
            print(f"negative matched: {name} -> semantic {expected}")
        return failures
    if len(errors) != 1:
        rendered = [f"{error.json_path} [{error.validator}] {error.message}" for error in errors]
        failures.append(f"{name}: expected exactly one validation error, got {len(errors)}: {rendered}")
        return failures

    error = errors[0]
    actual_path = str(error.json_path)
    if error.validator != expectation["expectedKeyword"]:
        failures.append(f"{name}: expected keyword {expectation['expectedKeyword']}, got {error.validator}")
    if actual_path != expectation["expectedPath"]:
        failures.append(f"{name}: expected path {expectation['expectedPath']}, got {actual_path}")
    if expectation["expectedMessageContains"] not in error.message:
        failures.append(
            f"{name}: error message does not contain {expectation['expectedMessageContains']!r}: {error.message}"
        )
    if not failures:
        print(f"negative matched: {name} -> {error.validator} {actual_path}")
    return failures


def main() -> int:
    registry = load_registry()
    expectations = load_json(NEGATIVE_EXPECTATIONS)
    failures: list[str] = []

    positive_names = sorted(path.name for path in HERE.glob("valid-*.json"))
    negative_names = {path.name for path in HERE.glob("invalid-*.json")}
    if negative_names != expectations.keys():
        failures.append(
            f"negative mapping drift: files={sorted(negative_names)} mapping={sorted(expectations)}"
        )

    for name in positive_names:
        failures.extend(validate_positive(name, registry))
    for name, expectation in expectations.items():
        failures.extend(validate_negative(name, expectation, registry))
    failures.extend(validate_process_material_binding_vectors())
    failures.extend(validate_launcher_contamination_vectors())
    failures.extend(validate_all_run_material())

    if failures:
        print("Gate A fixture validation failed:", file=sys.stderr)
        print("\n".join(failures), file=sys.stderr)
        return 1
    print(f"Gate A fixtures valid: {len(positive_names)} positive, {len(expectations)} negative")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

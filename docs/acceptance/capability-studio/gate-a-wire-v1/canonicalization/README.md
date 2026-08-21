# Gate A canonicalization reference

This directory is the non-Java reference for `Protocol Canonicalization v1`.
It intentionally uses only Node.js standard-library APIs so the Java implementation
cannot share parser or fingerprint code with its reference oracle.

Run:

```bash
node reference-fingerprint.mjs verify-profile
node reference-fingerprint.mjs verify-vectors
```

`fingerprint-profile-v1.json` is the Gate A fingerprint parameter authority. Its
Draft 2020-12 Schema freezes all 44 entries, their array order, and every
`objectKind`/`messageVersion` or `schemaVersion`/`domain`/`selfField`/
`fingerprintKind` combination with positional `const` values. It covers 33
canonical documents, three tree commitments, and eight aggregate commitments.
Tree and aggregate profiles have no `selfField`; canonical operation-result
profiles use `selfField: null` because their fingerprint is stored by the
containing response.

Production and Java implementations MUST select the profile by the protocol's
expected `objectKind`, then verify the document's `messageVersion` or
`schemaVersion` when that identity field exists. Their fingerprint API MUST NOT
accept caller-selected `domain`, `selfField`, or `fingerprintKind` values. A
caller-provided value is only a claim to compare with the selected profile, not
an input to fingerprint calculation.

The reference parser rejects duplicate keys, a UTF-8 BOM, lone surrogates,
non-finite numbers and unsafe JSON integers before canonicalization. Object keys
are ordered by UTF-16 code units, matching RFC 8785. `JSON.stringify` supplies
ECMAScript string and finite-number serialization.

`expectedRawFingerprint` covers the exact `sourceText` UTF-8 bytes.
`expectedDocumentFingerprint` covers:

```text
ASCII(domain) || 0x00 || UTF8(JCS(document with selfField=null))
```

The Java Gate A implementation must consume these vectors unchanged. Passing a
Java-only unit test is not sufficient for Design Gate D0 or Gate A.

The vector verifier validates the fingerprint profile before running the
original 11 canonicalization/parser vectors. It also proves that one wrong-domain
claim and one wrong-self-field claim are rejected by profile lookup. The free
form command below remains available only for byte-level diagnosis and has no
Gate A Authority:

```bash
node reference-fingerprint.mjs fingerprint <domain> [selfField|-] < document.json
```

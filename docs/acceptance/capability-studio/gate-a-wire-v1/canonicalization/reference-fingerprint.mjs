#!/usr/bin/env node

import { createHash } from "node:crypto";
import { readFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

class StrictJsonParser {
  constructor(source) {
    this.source = source;
    this.index = 0;
  }

  parse() {
    this.skipWhitespace();
    const value = this.parseValue();
    this.skipWhitespace();
    if (this.index !== this.source.length) {
      this.fail("TRAILING_CONTENT");
    }
    return value;
  }

  parseValue() {
    const token = this.source[this.index];
    if (token === "{") return this.parseObject();
    if (token === "[") return this.parseArray();
    if (token === '"') return this.parseString();
    if (token === "t") return this.parseLiteral("true", true);
    if (token === "f") return this.parseLiteral("false", false);
    if (token === "n") return this.parseLiteral("null", null);
    if (token === "-" || (token >= "0" && token <= "9")) return this.parseNumber();
    this.fail("INVALID_TOKEN");
  }

  parseObject() {
    const value = Object.create(null);
    const keys = new Set();
    this.index++;
    this.skipWhitespace();
    if (this.source[this.index] === "}") {
      this.index++;
      return value;
    }
    while (true) {
      if (this.source[this.index] !== '"') this.fail("OBJECT_KEY_REQUIRED");
      const key = this.parseString();
      if (keys.has(key)) this.fail("DUPLICATE_KEY");
      keys.add(key);
      this.skipWhitespace();
      if (this.source[this.index++] !== ":") this.fail("COLON_REQUIRED");
      this.skipWhitespace();
      value[key] = this.parseValue();
      this.skipWhitespace();
      const separator = this.source[this.index++];
      if (separator === "}") return value;
      if (separator !== ",") this.fail("OBJECT_SEPARATOR_REQUIRED");
      this.skipWhitespace();
    }
  }

  parseArray() {
    const value = [];
    this.index++;
    this.skipWhitespace();
    if (this.source[this.index] === "]") {
      this.index++;
      return value;
    }
    while (true) {
      value.push(this.parseValue());
      this.skipWhitespace();
      const separator = this.source[this.index++];
      if (separator === "]") return value;
      if (separator !== ",") this.fail("ARRAY_SEPARATOR_REQUIRED");
      this.skipWhitespace();
    }
  }

  parseString() {
    const start = this.index++;
    let escaped = false;
    while (this.index < this.source.length) {
      const code = this.source.charCodeAt(this.index);
      if (!escaped && code === 0x22) {
        this.index++;
        let parsed;
        try {
          parsed = JSON.parse(this.source.slice(start, this.index));
        } catch {
          this.fail("INVALID_STRING_ESCAPE");
        }
        rejectLoneSurrogates(parsed);
        return parsed;
      }
      if (!escaped && code < 0x20) this.fail("UNESCAPED_CONTROL_CHARACTER");
      if (!escaped && code === 0x5c) {
        escaped = true;
        this.index++;
        continue;
      }
      escaped = false;
      this.index++;
    }
    this.fail("UNTERMINATED_STRING");
  }

  parseNumber() {
    const remainder = this.source.slice(this.index);
    const match = /^-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?/.exec(remainder);
    if (!match) this.fail("INVALID_NUMBER");
    this.index += match[0].length;
    const value = Number(match[0]);
    if (!Number.isFinite(value)) this.fail("NON_FINITE_NUMBER");
    if (Number.isInteger(value) && !Number.isSafeInteger(value)) this.fail("UNSAFE_INTEGER");
    return value;
  }

  parseLiteral(literal, value) {
    if (!this.source.startsWith(literal, this.index)) this.fail("INVALID_LITERAL");
    this.index += literal.length;
    return value;
  }

  skipWhitespace() {
    while (/[\x20\x09\x0a\x0d]/.test(this.source[this.index] ?? "")) this.index++;
  }

  fail(reason) {
    const error = new Error(`${reason} at offset ${this.index}`);
    error.reason = reason;
    throw error;
  }
}

function rejectLoneSurrogates(value) {
  for (let index = 0; index < value.length; index++) {
    const code = value.charCodeAt(index);
    if (code >= 0xd800 && code <= 0xdbff) {
      const next = value.charCodeAt(index + 1);
      if (!(next >= 0xdc00 && next <= 0xdfff)) throw reasonError("LONE_SURROGATE");
      index++;
    } else if (code >= 0xdc00 && code <= 0xdfff) {
      throw reasonError("LONE_SURROGATE");
    }
  }
}

function reasonError(reason) {
  const error = new Error(reason);
  error.reason = reason;
  return error;
}

function parseStrictJson(sourceText) {
  if (sourceText.charCodeAt(0) === 0xfeff) throw reasonError("UTF8_BOM_REJECTED");
  return new StrictJsonParser(sourceText).parse();
}

function decodeStrictUtf8(bytes) {
  if (bytes.length >= 3 && bytes[0] === 0xef && bytes[1] === 0xbb && bytes[2] === 0xbf) {
    throw reasonError("UTF8_BOM_REJECTED");
  }
  try {
    return new TextDecoder("utf-8", {fatal: true}).decode(bytes);
  } catch {
    throw reasonError("INVALID_UTF8");
  }
}

function canonicalize(value) {
  if (value === null || typeof value === "boolean") return JSON.stringify(value);
  if (typeof value === "number") {
    if (!Number.isFinite(value)) throw reasonError("NON_FINITE_NUMBER");
    if (Number.isInteger(value) && !Number.isSafeInteger(value)) throw reasonError("UNSAFE_INTEGER");
    return JSON.stringify(value);
  }
  if (typeof value === "string") {
    rejectLoneSurrogates(value);
    return JSON.stringify(value);
  }
  if (Array.isArray(value)) return `[${value.map(canonicalize).join(",")}]`;
  if (typeof value === "object") {
    return `{${Object.keys(value).sort().map((key) => {
      rejectLoneSurrogates(key);
      return `${JSON.stringify(key)}:${canonicalize(value[key])}`;
    }).join(",")}}`;
  }
  throw reasonError("UNSUPPORTED_JSON_VALUE");
}

function rawSha256(bytes) {
  return `sha256:${createHash("sha256").update(bytes).digest("hex")}`;
}

function documentFingerprint(domain, value, selfField) {
  if (!/^[\x21-\x7e]+$/.test(domain)) throw reasonError("INVALID_ASCII_DOMAIN");
  const copy = structuredClone(value);
  if (selfField !== null) {
    if (!(selfField in copy)) throw reasonError("SELF_FIELD_MISSING");
    copy[selfField] = null;
  }
  const canonical = canonicalize(copy);
  const bytes = Buffer.concat([
    Buffer.from(domain, "ascii"),
    Buffer.from([0]),
    Buffer.from(canonical, "utf8")
  ]);
  return { canonical, fingerprint: rawSha256(bytes) };
}

function validateFingerprintProfile(profile, schema) {
  const expectedVersion = schema?.properties?.schemaVersion?.const;
  const expectedProfiles = schema?.properties?.profiles?.prefixItems?.map((item) => item.const);
  if (typeof expectedVersion !== "string" || !Array.isArray(expectedProfiles)
      || schema.properties.profiles.items !== false
      || schema.properties.profiles.minItems !== expectedProfiles.length
      || schema.properties.profiles.maxItems !== expectedProfiles.length) {
    throw reasonError("FINGERPRINT_PROFILE_SCHEMA_INVALID");
  }
  if (profile === null || typeof profile !== "object" || Array.isArray(profile)
      || Object.keys(profile).sort().join(",") !== "profiles,schemaVersion"
      || profile.schemaVersion !== expectedVersion
      || !Array.isArray(profile.profiles)
      || profile.profiles.length !== expectedProfiles.length) {
    throw reasonError("FINGERPRINT_PROFILE_INVALID");
  }

  const objectKinds = new Set();
  for (let index = 0; index < expectedProfiles.length; index++) {
    const expected = expectedProfiles[index];
    const actual = profile.profiles[index];
    if (canonicalize(actual) !== canonicalize(expected)) {
      throw reasonError("FINGERPRINT_PROFILE_ENTRY_MISMATCH");
    }
    if (objectKinds.has(actual.objectKind)) throw reasonError("FINGERPRINT_PROFILE_OBJECT_KIND_DUPLICATE");
    objectKinds.add(actual.objectKind);
    if (!/^[\x21-\x7e]+$/.test(actual.domain)) throw reasonError("INVALID_ASCII_DOMAIN");
    if (actual.fingerprintKind === "CANONICAL_DOCUMENT") {
      if (!("selfField" in actual)) throw reasonError("FINGERPRINT_PROFILE_SELF_FIELD_REQUIRED");
    } else if ((actual.fingerprintKind === "TREE_COMMITMENT"
        || actual.fingerprintKind === "AGGREGATE_COMMITMENT") && "selfField" in actual) {
      throw reasonError("FINGERPRINT_PROFILE_SELF_FIELD_FORBIDDEN");
    } else if (actual.fingerprintKind !== "TREE_COMMITMENT"
        && actual.fingerprintKind !== "AGGREGATE_COMMITMENT") {
      throw reasonError("FINGERPRINT_PROFILE_KIND_INVALID");
    }
    if ("messageVersion" in actual && "schemaVersion" in actual) {
      throw reasonError("FINGERPRINT_PROFILE_IDENTITY_AMBIGUOUS");
    }
  }
  return profile;
}

function fingerprintProfileEntry(profile, objectKind, value) {
  const entry = profile.profiles.find((candidate) => candidate.objectKind === objectKind);
  if (entry === undefined) throw reasonError("FINGERPRINT_PROFILE_OBJECT_KIND_UNKNOWN");
  for (const versionField of ["messageVersion", "schemaVersion"]) {
    if (versionField in entry
        && (value === null || typeof value !== "object" || Array.isArray(value)
          || value[versionField] !== entry[versionField])) {
      throw reasonError("FINGERPRINT_PROFILE_IDENTITY_MISMATCH");
    }
  }
  return entry;
}

function fingerprintByProfile(profile, objectKind, value) {
  const entry = fingerprintProfileEntry(profile, objectKind, value);
  const selfField = "selfField" in entry ? entry.selfField : null;
  return {
    ...documentFingerprint(entry.domain, value, selfField),
    fingerprintKind: entry.fingerprintKind
  };
}

function assertProfileParameters(profile, objectKind, value, domain, selfField, fingerprintKind) {
  const entry = fingerprintProfileEntry(profile, objectKind, value);
  if (domain !== entry.domain) throw reasonError("FINGERPRINT_PROFILE_DOMAIN_MISMATCH");
  const expectedSelfField = "selfField" in entry ? entry.selfField : null;
  if (selfField !== expectedSelfField) throw reasonError("FINGERPRINT_PROFILE_SELF_FIELD_MISMATCH");
  if (fingerprintKind !== entry.fingerprintKind) throw reasonError("FINGERPRINT_PROFILE_KIND_MISMATCH");
  return fingerprintByProfile(profile, objectKind, value);
}

function readStrictJson(path) {
  return parseStrictJson(decodeStrictUtf8(readFileSync(path)));
}

function loadFingerprintProfile(profilePath, schemaPath) {
  return validateFingerprintProfile(readStrictJson(profilePath), readStrictJson(schemaPath));
}

export {
  canonicalize,
  decodeStrictUtf8,
  documentFingerprint,
  fingerprintByProfile,
  loadFingerprintProfile,
  parseStrictJson,
  rawSha256,
  validateFingerprintProfile
};

function verifyVectors(path) {
  const manifest = readStrictJson(path);
  const base = dirname(resolve(path));
  const profilePath = manifest.fingerprintProfile === undefined
    ? fileURLToPath(new URL("./fingerprint-profile-v1.json", import.meta.url))
    : resolve(base, manifest.fingerprintProfile);
  const profileSchemaPath = manifest.fingerprintProfileSchema === undefined
    ? fileURLToPath(new URL("../../../../schemas/resource-gateway-capability-studio/capability-studio-gate-a-fingerprint-profile-v1.schema.json", import.meta.url))
    : resolve(base, manifest.fingerprintProfileSchema);
  const profile = loadFingerprintProfile(profilePath, profileSchemaPath);
  let checked = 0;
  for (const vector of manifest.vectors) {
    const parsed = parseStrictJson(vector.sourceText);
    const result = documentFingerprint(vector.domain, parsed, vector.selfField);
    const actualRawFingerprint = rawSha256(Buffer.from(vector.sourceText, "utf8"));
    if (result.canonical !== vector.expectedCanonical) {
      throw new Error(`${vector.id}: canonical bytes differ`);
    }
    if (result.fingerprint !== vector.expectedDocumentFingerprint) {
      throw new Error(`${vector.id}: document fingerprint differs`);
    }
    if (actualRawFingerprint !== vector.expectedRawFingerprint) {
      throw new Error(`${vector.id}: raw fingerprint differs`);
    }
    checked++;
  }
  for (const vector of manifest.rejections) {
    try {
      const sourceText = vector.sourceBytesHex === undefined
        ? vector.sourceText
        : decodeStrictUtf8(Buffer.from(vector.sourceBytesHex, "hex"));
      parseStrictJson(sourceText);
      throw new Error(`${vector.id}: input was unexpectedly accepted`);
    } catch (error) {
      if (error.reason !== vector.expectedReason) {
        throw new Error(`${vector.id}: expected ${vector.expectedReason}, got ${error.reason ?? error.message}`);
      }
    }
    checked++;
  }
  let profileRejections = 0;
  for (const vector of manifest.profileRejections ?? []) {
    try {
      const parsed = parseStrictJson(vector.sourceText);
      assertProfileParameters(
        profile,
        vector.objectKind,
        parsed,
        vector.domain,
        vector.selfField,
        vector.fingerprintKind
      );
      throw new Error(`${vector.id}: profile parameters were unexpectedly accepted`);
    } catch (error) {
      if (error.reason !== vector.expectedReason) {
        throw new Error(`${vector.id}: expected ${vector.expectedReason}, got ${error.reason ?? error.message}`);
      }
    }
    profileRejections++;
  }
  process.stdout.write(JSON.stringify({
    status: "PASS",
    checked,
    profileEntries: profile.profiles.length,
    profileRejections
  }) + "\n");
}

function verifyProfile(profilePath, schemaPath) {
  const profile = loadFingerprintProfile(profilePath, schemaPath);
  process.stdout.write(JSON.stringify({status: "PASS", profileEntries: profile.profiles.length}) + "\n");
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const command = process.argv[2];
  if (command === "verify-vectors") {
    const path = process.argv[3] ?? fileURLToPath(new URL("./canonicalization-vectors-v1.json", import.meta.url));
    verifyVectors(path);
  } else if (command === "verify-profile") {
    const profilePath = process.argv[3]
      ?? fileURLToPath(new URL("./fingerprint-profile-v1.json", import.meta.url));
    const schemaPath = process.argv[4]
      ?? fileURLToPath(new URL("../../../../schemas/resource-gateway-capability-studio/capability-studio-gate-a-fingerprint-profile-v1.schema.json", import.meta.url));
    verifyProfile(profilePath, schemaPath);
  } else if (command === "fingerprint") {
    const [domain, selfField = "-"] = process.argv.slice(3);
    const source = decodeStrictUtf8(readFileSync(0));
    const result = documentFingerprint(domain, parseStrictJson(source), selfField === "-" ? null : selfField);
    process.stdout.write(JSON.stringify(result) + "\n");
  } else {
    process.stderr.write("usage: reference-fingerprint.mjs verify-vectors [manifest] | verify-profile [profile] [schema] | fingerprint <domain> [selfField|-]\n");
    process.exitCode = 64;
  }
}

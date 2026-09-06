import json
import unittest

import business_surface_certification_probe as probe


class Response:
    def __init__(self, value):
        self.value = value

    def __enter__(self):
        return self

    def __exit__(self, *_args):
        return False

    def read(self):
        return json.dumps(self.value).encode()


class SurfaceProbeTest(unittest.TestCase):
    def test_requires_server_filtered_list_and_direct_call_rejection(self):
        responses = iter([
            Response({"result": {"tools": [
                {"name": "rg.library.overview.get"}, {"name": "rg.capability.search"}]}}),
            Response({"error": {"code": -32031, "message": "TOOL_NOT_VISIBLE_IN_SURFACE"}}),
        ])

        result = probe.certify_surface("http://127.0.0.1/mcp", "secret", "a" * 64,
                                       lambda *_args, **_kwargs: next(responses))

        self.assertEqual("rg.businessSurfaceProof.v1", result["schemaVersion"])
        self.assertRegex(result["proofFingerprint"], r"^sha256:[0-9a-f]{64}$")

    def test_rejects_client_or_server_catalog_leak(self):
        responses = iter([Response({"result": {"tools": [
            {"name": "rg.library.overview.get"}, {"name": "rg.capability.search"},
            {"name": "rg.dsl.reference.get"}]}})])

        with self.assertRaisesRegex(probe.SurfaceProbeFailure, "exposed"):
            probe.certify_surface("http://127.0.0.1/mcp", "secret", "a" * 64,
                                  lambda *_args, **_kwargs: next(responses))

    def test_rejects_missing_call_guard(self):
        responses = iter([
            Response({"result": {"tools": [
                {"name": "rg.library.overview.get"}, {"name": "rg.capability.search"}]}}),
            Response({"result": {"structuredContent": {"ok": True}}}),
        ])

        with self.assertRaisesRegex(probe.SurfaceProbeFailure, "direct call"):
            probe.certify_surface("http://127.0.0.1/mcp", "secret", "a" * 64,
                                  lambda *_args, **_kwargs: next(responses))


if __name__ == "__main__":
    unittest.main()

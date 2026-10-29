import json
from pathlib import Path
import tempfile
import unittest
from validate_build_output import validate_network_security


class NetworkSecurityPolicyTest(unittest.TestCase):
    def test_default_denies_global_cleartext(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.network(root)
            errors, warnings = [], []
            validate_network_security(root, errors, warnings)
            self.assertEqual(1, len(errors))

    def test_documented_exception_is_scoped_to_one_file(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            path = self.network(root)
            self.exception(root, path)
            errors, warnings = [], []
            validate_network_security(root, errors, warnings)
            self.assertEqual([], errors)
            self.network(root, "other")
            validate_network_security(root, errors, warnings)
            self.assertEqual(1, len(errors))

    def test_missing_review_does_not_allow_cleartext(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            path = self.network(root)
            self.exception(root, path)
            (root / "review.md").unlink()
            errors, warnings = [], []
            validate_network_security(root, errors, warnings)
            self.assertGreaterEqual(len(errors), 2)

    def test_exception_never_allows_tls_bypass(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            path = self.network(root)
            self.exception(root, path)
            code = root / "app/src/main/java/Client.kt"
            code.parent.mkdir(parents=True)
            code.write_text('client.hostnameVerifier { _, _ -> true }', encoding="utf-8")
            errors, warnings = [], []
            validate_network_security(root, errors, warnings)
            self.assertTrue(any("TLS" in error for error in errors))

    def network(self, root, module="app"):
        path = root / module / "src/main/res/xml/network_security_config.xml"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text('<network-security-config><base-config cleartextTrafficPermitted="true"/></network-security-config>')
        return path.relative_to(root).as_posix()

    def exception(self, root, path):
        config = root / ".agents/config/network-security-exceptions.json"
        config.parent.mkdir(parents=True)
        (root / "review.md").write_text("Runtime origin checks reviewed")
        config.write_text(json.dumps({"cleartextExceptions": [{"path": path, "reason": "Dynamic NAS hosts", "review": "review.md"}]}))


if __name__ == "__main__":
    unittest.main()

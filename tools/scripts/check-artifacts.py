"""Validate packaged schemas and debug-only instrumentation, without treating this as device execution."""
from pathlib import Path
import hashlib
import json
import re
import xml.etree.ElementTree as ET
import zipfile

root = Path(__file__).resolve().parents[1]
contract = json.loads((root / "contracts/toolpack-v1.json").read_text())
registered = re.findall(r'Descriptor\("([^"]+)"', (root / "core/src/main/kotlin/dev/thor/tools/Contracts.kt").read_text())
assert set(registered) == {tool["toolId"] for tool in contract["tools"]}
assert len(registered) == len(set(registered))
for variant, apkname in [("debug", "diagnostic-debug.apk"), ("release", "diagnostic-release-unsigned.apk")]:
    apk = root / "diagnostic/build/outputs/apk" / variant / apkname
    with zipfile.ZipFile(apk) as contents:
        assert json.loads(contents.read("assets/toolpack-v1.json")) == contract
        dex = b"".join(contents.read(name) for name in contents.namelist() if name.endswith(".dex"))
        assert (b"LocalSmokeInstrumentation" in dex) == (variant == "debug")
    print(f"{variant}: {apk.stat().st_size} bytes; SHA-256 {hashlib.sha256(apk.read_bytes()).hexdigest()}")
report = ET.parse(root / "core/build/test-results/test/TEST-dev.thor.tools.CoreTest.xml").getroot()
assert report.attrib["failures"] == "0" and report.attrib["errors"] == "0"
lint = ET.parse(root / "diagnostic/build/reports/lint-results-debug.xml").getroot()
errors = [issue for issue in lint if issue.attrib.get("severity") in {"Error", "Fatal"}]
assert not errors, errors
print(f"JVM tests: {report.attrib['tests']} passed; lint: 0 errors, {len(lint)} warnings")

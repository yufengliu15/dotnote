#!/usr/bin/env python3
"""Run focused Android suites separately and fail on instrumentation errors (adb may exit 0)."""
from pathlib import Path
import subprocess
import tempfile

for suite in ("AppUpdateTest", "NativePipelineTest", "VaultPipelineTest", "VaultLifecycleTest"):
    result = subprocess.run([
        "adb", "shell", "am", "instrument", "-w", "-e", "class", f"dev.dotnote.app.{suite}",
        "dev.dotnote.app.test/androidx.test.runner.AndroidJUnitRunner",
    ], text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=240)
    (Path(tempfile.gettempdir()) / f"dotnote-instrumentation-{suite}.log").write_text(result.stdout)
    print(result.stdout)
    if result.returncode or "OK (" not in result.stdout or "FAILURES" in result.stdout or "Process crashed" in result.stdout:
        raise SystemExit(f"{suite} failed")

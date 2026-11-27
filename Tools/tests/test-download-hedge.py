#!/usr/bin/env python3
"""Compiles DownloadHedgePolicy on its own and runs its unit test."""
import subprocess
import tempfile
from pathlib import Path

repo = Path(__file__).resolve().parents[2]
source = repo / 'TMessagesProj/src/main/java/org/telegram/messenger/DownloadHedgePolicy.java'
test = repo / 'Tools/tests/DownloadHedgePolicyTest.java'

with tempfile.TemporaryDirectory(prefix='moagram-hedge-test-') as tmp:
    subprocess.run(['javac', '-d', tmp, str(source), str(test)], check=True)
    subprocess.run(['java', '-cp', tmp, 'org.telegram.messenger.DownloadHedgePolicyTest'], check=True)

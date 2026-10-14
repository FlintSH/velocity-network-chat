#!/usr/bin/env python3
"""Start a real Velocity distribution with the baseline plugin, exercise console, stop.
Usage: python3 scripts/smoke.py JAVA_EXECUTABLE PLUGIN_JAR VELOCITY_VERSION
Only downloads official PaperMC artifacts; validates their SHA-256 checksum.
"""
import hashlib
import json
import pathlib
import queue
import subprocess
import sys
import tempfile
import threading
import time
import urllib.request

java, plugin, version = sys.argv[1:]
plugin = pathlib.Path(plugin).resolve()
headers = {"User-Agent": "VelocityNetworkChat/1.0 (https://github.com/FlintSH/velocity-network-chat)"}

def download(url):
    with urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=60) as response:
        return response.read()

build = json.loads(download(f"https://fill.papermc.io/v3/projects/velocity/versions/{version}/builds/latest"))
artifact = build["downloads"]["server:default"]
jar = download(artifact["url"])
assert hashlib.sha256(jar).hexdigest() == artifact["checksums"]["sha256"], "Proxy checksum mismatch"
with tempfile.TemporaryDirectory(prefix="networkchat-smoke-") as temp:
    root = pathlib.Path(temp)
    (root / "velocity.jar").write_bytes(jar)
    (root / "plugins").mkdir()
    (root / "plugins/networkchat.jar").write_bytes(plugin.read_bytes())
    (root / "velocity.toml").write_text('''config-version = "2.7"
bind = "127.0.0.1:0"
online-mode = false
player-info-forwarding-mode = "NONE"
[servers]
lobby = "127.0.0.1:9"
factions = "127.0.0.1:9"
minigames = "127.0.0.1:9"
try = ["lobby"]
''', encoding="utf-8")
    process = subprocess.Popen([java, "-Xms128m", "-Xmx256m", "-jar", "velocity.jar"], cwd=root,
                               stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                               text=True)
    lines = []
    output = queue.Queue()
    def reader():
        for line in process.stdout:
            output.put(line)
    threading.Thread(target=reader, daemon=True).start()
    try:
        deadline = time.monotonic() + 60
        while time.monotonic() < deadline:
            try:
                line = output.get(timeout=1)
                lines.append(line)
                if "Done" in line:
                    break
            except queue.Empty:
                if process.poll() is not None:
                    raise RuntimeError("Proxy exited before startup")
        else:
            raise TimeoutError("Proxy startup timed out")
        for command in ("networkchat status", "networkchat reload", "networkchat status"):
            process.stdin.write(command + "\n")
            process.stdin.flush()
            # Read the command's response before issuing the next command.
            end = time.monotonic() + 15
            expected = "Configuration reloaded" if "reload" in command else "Players:"
            while time.monotonic() < end:
                try:
                    line = output.get(timeout=1)
                    lines.append(line)
                    if expected in line:
                        break
                except queue.Empty:
                    pass
            else:
                raise TimeoutError(f"No response to {command}")
        process.stdin.write("shutdown\n")
        process.stdin.flush()
        process.wait(timeout=20)
        while not output.empty():
            lines.append(output.get())
        log = "".join(lines)
        assert "Network Chat enabled" in log, "Plugin did not initialize"
        assert "failed to initialize" not in log and "NoSuchMethodError" not in log and "NoClassDefFoundError" not in log, "Plugin runtime failure"
        assert process.returncode == 0, f"Proxy exit code {process.returncode}"
        print(f"PASS: baseline JAR loaded and status/reload worked on Velocity {version} (build {build['id']})")
    finally:
        if process.poll() is None:
            process.kill()
            process.wait()
        print("".join(lines))

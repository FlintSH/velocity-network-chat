#!/usr/bin/env python3
"""Real Paper 26.1.2 bridge probe with controlled player doubles, no real player clients.

Build with JDK25: mvn -B -Ppaper verify dependency:build-classpath -Dmdep.outputFile=target/test-classpath.txt
Run: python3 scripts/bridge-smoke.py paper-bridge/target/networkchat-paper-bridge-1.0.0.jar
Accepts Mojang's EULA for an isolated temporary loopback server. Never uses a production config.
"""
import hashlib
import os
import pathlib
import queue
import secrets
import shutil
import subprocess
import sys
import tempfile
import threading
import time
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parent.parent
JAVA = pathlib.Path(os.environ["JAVA_HOME"]) / "bin/java"
PLUGIN = pathlib.Path(sys.argv[1]).resolve()
SHA = "1d70b1dab9cf4a6de615209a536f3a45a2186240253c428213ce2188ab95e5f7"
URL = "https://fill-data.papermc.io/v1/objects/" + SHA + "/paper-26.1.2-74.jar"
request = urllib.request.Request(URL, headers={"User-Agent": "NetworkChatBridge/1.0 (https://github.com/FlintSH/velocity-network-chat)"})
with urllib.request.urlopen(request, timeout=60) as response:
    jar = response.read()
assert hashlib.sha256(jar).hexdigest() == SHA, "Paper checksum mismatch"

with tempfile.TemporaryDirectory(prefix="networkchat-bridge-paper-") as temporary:
    work = pathlib.Path(temporary)
    (work / "plugins/NetworkChatBridge").mkdir(parents=True)
    shutil.copyfile(PLUGIN, work / "plugins/bridge.jar")
    (work / "paper.jar").write_bytes(jar)
    (work / "eula.txt").write_text("eula=true\n")
    (work / "server.properties").write_text(
        "server-ip=127.0.0.1\nserver-port=0\nonline-mode=false\nlevel-type=minecraft:flat\n"
        "generate-structures=false\nview-distance=2\nsimulation-distance=2\nspawn-protection=0\n"
        "enable-status=false\npause-when-empty-seconds=-1\n"
        'generator-settings={"layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:dirt","height":2},{"block":"minecraft:grass_block","height":1}],"biome":"minecraft:plains"}\n'
    )
    config = (ROOT / "paper-bridge/src/main/resources/config.yml").read_text()
    config = config.replace("shared-secret: ''", "shared-secret: '" + secrets.token_hex(32) + "'")
    config = config.replace("require-inventory-sync: false", "require-inventory-sync: true")
    (work / "plugins/NetworkChatBridge/config.yml").write_text(config)
    classpath = str(PLUGIN) + os.pathsep + (ROOT / "paper-bridge/target/test-classpath.txt").read_text().strip()
    for name, source, extra in [
        ("NetworkInventorySync", "InventoryFixture", ""),
        ("BridgeProbe", "BridgeProbe", "depend: [NetworkChatBridge, NetworkInventorySync]\n"),
    ]:
        classes = work / (source + "-classes")
        classes.mkdir()
        subprocess.run([str(JAVA.with_name("javac")), "-cp", classpath, "-d", str(classes),
                        str(ROOT / "paper-bridge/src/test/probe" / (source + ".java"))], check=True)
        (classes / "plugin.yml").write_text(
            f"name: {name}\nversion: '1'\nmain: dev.flint.networkchat.probe.{source}\napi-version: '26.1.2'\n" + extra
        )
        subprocess.run([str(JAVA.with_name("jar")), "--create", "--file", str(work / "plugins" / (source + ".jar")),
                        "-C", str(classes), "."], check=True)
    process = subprocess.Popen([str(JAVA), "-Xms256m", "-Xmx768m", "-jar", "paper.jar", "--nogui"],
                               cwd=work, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    output = queue.Queue()
    lines = []

    def read_output():
        for line in process.stdout:
            output.put(line)

    threading.Thread(target=read_output, daemon=True).start()
    try:
        deadline = time.monotonic() + 180
        while time.monotonic() < deadline:
            try:
                lines.append(output.get(timeout=1))
            except queue.Empty:
                if process.poll() is not None:
                    break
        else:
            raise TimeoutError("Paper bridge probe timed out")
        while not output.empty():
            lines.append(output.get_nowait())
        log = "".join(lines)
        assert "BRIDGE_PROBE_OK" in log and "BRIDGE_PROBE_FAILED" not in log, "Bridge probe failed"
        assert process.returncode == 0, f"Paper exited {process.returncode}"
        print("PASS: real Paper26.1.2 bridge startup, authentication, readiness, chunk loading, cancellation, position and duplicate handling")
    finally:
        if process.poll() is None:
            process.kill()
            process.wait()
        print("".join(lines))

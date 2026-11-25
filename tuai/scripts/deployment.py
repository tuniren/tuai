"""Shared local helpers for SSH deployment; never print credential values."""
import json
import os
from pathlib import Path
import re
import shlex
import subprocess

ROOT = Path(__file__).resolve().parent.parent


def run(arguments, **kwargs):
    return subprocess.run([str(a) for a in arguments], check=True, **kwargs)


def remote(host, arguments, **kwargs):
    command = " ".join(shlex.quote(str(a)) for a in arguments)
    return run(["ssh", "-o", "BatchMode=yes", "-o", "ConnectTimeout=10", host, command], **kwargs)


def upload(host, local, destination):
    run(["scp", "-q", local, host + ":" + destination])


def prepare_release(host, path):
    if not re.fullmatch(r"/[A-Za-z0-9_./-]+", path) or ".." in path.split("/"):
        raise ValueError("Remote release directory must be an absolute path without spaces or parent traversal")
    remote(host, ["python3", "-c", "from pathlib import Path; import os; p=Path(" + repr(path) +
                  "); p.mkdir(parents=True,exist_ok=True); os.chmod(str(p),0o700)"])


def verify_jar(skip_build):
    if not skip_build:
        log = ROOT / ".tools/docker-build-maven.log"
        log.parent.mkdir(exist_ok=True)
        if os.name == "nt":
            command = ["powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File",
                       str(ROOT / "scripts/maven.ps1"), "-B", "-ntp", "clean", "verify"]
        else:
            command = ["mvn", "-B", "-ntp", "clean", "verify"]
        print("Running Maven clean verify...", flush=True)
        with log.open("wb") as output:
            result = subprocess.run(command, cwd=ROOT, stdout=output, stderr=subprocess.STDOUT)
        if result.returncode:
            print(log.read_text(encoding="utf-8", errors="replace")[-6000:])
            raise RuntimeError("Maven verification failed; see " + str(log))
        print("Maven verification passed.", flush=True)
    jar = ROOT / "target/tuai-0.1.0-SNAPSHOT.jar"
    if not jar.is_file():
        raise FileNotFoundError("Build the executable JAR first: " + str(jar))
    return jar


def settings(path=None):
    source = (ROOT / "src/main/scala/com/tuniren/tuai/AppConfig.scala").read_text(encoding="utf-8")
    values = dict(re.findall(r'value\("([A-Z_]+)",\s*"([^"\r\n]*)"\)', source))
    values.update(dict(re.findall(r'int\("([A-Z_]+)",\s*([0-9]+)\)', source)))
    values.update(dict(re.findall(r'bool\("([A-Z_]+)",\s*(true|false)\)', source)))
    # Match the current configuration precedence without dumping process environment or passwords.
    values = {key: os.environ.get(key, value) for key, value in values.items()}
    if path:
        with Path(path).open(encoding="utf-8") as source_file:
            overrides = json.load(source_file)
        if not isinstance(overrides, dict) or set(overrides) - set(values):
            raise ValueError("Deployment settings must be an object containing known AppConfig keys only")
        if any(not isinstance(value, str) for value in overrides.values()):
            raise ValueError("Deployment setting values must be strings")
        values.update(overrides)
    values["SERVER_ADDRESS"] = "0.0.0.0"
    values["SERVER_PORT"] = "8080"
    required = {"DB_URL", "DB_USERNAME", "DB_PASSWORD", "EMBEDDING_MODEL", "EMBEDDING_DIMENSIONS"}
    if required - set(values):
        raise ValueError("Could not read required AppConfig defaults")
    if not values["DB_URL"].startswith("jdbc:postgresql:"):
        raise ValueError("DB_URL must point to PostgreSQL")
    return values

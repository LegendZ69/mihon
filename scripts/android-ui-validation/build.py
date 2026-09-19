#!/usr/bin/env python3
"""Build only. This helper never invokes adb or connects to a device."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import zipfile


def run(command: list[str], *, env: dict[str, str]) -> str:
    result = subprocess.run(command, text=True, capture_output=True, env=env)
    if result.stderr.strip():
        print(result.stderr.strip())
    result.check_returncode()
    return result.stdout.strip()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sdk", type=Path, default=Path(os.environ.get("ANDROID_HOME", Path.home() / "Library/Android/sdk")))
    parser.add_argument("--java-home", type=Path, default=Path(os.environ.get("JAVA_HOME", "/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home")))
    parser.add_argument("--platform", default="android-37.0")
    parser.add_argument("--build-tools", default="36.1.0")
    parser.add_argument("--keystore", type=Path, default=Path.home() / ".android/debug.keystore")
    parser.add_argument("--alias", default="androiddebugkey")
    parser.add_argument("--out", type=Path, default=Path("build/translator/validation/framework-ui"))
    args = parser.parse_args()
    source = Path(__file__).resolve().parent
    tools = args.sdk / "build-tools" / args.build_tools
    android = args.sdk / "platforms" / args.platform / "android.jar"
    for required in [android, args.keystore, args.java_home / "bin/javac", *[tools / t for t in ["aapt2", "d8", "zipalign", "apksigner"]]]:
        if not required.is_file():
            parser.error(f"Missing build input: {required}")
    if args.keystore.resolve() != (Path.home() / ".android/debug.keystore").resolve():
        parser.error("This validation helper only signs with the local Android debug keystore")
    os.umask(0o077)
    args.out.mkdir(parents=True, exist_ok=True)
    args.out.chmod(0o700)
    environment = dict(os.environ, JAVA_HOME=str(args.java_home))
    environment["PATH"] = str(args.java_home / "bin") + os.pathsep + environment.get("PATH", "")
    # Standard debug-keystore passwords are public defaults, never production credentials.
    environment["MIHON_UI_VALIDATION_DEBUG_PASSWORD"] = "android"
    with tempfile.TemporaryDirectory(prefix="framework-ui-", dir=args.out) as temporary:
        work = Path(temporary)
        classes = work / "classes"
        dex = work / "dex"
        classes.mkdir()
        dex.mkdir()
        sources = sorted((source / "src").rglob("*.java"))
        run([str(args.java_home / "bin/javac"), "-source", "8", "-target", "8", "-Xlint:-options", "-classpath", str(android), "-d", str(classes), *map(str, sources)], env=environment)
        jar = work / "classes.jar"
        with zipfile.ZipFile(jar, "w", zipfile.ZIP_DEFLATED) as archive:
            for item in sorted(classes.rglob("*.class")):
                archive.write(item, item.relative_to(classes).as_posix())
        run([str(tools / "d8"), "--lib", str(android), "--min-api", "26", "--output", str(dex), str(jar)], env=environment)
        unsigned = work / "unsigned.apk"
        run([str(tools / "aapt2"), "link", "-I", str(android), "--manifest", str(source / "AndroidManifest.xml"), "-o", str(unsigned)], env=environment)
        with zipfile.ZipFile(unsigned, "a", zipfile.ZIP_DEFLATED) as archive:
            for item in sorted(dex.glob("*.dex")):
                archive.write(item, item.name)
        aligned = work / "aligned.apk"
        run([str(tools / "zipalign"), "-f", "4", str(unsigned), str(aligned)], env=environment)
        apk = args.out / "mihon-framework-ui-validation.apk"
        run([str(tools / "apksigner"), "sign", "--ks", str(args.keystore), "--ks-key-alias", args.alias,
             "--ks-pass", "env:MIHON_UI_VALIDATION_DEBUG_PASSWORD", "--key-pass", "env:MIHON_UI_VALIDATION_DEBUG_PASSWORD",
             "--out", str(apk), str(aligned)], env=environment)
        certificate = run([str(tools / "apksigner"), "verify", "--verbose", "--print-certs", str(apk)], env=environment)
        manifest = run([str(tools / "aapt2"), "dump", "xmltree", str(apk), "--file", "AndroidManifest.xml"], env=environment)
        metadata = {
            "apk": str(apk.resolve()), "sha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
            "sdk_platform": args.platform, "build_tools": args.build_tools,
            "signature_verification": certificate, "compiled_manifest": manifest,
            "framework_only": True, "application_dependencies": [], "device_actions": [],
            "sources": {str(p.relative_to(source)): hashlib.sha256(p.read_bytes()).hexdigest()
                        for p in [source / "AndroidManifest.xml", *sources]},
        }
        (args.out / "build.json").write_text(json.dumps(metadata, indent=2) + "\n")
        print(json.dumps({"apk": metadata["apk"], "sha256": metadata["sha256"], "device_actions": []}, indent=2))


if __name__ == "__main__":
    main()

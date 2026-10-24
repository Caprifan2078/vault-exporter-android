#!/usr/bin/env python3
"""Rebuild checked OFFLINE core sources from pinned v0.9 + reviewed diffs.

Reports and generated logs are excluded. No Telegram connection; NOT an APK.
"""
import hashlib
import pathlib
import subprocess
import sys
import zipfile

SOURCE_SHA = "6aa2c1c5805b6044b98a7182b5dcd3cbcd80683429cd1d916779463b047fcef5"
PATCHES = [
 "01-hardening.diff", "02-verifier.diff", "03-integration.diff",
 "04-tdlib-reliability.diff", "05-jni-media.diff",
 "06-build-safety.diff", "07-concurrency.diff"
]
DIRS = ("app/", "tests/", "scripts/", ".github/workflows/", "integration-jvm/",
        "transport-jvm/", "native-host-test/", "ci-contract/")
FILES = ("RUN_ALL_OFFLINE.sh", "build.gradle", "settings.gradle",
         "gradle.properties", ".gitignore")
HASHES = {
 "app/src/main/java/org/vault/exporter/StagingCipher.java":"2d503e7c6a8afb0b7905cb58940b22d2aace440c98fd0f8a916ca94f834ac59b",
 "app/src/main/java/org/vault/exporter/ResumeStore.java":"8810cf2072ea111b31b1fca9cd8f82df1fa181b9489c31554ef3b4f023c88b21",
 "app/src/main/java/org/vault/exporter/ArchiveVerifier.java":"4ee3e702a28fcfce61cd3f32800fb38d8d443362c30daebb4ac72bb42dd645f1",
 "app/src/main/java/org/vault/exporter/TdGateway.java":"684a4cc0829e29ec35ccfab1178147ac0bfdb83932ffcef0b3f80e64d2730585",
 "app/src/main/java/org/vault/exporter/MainActivity.java":"1b6e5497b2b62084e61b1af1cbc8513157f8e53746295e1648058ccc829cdbbd",
 ".github/workflows/build-debug-apk.yml":"75ef3c9b1be933818e6078699471c7f469e3f20f83a7459014b04ad5451d5f08",
 "RUN_ALL_OFFLINE.sh":"6cceed2c05783caffbb20a0d852ff872f8a260ecef488afe8cf6c4b7a5aa3bdd",
 "scripts/security_gate.py":"d5ffa35910bf6de6fd07df119f079615a037bf8ec0a0500c8d22ae21c1d1eb93",
 "app/src/main/cpp/vaultbridge.cpp":"9ce5d49e72d0a85b0c2c5c21f73eef3c288494284a7426350b57e01016a42a52",
}

def allowed(p):
    return p in FILES or p.startswith(DIRS)

def normalize(s):
    if s=="/dev/null": return s
    fields=s.split("/")
    for i in range(len(fields)):
        name="/".join(fields[i:])
        if allowed(name):
            if ".." in name.split("/") or "\\" in name or "\0" in name:
                raise ValueError("Unsafe patch path")
            return name
    return None

def source_only_patch(s):
    lines=s.splitlines(keepends=True);i=0;out=[];changed=0
    while i<len(lines):
        if not lines[i].startswith("--- "):
            i+=1;continue
        left=lines[i][4:].split("\t",1)[0].strip().split("  ")[0]
        if i+1>=len(lines) or not lines[i+1].startswith("+++ "):
            raise ValueError("Malformed patch")
        right=lines[i+1][4:].split("\t",1)[0].strip().split("  ")[0]
        i+=2;hunks=[]
        while i<len(lines) and not lines[i].startswith(("diff -","--- ")):
            hunks.append(lines[i]);i+=1
        a,b=normalize(left),normalize(right)
        name=a if b=="/dev/null" else b
        if name and allowed(name):
            if a is None or b is None:raise ValueError("Unsafe source path")
            out.extend(["--- "+(a if a=="/dev/null" else "a/"+a)+"\n",
                        "+++ "+(b if b=="/dev/null" else "b/"+b)+"\n"])
            out.extend(hunks);changed+=1
    if not changed:raise ValueError("Empty source patch")
    return "".join(out),changed

def main():
    if len(sys.argv)!=3:raise SystemExit("Usage: reconstruct.py <repository-root> <output-dir>")
    repo=pathlib.Path(sys.argv[1]).resolve();dst=pathlib.Path(sys.argv[2]).resolve()
    archive=repo/"Vault-Exporter-Android-v09-source.zip"
    if hashlib.sha256(archive.read_bytes()).hexdigest()!=SOURCE_SHA:
        raise ValueError("Pinned archive SHA-256 mismatch")
    dst.mkdir(parents=True,exist_ok=False)
    with zipfile.ZipFile(archive) as z:
        for info in z.infolist():
            p=pathlib.PurePosixPath(info.filename)
            if p.is_absolute() or ".." in p.parts or not p.parts or p.parts[0]!="Vault-Exporter-Android-v09":
                raise ValueError("Unsafe ZIP path")
            if ((info.external_attr >> 16) & 0o170000)==0o120000:
                raise ValueError("ZIP symlink forbidden")
        z.extractall(dst)
    root=dst/"Vault-Exporter-Android-v09"
    for name in PATCHES:
        content=(repo/"ci-rebuild"/"patches"/name).read_text(encoding="utf-8")
        patch,count=source_only_patch(content)
        proc=subprocess.run(["patch","--batch","--forward","--fuzz=0","-p1"],
                            input=patch,text=True,cwd=root,stdout=subprocess.PIPE,stderr=subprocess.STDOUT)
        if proc.returncode:
            print(proc.stdout[-4000:],file=sys.stderr)
            raise RuntimeError("Patch failed: "+name)
        print("APPLIED",name,"source files:",count,flush=True)
    for name,expected in HASHES.items():
        p=root/name
        if not p.is_file() or hashlib.sha256(p.read_bytes()).hexdigest()!=expected:
            raise AssertionError("Audited source hash mismatch: "+name)
    print("PASS: seven patches applied with zero fuzz; nine core-source hashes match",flush=True)
    print("OFFLINE ONLY; no real Android/Telegram proof",flush=True)

if __name__=="__main__":main()

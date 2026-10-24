#!/usr/bin/env python3
"""Reconstruct program source from pinned v0.9 and seven historical patches.
Historical reports/manifests are excluded, never program code.
"""
import pathlib, re, subprocess, sys
ROOTS={'.github','app','tests','scripts','ci-contract','integration-jvm','transport-jvm','native-host-test'}
ROOT_FILES={'.gitignore','build.gradle','settings.gradle','gradle.properties','RUN_ALL_OFFLINE.sh'}
def relative(path):
    if path=='/dev/null':return path
    parts=path.split('/')
    for idx,part in enumerate(parts):
        if part in ROOTS or part in ROOT_FILES:return '/'.join(parts[idx:])
    return None
def filter_patch(content):
    out=[];count=0
    for segment in re.split(r'(?m)(?=^--- )',content):
        if not segment.startswith('--- '):continue
        lines=segment.splitlines(keepends=True)
        if len(lines)<3 or not lines[1].startswith('+++ '):
            raise ValueError('Malformed patch pair')
        old=lines[0][4:].split()[0]
        new=lines[1][4:].split()[0]
        oldrel=relative(old);newrel=relative(new)
        target=newrel if newrel and newrel!='/dev/null' else oldrel
        if not target or (old!='/dev/null' and oldrel is None) or (new!='/dev/null' and newrel is None):
            continue
        lines[0]='--- '+('/dev/null' if old=='/dev/null' else 'a/'+oldrel)+'\n'
        lines[1]='+++ '+('/dev/null' if new=='/dev/null' else 'b/'+newrel)+'\n'
        lines=[line for line in lines if not line.startswith('diff -ruN ')]
        out.append(''.join(lines));count+=1
    if count==0:raise ValueError('No program code patches found')
    return '\n'.join(out),count
def run(root,sources):
    for i,source in enumerate(sources,1):
        patch,n=filter_patch(pathlib.Path(source).read_text(encoding='utf-8'))
        r=subprocess.run(['patch','--batch','--forward','--fuzz=0','-p1'],
          input=patch,text=True,cwd=root,capture_output=True)
        if r.returncode:
            raise RuntimeError(f'Patch {i} failed: {r.stdout[-2000:]}\n{r.stderr}')
        print(f'PASS patch {i} ({n} source sections)',flush=True)
if __name__=='__main__':
    run(pathlib.Path(sys.argv[1]),sys.argv[2:])

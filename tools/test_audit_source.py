#!/usr/bin/env python3
"""Reproducible checks for tools/audit_source.py permission slice gate.
Positive: repo manifest with exactly the 4 approved permissions passes (exit 0).
Negative: adding usage/accessibility/network/alarm/battery/unknown permission fails (exit 1).
Run from repo root: python3 tools/test_audit_source.py
The gate resolves its target repo from its own file location, so the working-tree
tools/audit_source.py is copied into the case tree and that copy is exercised.
"""
from pathlib import Path
import shutil,subprocess,sys,tempfile
root=Path(__file__).resolve().parents[1]
APPROVED=[
 'android.permission.FOREGROUND_SERVICE',
 'android.permission.FOREGROUND_SERVICE_SPECIAL_USE',
 'android.permission.SYSTEM_ALERT_WINDOW',
 'android.permission.POST_NOTIFICATIONS',
]
REJECTED=[
 'android.permission.INTERNET',
 'android.permission.ACCESS_NETWORK_STATE',
 'android.permission.RECEIVE_BOOT_COMPLETED',
 'android.permission.SCHEDULE_EXACT_ALARM',
 'android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS',
 'android.permission.BIND_ACCESSIBILITY_SERVICE',
 'android.permission.CAMERA',
 'android.permission.READ_EXTERNAL_STORAGE',
 'com.example.UNKNOWN_PERMISSION',
]
def replace_manifest(tmp,perms):
 m=tmp/'app/src/main/AndroidManifest.xml'
 text=m.read_text()
 start=text.index('<uses-permission')
 end=text.rindex('/>')+2
 block=''.join('    <uses-permission android:name="%s" />\n'%p for p in perms)
 m.write_text(text[:start]+block+text[end:])
def run_gate(tmp):
 return subprocess.run([sys.executable,str(tmp/'tools/audit_source.py')],cwd=tmp,capture_output=True).returncode
def main():
 failed=[]
 tmp=Path(tempfile.mkdtemp(prefix='audit_gate_test_'))
 try:
  proj=tmp/'project'
  subprocess.run(['git','clone','--quiet','--no-local',str(root),str(proj)],check=True)
  subprocess.run(['git','checkout','--quiet','HEAD'],cwd=proj,check=True)
  shutil.copyfile(root/'tools/audit_source.py',proj/'tools/audit_source.py')
  rc=run_gate(proj)
  if rc!=0:failed.append(('positive','expected 0 got %d'%rc))
  for perm in REJECTED:
   replace_manifest(proj,APPROVED+[perm])
   rc=run_gate(proj)
   if rc!=1:failed.append((perm,'expected 1 got %d'%rc))
   subprocess.run(['git','checkout','--','app/src/main/AndroidManifest.xml'],cwd=proj,check=True)
  replace_manifest(proj,[])
  if run_gate(proj)!=1:failed.append(('empty','expected 1 got 0'))
  subprocess.run(['git','checkout','--','app/src/main/AndroidManifest.xml'],cwd=proj,check=True)
  if run_gate(proj)!=0:failed.append(('restore-check','expected 0 after restore'))
 finally:
  subprocess.run(['rm','-rf',str(tmp)])
 if failed:
  for name,got in failed:print('FAIL',name,got)
  sys.exit(1)
 print('PASS: 4 approved accepted; %d non-whitelisted + empty rejected; gate exit codes correct'%len(REJECTED))
if __name__=='__main__':
 main()

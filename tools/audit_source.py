#!/usr/bin/env python3
"""Bounded clean-source gate. Prints file names/summary only, never matched secret text."""
from pathlib import Path
import hashlib,json,re,subprocess,sys
root=Path(__file__).resolve().parents[1]
res=subprocess.run(['git','ls-files','-z'],cwd=root,capture_output=True,check=True)
paths=[Path(p) for p in res.stdout.decode().split('\0') if p]
errors=[]; hashes={}
for path in paths:
 f=root/path
 if f.is_symlink() or not f.is_file():errors.append((str(path),'not a regular file'));continue
 if any(p in {'build','.gradle','.kotlin','evidence','node_modules'} for p in path.parts) or path.name.startswith('.gh_token') or path.suffix.lower() in {'.apk','.aab','.jks','.keystore','.p12','.pem'} or path.name=='local.properties':errors.append((str(path),'excluded artifact/config'))
 data=f.read_bytes();hashes[str(path)]=hashlib.sha256(data).hexdigest()
 if len(data)>2*1024*1024:errors.append((str(path),'unexpected large source file'))
 if path.name=='gradle-wrapper.jar':continue
 try:text=data.decode('utf-8')
 except UnicodeDecodeError:errors.append((str(path),'unexpected binary'));continue
 if re.search(r'github_pat_[A-Za-z0-9_]{20,}|gh[pousr]_[A-Za-z0-9]{20,}|-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----',text):errors.append((str(path),'credential-like content'))
manifest=(root/'app/src/main/AndroidManifest.xml').read_text()
if '<uses-permission' in manifest:errors.append(('AndroidManifest.xml','preview requests permission outside approved first slice'))
print(json.dumps({'trackedFiles':len(paths),'totalBytes':sum((root/p).stat().st_size for p in paths if (root/p).is_file()),'errors':errors,'note':'pattern gate supplements human scope review; not exhaustive secret detection'},ensure_ascii=False,indent=2))
if errors:sys.exit(1)

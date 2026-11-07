"""Package only project source and selected verification evidence."""
from pathlib import Path
import hashlib
import shutil
import zipfile

ROOT=Path(__file__).resolve().parents[1]
DIST=ROOT/'dist'
DIST.mkdir(exist_ok=True)
target=DIST/'qingri-1.4.0-source.zip'
with zipfile.ZipFile(target,'w',zipfile.ZIP_DEFLATED,compresslevel=9) as archive:
    for folder in ('app','scripts','tests','docs'):
        for path in sorted((ROOT/folder).rglob('*')):
            if path.is_file() and '__pycache__' not in path.parts and path.suffix not in ('.pyc','.keystore','.jks'):
                archive.write(path,'qingri/'+path.relative_to(ROOT).as_posix())
    for name in ('README.md','build.ps1','.gitignore'):
        archive.write(ROOT/name,'qingri/'+name)
    for name in ('core-tests.log','storage-tests.log','ui-tests.log','regression-tests.log','denied-tests.log','channel-tests.log','process-restore.log','signature.log','records-tests.log','records-ui-tests.log','upgrade-tests.log','pie3d-touch.log','agent-tests.log','agent-ui-tests.log','upgrade13-before.log','upgrade13-after.log','upgrade14-before.log','upgrade14-after.log'):
        path=ROOT/'build/qa'/name
        if path.exists():archive.write(path,'qingri/verification-logs/'+name)
with zipfile.ZipFile(target) as archive:
    names=archive.namelist()
    assert archive.testzip() is None
    assert 'qingri/app/src/main/AndroidManifest.xml' in names
    assert 'qingri/scripts/test.ps1' in names
    assert not any('/.tools/' in n or n.endswith(('.keystore','.jks','.pyc')) for n in names)

shutil.copyfile(target,DIST/'qingri-source.zip')
outputs=[DIST/'qingri-1.4.0.apk',target]
(DIST/'SHA256SUMS.txt').write_text(''.join(hashlib.sha256(p.read_bytes()).hexdigest()+'  '+p.name+'\n' for p in outputs),encoding='ascii')
for path in outputs:print(path.name,path.stat().st_size,'bytes',hashlib.sha256(path.read_bytes()).hexdigest())
print('ZIP integrity verified; source entries:',len(names))

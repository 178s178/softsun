"""Download portable official build tools into .tools (no global installation)."""
from pathlib import Path
import concurrent.futures
import hashlib
import json
import shutil
import urllib.request
import zipfile
import time

def digest(path, algo):
    h=hashlib.new(algo)
    with path.open('rb') as source:
        for chunk in iter(lambda:source.read(1024*1024),b''): h.update(chunk)
    return h.hexdigest()

ROOT = Path(__file__).resolve().parents[1]
TOOLS = ROOT / '.tools'
PACKAGES = [
    ('platform', 'https://dl.google.com/android/repository/platform-35_r02.zip', 'sha1', '0bb560a90a7a2cbd0dd8348224d518b638fe7949'),
    ('build-tools', 'https://dl.google.com/android/repository/build-tools_r35.0.1_windows.zip', 'sha1', '1cbfa5564b62504a1111352edda35914cf723a20'),
    ('platform-tools', 'https://dl.google.com/android/repository/platform-tools_r37.0.1-win.zip', 'sha1', 'e03e78b1d80b396f1c3358e31251cb31740e1110'),
    ('jdk', 'https://aka.ms/download-jdk/microsoft-jdk-17-windows-x64.zip', 'sha256', None),
]

def fetch(item):
    name, url, algo, expected = item
    dest = TOOLS / name
    if (dest / '.ready').exists():
        print(f'{name}: already ready', flush=True)
        return
    cache = TOOLS / 'downloads' / f'{name}.zip'
    cache.parent.mkdir(parents=True, exist_ok=True)
    if expected is None:
        expected = urllib.request.urlopen(url + '.sha256', timeout=60).read().decode().split()[0].lower()
    if not cache.exists() or digest(cache, algo) != expected:
        print(f'{name}: downloading', flush=True)
        for attempt in range(5):
            offset=cache.stat().st_size if cache.exists() else 0
            req=urllib.request.Request(url,headers={'Range':f'bytes={offset}-','Accept-Encoding':'identity'})
            try:
                with urllib.request.urlopen(req, timeout=45) as src, cache.open('ab' if src.status==206 else 'wb') as dst:
                    shutil.copyfileobj(src, dst, 1024 * 1024)
                break
            except (TimeoutError, OSError) as exc:
                print(f'{name}: resume after {type(exc).__name__} (attempt {attempt+1})',flush=True)
                if attempt==4: raise
    actual = digest(cache, algo)
    if actual != expected:
        raise RuntimeError(f'{name}: checksum mismatch')
    dest.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(cache) as archive:
        for member in archive.infolist():
            candidate = (dest / member.filename).resolve()
            if not candidate.is_relative_to(dest.resolve()):
                raise RuntimeError('Unsafe archive path')
        archive.extractall(dest)
    (dest / '.ready').write_text(json.dumps({'url':url,'algorithm':algo,'checksum':actual}), encoding='utf-8')
    print(f'{name}: verified and extracted', flush=True)

if __name__ == '__main__':
    if Path('C:/Program Files/Android/openjdk/jdk-21.0.8/bin/javac.exe').exists():
        PACKAGES = [p for p in PACKAGES if p[0] != 'jdk']
        print('jdk: using existing portable Android OpenJDK 21', flush=True)
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        list(pool.map(fetch, PACKAGES))

import sys
import urllib.request
import xml.etree.ElementTree as ET
import concurrent.futures
from setup_tools import fetch

packages=[]
for xml_url, package_path, base in [
    ('https://dl.google.com/android/repository/repository2-3.xml','emulator','https://dl.google.com/android/repository/'),
    ('https://dl.google.com/android/repository/sys-img/android/sys-img2-3.xml','system-images;android-35;default;x86_64','https://dl.google.com/android/repository/sys-img/android/'),
]:
    root=ET.fromstring(urllib.request.urlopen(xml_url,timeout=45).read())
    candidates=[p for p in root.findall('remotePackage') if p.attrib['path']==package_path]
    # channelRef is an attribute-only element; prefer the stable channel.
    candidates=[p for p in candidates if p.find('channelRef') is None or p.find('channelRef').attrib.get('ref')=='channel-0']
    if not candidates: raise RuntimeError('Official package unavailable: '+package_path)
    p=candidates[0]
    for a in p.findall('./archives/archive'):
        if a.findtext('host-os') in (None,'windows'):
            name='emulator' if package_path=='emulator' else 'system-image'
            packages.append((name,base+a.findtext('complete/url'),'sha1',a.findtext('complete/checksum')))
            print(name,a.findtext('complete/size'),flush=True)
            break
with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
    list(pool.map(fetch,packages))

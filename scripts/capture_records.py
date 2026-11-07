"""Capture actual day/month records screens after the emulator-only UI fixture test."""
from pathlib import Path
import subprocess
import re
import xml.etree.ElementTree as ET

ROOT=Path(__file__).resolve().parents[1]
ADB=ROOT/'.tools/platform-tools/platform-tools/adb.exe'
QA=ROOT/'build/qa'
def run(*args):return subprocess.run([str(ADB),'-s','emulator-5556',*args],capture_output=True,check=True).stdout
def tree():
    run('shell','uiautomator','dump','/sdcard/records.xml');run('pull','/sdcard/records.xml',str(QA/'records.xml'));return list(ET.parse(QA/'records.xml').iter('node'))
def tap(label):
    nodes=tree()
    for key in ('content-desc','text'):
        for node in nodes:
            if node.attrib.get(key)==label:
                a,b,c,d=map(int,re.findall(r'\d+',node.attrib['bounds']));run('shell','input','tap',str((a+c)//2),str((b+d)//2));return
    raise RuntimeError('Missing UI action: '+label)
def screenshot(name):
    tree();run('shell','screencap','-p','/sdcard/records.png');run('pull','/sdcard/records.png',str(QA/name))
run('shell','am','start','-W','-n','cn.qingri.app/.MainActivity','--es','date','2026-11-03')
tap('记录')
tap('日统计');screenshot('records-day.png')
tap('月统计');screenshot('records-month.png')
tap('日统计')
for node in tree():
    if node.attrib.get('content-desc','').startswith('学习时长扇形图'):
        a,b,c,d=map(int,re.findall(r'\d+',node.attrib['bounds']));run('shell','input','tap',str((a+c)//2+(c-a)//6),str((b+d)//2));break
screenshot('records-selected.png')
print('Captured real day, month, selected-session screens')

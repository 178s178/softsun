"""Capture real UI and check study restoration, using only the QA emulator."""
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET

ROOT=Path(__file__).resolve().parents[1]
ADB=ROOT/'.tools/platform-tools/platform-tools/adb.exe'
QA=ROOT/'build/qa'
def adb(*args):
    return subprocess.run([str(ADB),'-s','emulator-5556',*args],capture_output=True,check=True).stdout
def tree():
    adb('shell','uiautomator','dump','/sdcard/qingri-capture.xml')
    adb('pull','/sdcard/qingri-capture.xml',str(QA/'capture.xml'))
    return ET.parse(QA/'capture.xml')
def tap(label):
    nodes=list(tree().iter('node'))
    for key in ('content-desc','text'):
        for n in nodes:
            if n.attrib.get(key)==label:
                x1,y1,x2,y2=map(int,re.findall(r'\d+',n.attrib['bounds']))
                adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2));return
    raise RuntimeError('UI element missing: '+label)
def shot(name):
    adb('shell','screencap','-p','/sdcard/qingri-capture.png')
    adb('pull','/sdcard/qingri-capture.png',str(QA/name))

adb('shell','pm','grant','cn.qingri.app','android.permission.POST_NOTIFICATIONS')
adb('shell','appops','set','cn.qingri.app','SCHEDULE_EXACT_ALARM','allow')
adb('shell','am','start','-n','cn.qingri.app/.MainActivity')
tap('日程');tap('今天');tree();shot('agenda.png')
tap('月历');tree();shot('calendar.png')
tap('学习');tree();shot('focus-ready.png')
try: tap('开始学习')
except RuntimeError: tap('再开始一段')
time.sleep(1)
adb('shell','input','keyevent','3');time.sleep(1)
adb('shell','am','force-stop','cn.qingri.app');time.sleep(1)
adb('shell','am','start','-n','cn.qingri.app/.MainActivity')
tap('学习')
texts=[n.attrib.get('text','') for n in tree().iter('node')]
timer=next(t for t in texts if re.fullmatch(r'\d+:\d{2}:\d{2}',t))
h,m,s=map(int,timer.split(':'))
if h*3600+m*60+s<3: raise AssertionError('Study timer did not preserve process-stop interval')
message='PASS: study timer survives leaving page and process force-stop; restored elapsed '+timer
(QA/'process-restore.log').write_text(message+'\n',encoding='utf-8');print(message)
shot('focus.png')
tap('暂停');tap('结束本次')

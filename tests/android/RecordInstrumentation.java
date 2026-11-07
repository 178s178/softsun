package cn.qingri.app.tests;

import android.app.*;
import android.content.*;
import android.os.*;
import cn.qingri.app.*;
import java.time.*;
import java.util.*;
import org.json.*;

public class RecordInstrumentation extends Instrumentation {
    int checks;
    void check(boolean pass,String why){checks++;if(!pass)throw new AssertionError(why);}
    @Override public void onCreate(Bundle b){super.onCreate(b);start();}
    @Override public void onStart(){Bundle result=new Bundle();try{
        Context c=getTargetContext().createDeviceProtectedStorageContext();c.deleteDatabase("qingri.db");
        try(EventStore db=new EventStore(c)){
            TimeRecord r=new TimeRecord();r.token="record-fixture";r.label="高数复习";r.start=1000;r.end=901000;r.duration=600000;
            r.segments.add(new TimeRecord.Segment(1000,301000,300000));r.segments.add(new TimeRecord.Segment(601000,901000,300000));
            db.saveTimeRecord(r);check(db.studyRecords().size()==1,"Time record is persisted");
            TimeRecord loaded=db.studyRecords().get(0);check(loaded.label.equals("高数复习")&&loaded.segments.size()==2,"Label and pause-separated segments round trip");
            check(db.studyStats(0,1000000)[0]==600000,"Effective duration excludes pause");
            db.renameStudy(r.token,"高数习题");check(db.studyRecords().get(0).label.equals("高数习题"),"Rename updates label without changing duration");
            String backup=db.exportJson();check(new JSONObject(backup).getInt("version")==3,"Backup format upgraded");db.deleteStudy(r.token);check(db.studyRecords().isEmpty(),"Delete removes record");db.importJson(backup);
            check(db.studyRecords().size()==1&&db.studyRecords().get(0).segments.size()==2,"Backup restores actual effective segments");
            JSONObject bad=new JSONObject(backup);bad.getJSONArray("study").getJSONObject(0).getJSONArray("segments").getJSONObject(0).put("duration",5);
            try{db.importJson(bad.toString());throw new AssertionError("Invalid segment duration was accepted");}catch(IllegalArgumentException expected){check(db.studyRecords().get(0).duration==600000,"Invalid segment totals preserve original records");}
            JSONObject old=new JSONObject(backup);old.put("version",2);JSONObject oldRecord=old.getJSONArray("study").getJSONObject(0);oldRecord.remove("label");oldRecord.remove("segments");db.importJson(old.toString());
            check(db.studyRecords().size()==1&&db.studyRecords().get(0).segments.isEmpty(),"Old backup restores legacy records without invented segments");
            check(db.studyRecords().get(0).label.isEmpty(),"Old backup does not fabricate a session name");
            TimeRecord fragmented=new TimeRecord();fragmented.token="many-fragments";fragmented.start=1000;fragmented.end=21001;fragmented.duration=20001;
            for(int i=0;i<20001;i++)fragmented.segments.add(new TimeRecord.Segment(1000+i,1001+i,1));
            db.saveTimeRecord(fragmented);check(db.studyRecords().stream().anyMatch(record->record.token.equals(fragmented.token)&&record.segments.size()==20001),"Every successfully saved multi-fragment record can be read");db.deleteStudy(fragmented.token);
        }
        Context main=getTargetContext();main.getSharedPreferences("study",0).edit().clear().commit();
        StudySession first=new StudySession(main);first.label="自动记录测试";first.start();Thread.sleep(40);first.pause();StudySession stale=new StudySession(main);first.finish();StudySession newer=new StudySession(main);newer.start();
        stale.checkpoint();stale.pause();stale.start();StudySession restored=new StudySession(main);check(restored.timer.isRunning()&&newer.isCurrent(),"Stale session cannot overwrite a newer timer");newer.pause();Thread.sleep(10);newer.finish();
        SharedPreferences prefs=main.getSharedPreferences("study",0);String crashToken="finished-before-clear";
        try(EventStore db=new EventStore(main)){TimeRecord done=new TimeRecord();done.token=crashToken;done.start=1000;done.end=2000;done.duration=1000;done.label="已保存的学习";done.segments.add(new TimeRecord.Segment(1000,2000,1000));db.saveTimeRecord(done);}
        prefs.edit().clear().putString("token",crashToken).putLong("startedWall",1000).putLong("accumulated",1000).putBoolean("running",false).commit();
        StudySession recovered=new StudySession(main);check(recovered.recoveredCompleted&&recovered.startedWall==0,"Already finalized token is reconciled after a crash");
        recovered.start();check(!prefs.getString("token","").equals(crashToken),"Next study uses a new identity rather than losing resumed time");Thread.sleep(5);recovered.finish();
        result.putString("stream","PASS: "+checks+" time storage assertions\n");finish(Activity.RESULT_OK,result);
    }catch(Throwable ex){result.putString("stream","FAIL: "+android.util.Log.getStackTraceString(ex));finish(Activity.RESULT_CANCELED,result);}}
}

package cn.qingri.app.tests;

import android.app.*;
import android.content.*;
import android.database.*;
import android.database.sqlite.*;
import android.os.*;

public class UpgradeInstrumentation extends Instrumentation {
    String phase;
    @Override public void onCreate(Bundle args){super.onCreate(args);phase=args.getString("phase","before");start();}
    @Override public void onStart(){Bundle output=new Bundle();try{
        Context c=getTargetContext();SharedPreferences snapshot=c.getSharedPreferences("upgrade-test",0);
        if("before".equals(phase)){
            String device=(Build.FINGERPRINT+" "+Build.MODEL+" "+Build.PRODUCT).toLowerCase(java.util.Locale.ROOT);
            if(!(device.contains("generic")||device.contains("emulator")||device.contains("sdk_gphone")||device.contains("sdk_phone")))throw new AssertionError("Upgrade fixtures restricted to QA emulator");
            try(cn.qingri.app.EventStore store=new cn.qingri.app.EventStore(c)){
                if(store.list().isEmpty()){cn.qingri.app.Event e=new cn.qingri.app.Event();e.title="升级保留日程";e.start=java.time.LocalDateTime.now().plusDays(3).withSecond(0).withNano(0);e.end=e.start.plusHours(1);store.save(e);}
                if(store.studyRecords().isEmpty())store.saveStudy("upgrade13-fixture",1000,61000,60000);
            }
        }
        if("after".equals(phase)){Object store=Class.forName("cn.qingri.app.EventStore").getConstructor(Context.class).newInstance(c);store.getClass().getMethod("getWritableDatabase").invoke(store);store.getClass().getMethod("close").invoke(store);}
        try(SQLiteDatabase db=SQLiteDatabase.openDatabase(c.getDatabasePath("qingri.db").getPath(),null,SQLiteDatabase.OPEN_READONLY)){
            String legacy="";
            long events=scalar(db,"SELECT COUNT(*) FROM events"),sessions=scalar(db,"SELECT COUNT(*) FROM study_sessions"+legacy),duration=scalar(db,"SELECT COALESCE(SUM(duration_ms),0) FROM study_sessions"+legacy);
            if("before".equals(phase)){if(events==0||sessions==0)throw new AssertionError("Upgrade fixture must have existing data");snapshot.edit().putLong("events",events).putLong("sessions",sessions).putLong("duration",duration).commit();}
            else if(db.getVersion()!=5||events!=snapshot.getLong("events",-1)||sessions!=snapshot.getLong("sessions",-1)||duration!=snapshot.getLong("duration",-1))throw new AssertionError("Upgrade changed existing data");
            output.putString("stream","PASS: upgrade "+phase+" schema="+db.getVersion()+", events="+events+", sessions="+sessions+", duration="+duration+"\n");
        }finish(Activity.RESULT_OK,output);
    }catch(Throwable ex){output.putString("stream","FAIL: "+android.util.Log.getStackTraceString(ex));finish(Activity.RESULT_CANCELED,output);}}
    long scalar(SQLiteDatabase db,String sql){try(Cursor c=db.rawQuery(sql,null)){c.moveToFirst();return c.getLong(0);}}
}

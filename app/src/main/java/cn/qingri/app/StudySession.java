package cn.qingri.app;

import android.content.*;
import android.os.SystemClock;
import android.provider.Settings;
import org.json.*;
import java.util.*;

public class StudySession {
    private static final Object LOCK=new Object();
    private final Context context;
    private final SharedPreferences prefs;
    public final StudyTimer timer;
    public long startedWall;
    public String label="";
    private String token;
    private long wallAnchor,generation;
    private boolean canMerge,estimated;
    private final List<TimeRecord.Segment> segments=new ArrayList<>();
    public final boolean rebooted,recoveredCompleted;
    private final int boot;

    public StudySession(Context supplied){
        synchronized(LOCK){
            context=supplied.getApplicationContext();prefs=supplied.getSharedPreferences("study",0);
            boot=Settings.Global.getInt(supplied.getContentResolver(),Settings.Global.BOOT_COUNT,-1);
            String savedToken=prefs.getString("token","");boolean finalized=false;
            if(!savedToken.isEmpty())try(EventStore store=new EventStore(context)){finalized=store.hasStudyToken(savedToken);}
            if(finalized)prefs.edit().clear().putLong("generation",prefs.getLong("generation",0)+1).commit();
            recoveredCompleted=finalized;generation=prefs.getLong("generation",0);
            long now=SystemClock.elapsedRealtime();boolean sameBoot=prefs.getInt("boot",boot)==boot;
            rebooted=prefs.getBoolean("running",false)&&(!sameBoot||prefs.getLong("anchor",0)>now);
            timer=new StudyTimer(prefs.getLong("accumulated",0),prefs.getBoolean("running",false)&&!rebooted,prefs.getLong("anchor",0));
            startedWall=prefs.getLong("startedWall",0);token=prefs.getString("token","");label=prefs.getString("label","");canMerge=prefs.getBoolean("canMerge",false);estimated=prefs.getBoolean("estimated",false);
            long oldAnchor=timer.anchor();long inferred=oldAnchor>0&&sameBoot&&oldAnchor<=now?System.currentTimeMillis()-(now-oldAnchor):startedWall+timer.accumulated();
            wallAnchor=prefs.getLong("wallAnchor",startedWall>0?Math.max(startedWall,inferred):System.currentTimeMillis());
            try{TimeRecord record=new TimeRecord();TimeRecordStore.readSegments(record,new JSONArray(prefs.getString("segments","[]")));segments.addAll(record.segments);}catch(JSONException ex){throw new IllegalStateException("计时片段恢复失败",ex);}
            if(startedWall>0&&segments.isEmpty()&&timer.accumulated()>0){segments.add(new TimeRecord.Segment(startedWall,Math.max(startedWall,wallAnchor),timer.accumulated()));estimated=true;}
            if(startedWall>0&&token.isEmpty()){token=UUID.randomUUID().toString();persist();}
            if(rebooted){canMerge=false;persist();}
        }
    }
    private boolean current(){return generation==prefs.getLong("generation",0)&&token.equals(prefs.getString("token",""));}
    public boolean isCurrent(){synchronized(LOCK){return current();}}
    public void start(){synchronized(LOCK){
        if(!current())return;
        if(startedWall==0){startedWall=System.currentTimeMillis();token=UUID.randomUUID().toString();}
        if(!timer.isRunning()){wallAnchor=System.currentTimeMillis();canMerge=false;}
        timer.start(SystemClock.elapsedRealtime());persist();
    }}
    private long capture(){
        long now=SystemClock.elapsedRealtime();
        if(timer.isRunning()){
            long delta=timer.elapsed(now)-timer.accumulated();
            if(delta>0){
                TimeRecord.Segment previous=segments.isEmpty()?null:segments.get(segments.size()-1);
                if(canMerge&&previous!=null&&previous.duration==previous.end-previous.start&&Math.abs(previous.end-wallAnchor)<=1000)segments.set(segments.size()-1,new TimeRecord.Segment(previous.start,previous.end+delta,previous.duration+delta));
                else segments.add(new TimeRecord.Segment(wallAnchor,wallAnchor+delta,delta));
                canMerge=true;
            }
            timer.checkpoint(now);wallAnchor=System.currentTimeMillis();
        }return now;
    }
    public void pause(){synchronized(LOCK){if(!current())return;long now=capture();timer.pause(now);canMerge=false;persist();}}
    private void persist(){
        TimeRecord record=new TimeRecord();record.segments.addAll(segments);String encoded;
        try{encoded=TimeRecordStore.segments(record).toString();}catch(JSONException ex){throw new IllegalStateException(ex);}
        generation++;
        prefs.edit().putLong("generation",generation).putLong("accumulated",timer.accumulated()).putLong("anchor",timer.anchor()).putBoolean("running",timer.isRunning()).putLong("startedWall",startedWall).putString("token",token).putInt("boot",boot).putLong("wallAnchor",wallAnchor).putBoolean("canMerge",canMerge).putBoolean("estimated",estimated).putString("label",label).putString("segments",encoded).commit();
    }
    public void save(){synchronized(LOCK){if(current())persist();}}
    public void checkpoint(){synchronized(LOCK){if(current()){capture();persist();}}}
    public long finish(){synchronized(LOCK){
        if(!current())return timer.elapsed(SystemClock.elapsedRealtime());
        long now=capture();timer.pause(now);canMerge=false;persist();long duration=timer.accumulated();
        if(duration>0)try(EventStore store=new EventStore(context)){
            TimeRecord record=new TimeRecord();record.token=token;record.label=label;record.start=startedWall;record.end=Math.max(startedWall,System.currentTimeMillis());record.duration=duration;record.estimated=estimated;record.segments.addAll(segments);
            for(TimeRecord.Segment segment:segments){record.start=Math.min(record.start,segment.start);record.end=Math.max(record.end,segment.end);}store.saveTimeRecord(record);
        }
        prefs.edit().clear().putLong("generation",generation+1).commit();return duration;
    }}
    public static boolean active(Context c){return c.getSharedPreferences("study",0).getBoolean("running",false);}
    public static String format(long duration){long seconds=Math.max(0,duration/1000);return String.format(Locale.ROOT,"%02d:%02d:%02d",seconds/3600,seconds/60%60,seconds%60);}
}

package cn.qingri.app;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import org.json.*;
import java.time.LocalDateTime;
import java.util.*;

public class EventStore extends SQLiteOpenHelper implements AutoCloseable {
    public static final int MAX_BACKUP_BYTES=64*1024*1024,MAX_EVENTS=20000,MAX_RECORDS=200000;
    public java.util.List<TimeRecord> studyRecords(){return TimeRecordStore.list(getReadableDatabase());}
    public java.util.List<TimeRecord> studyRecords(long from,long to){return TimeRecordStore.list(getReadableDatabase(),"start_ms<? AND end_ms>=?",new String[]{""+to,""+from});}
    public boolean hasStudyToken(String token){try(Cursor c=getReadableDatabase().query("study_sessions",new String[]{"id"},"session_token=?",new String[]{token},null,null,null)){return c.moveToFirst();}}
    public void saveTimeRecord(TimeRecord record){TimeRecordStore.save(getWritableDatabase(),record);}
    public void renameStudy(String token,String label){if(label==null||label.trim().length()>80)throw new IllegalArgumentException("名称最多80字");ContentValues v=new ContentValues();v.put("label",label.trim());getWritableDatabase().update("study_sessions",v,"session_token=?",new String[]{token});}
    public void deleteStudy(String token){getWritableDatabase().delete("study_sessions","session_token=?",new String[]{token});}
    public EventStore(Context c) { super(c,"qingri.db",null,5); }
    @Override public void onConfigure(SQLiteDatabase db) { db.setForeignKeyConstraintsEnabled(true); }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE events(id INTEGER PRIMARY KEY AUTOINCREMENT,revision TEXT NOT NULL,title TEXT NOT NULL,note TEXT NOT NULL,location TEXT NOT NULL,category TEXT NOT NULL,priority INTEGER NOT NULL,start_at TEXT NOT NULL,end_at TEXT NOT NULL,repeat_rule TEXT NOT NULL,reminder INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE completed(occurrence_key TEXT PRIMARY KEY,event_id INTEGER NOT NULL REFERENCES events(id) ON DELETE CASCADE)");
        db.execSQL("CREATE TABLE delivered(occurrence_key TEXT PRIMARY KEY,event_id INTEGER NOT NULL REFERENCES events(id) ON DELETE CASCADE)");
        createStudyTable(db);
        db.execSQL("CREATE INDEX study_period ON study_sessions(end_ms,start_ms)");
        createAgentTable(db);
    }
    private void createAgentTable(SQLiteDatabase db){db.execSQL("CREATE TABLE agent_runs(run_id TEXT PRIMARY KEY,applied_at TEXT NOT NULL)");}
    private void createStudyTable(SQLiteDatabase db){db.execSQL("CREATE TABLE study_sessions(id INTEGER PRIMARY KEY AUTOINCREMENT,session_token TEXT NOT NULL UNIQUE,start_ms INTEGER NOT NULL,end_ms INTEGER NOT NULL,duration_ms INTEGER NOT NULL,label TEXT NOT NULL DEFAULT '',segments_json TEXT NOT NULL DEFAULT '[]',estimated INTEGER NOT NULL DEFAULT 1)");}
    @Override public void onUpgrade(SQLiteDatabase db,int oldVersion,int newVersion) {
        if(oldVersion<2)createStudyTable(db);
        if(oldVersion<3){db.execSQL("ALTER TABLE events ADD COLUMN revision TEXT NOT NULL DEFAULT ''");db.execSQL("UPDATE events SET revision=lower(hex(randomblob(16)))");if(oldVersion>=2){db.execSQL("ALTER TABLE study_sessions ADD COLUMN session_token TEXT");db.execSQL("UPDATE study_sessions SET session_token='legacy-'||id");db.execSQL("CREATE UNIQUE INDEX study_session_token ON study_sessions(session_token)");}}
        if(oldVersion>=2&&oldVersion<4){db.execSQL("ALTER TABLE study_sessions ADD COLUMN label TEXT NOT NULL DEFAULT ''");db.execSQL("ALTER TABLE study_sessions ADD COLUMN segments_json TEXT NOT NULL DEFAULT '[]'");db.execSQL("ALTER TABLE study_sessions ADD COLUMN estimated INTEGER NOT NULL DEFAULT 1");}
        db.execSQL("CREATE INDEX IF NOT EXISTS study_period ON study_sessions(end_ms,start_ms)");
        if(oldVersion<5)createAgentTable(db);
    }
    public void saveStudy(long start,long end,long duration){saveStudy(UUID.randomUUID().toString(),start,end,duration);}
    public void saveStudy(String token,long start,long end,long duration){if(duration<=0||end<start)return;TimeRecord r=new TimeRecord();r.token=token;r.start=start;r.end=end;r.duration=duration;r.estimated=true;saveTimeRecord(r);}
    public long[] studyStats(long from,long to){TimeStats.Summary summary=TimeStats.summarize(studyRecords(from,to),from,to);return new long[]{summary.total,summary.slices.size()};}
    private Event read(Cursor c) {
        Event e=new Event();e.id=c.getLong(c.getColumnIndexOrThrow("id"));e.revision=c.getString(c.getColumnIndexOrThrow("revision"));e.title=c.getString(c.getColumnIndexOrThrow("title"));e.note=c.getString(c.getColumnIndexOrThrow("note"));e.location=c.getString(c.getColumnIndexOrThrow("location"));e.category=c.getString(c.getColumnIndexOrThrow("category"));e.priority=c.getInt(c.getColumnIndexOrThrow("priority"));e.start=LocalDateTime.parse(c.getString(c.getColumnIndexOrThrow("start_at")));e.end=LocalDateTime.parse(c.getString(c.getColumnIndexOrThrow("end_at")));e.repeat=c.getString(c.getColumnIndexOrThrow("repeat_rule"));e.reminder=c.getInt(c.getColumnIndexOrThrow("reminder"));return e;
    }
    public List<Event> list() {
        List<Event> result=new ArrayList<>();try(Cursor c=getReadableDatabase().query("events",null,null,null,null,null,"start_at ASC,id ASC")){while(c.moveToNext())result.add(read(c));}return result;
    }
    public Event find(long id) {try(Cursor c=getReadableDatabase().query("events",null,"id=?",new String[]{String.valueOf(id)},null,null,null)){return c.moveToFirst()?read(c):null;} }
    private ContentValues values(Event e) {
        ContentValues v=new ContentValues();v.put("revision",e.revision);v.put("title",e.title.trim());v.put("note",e.note);v.put("location",e.location);v.put("category",e.category);v.put("priority",e.priority);v.put("start_at",e.start.toString());v.put("end_at",e.end.toString());v.put("repeat_rule",e.repeat);v.put("reminder",e.reminder);return v;
    }
    public void save(Event e) {
        String problem=ScheduleEngine.validate(e);if(!problem.isEmpty())throw new IllegalArgumentException(problem);
        e.revision=UUID.randomUUID().toString();
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try {
            if(e.id==0)e.id=db.insertOrThrow("events",null,values(e));else {
                Event old=find(e.id);if(old==null)throw new IllegalArgumentException("日程不存在");
                if(!old.start.equals(e.start)||!old.repeat.equals(e.repeat)){db.delete("completed","event_id=?",new String[]{""+e.id});db.delete("delivered","event_id=?",new String[]{""+e.id});}
                else if(old.reminder!=e.reminder)db.delete("delivered","event_id=?",new String[]{""+e.id});
                db.update("events",values(e),"id=?",new String[]{""+e.id});
            }
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
    public void delete(long id) {getWritableDatabase().delete("events","id=?",new String[]{""+id});}
    private Set<String> keys(String table) {Set<String> keys=new HashSet<>();try(Cursor c=getReadableDatabase().query(table,new String[]{"occurrence_key"},null,null,null,null,null)){while(c.moveToNext())keys.add(c.getString(0));}return keys;}
    public Set<String> completions() { return keys("completed"); }
    public Set<String> delivered() { return keys("delivered"); }
    private long keyId(String key) {
        try{String[] pieces=key.split("@",2);long id=Long.parseLong(pieces[0]);LocalDateTime.parse(pieces[1]);if(id<=0)throw new IllegalArgumentException();return id;}catch(RuntimeException ex){throw new IllegalArgumentException("无效的日程记录",ex);}
    }
    private void record(String table,String key) {long id=keyId(key);if(find(id)==null)return;ContentValues v=new ContentValues();v.put("occurrence_key",key);v.put("event_id",id);getWritableDatabase().insertWithOnConflict(table,null,v,SQLiteDatabase.CONFLICT_IGNORE);}
    public void markDelivered(String key) {record("delivered",key);}
    public void setCompleted(String key,boolean done) {if(done)record("completed",key);else getWritableDatabase().delete("completed","occurrence_key=?",new String[]{key});}
    public String exportJson() {
        try {
            JSONObject root=new JSONObject().put("version",3).put("app","轻日").put("exportedAt",LocalDateTime.now().toString());JSONArray array=new JSONArray();
            for(Event e:list())array.put(new JSONObject().put("id",e.id).put("title",e.title).put("note",e.note).put("location",e.location).put("category",e.category).put("priority",e.priority).put("start",e.start.toString()).put("end",e.end.toString()).put("repeat",e.repeat).put("reminder",e.reminder));
            JSONArray study=new JSONArray();for(TimeRecord r:studyRecords())study.put(TimeRecordStore.json(r));
            Set<String> completed=completions(),delivered=delivered();
            if(array.length()>MAX_EVENTS||study.length()>MAX_RECORDS||completed.size()>MAX_RECORDS||delivered.size()>MAX_RECORDS)throw new IllegalStateException("备份记录超过支持范围，未导出");
            String output=root.put("events",array).put("completed",new JSONArray(completed)).put("delivered",new JSONArray(delivered)).put("study",study).toString(2);
            if(output.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>MAX_BACKUP_BYTES)throw new IllegalStateException("备份超过64MB，未导出");return output;
        }catch(JSONException ex){throw new IllegalStateException("备份生成失败",ex);}
    }
    public void importJson(String value) {
        final List<Event> incoming=new ArrayList<>();final Set<Long> ids=new HashSet<>();final Set<String> done=new HashSet<>(),sent=new HashSet<>();
        final List<TimeRecord> study=new ArrayList<>();boolean restoreStudy=false;
        try {
            if(value==null||value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>MAX_BACKUP_BYTES)throw new IllegalArgumentException("备份过大");
            JSONObject root=new JSONObject(value);int version=root.getInt("version");if(version<1||version>3)throw new IllegalArgumentException("不支持的备份版本");restoreStudy=version>=2;JSONArray array=root.getJSONArray("events");if(array.length()>MAX_EVENTS)throw new IllegalArgumentException("日程过多");
            for(int i=0;i<array.length();i++) {
                JSONObject o=array.getJSONObject(i);Event e=new Event();e.id=o.getLong("id");e.title=o.getString("title");e.note=o.getString("note");e.location=o.getString("location");e.category=o.getString("category");e.priority=o.getInt("priority");e.start=LocalDateTime.parse(o.getString("start"));e.end=LocalDateTime.parse(o.getString("end"));e.repeat=o.getString("repeat");e.reminder=o.getInt("reminder");
                if(e.id<=0||e.id>=Integer.MAX_VALUE||!ids.add(e.id)||!ScheduleEngine.validate(e).isEmpty())throw new IllegalArgumentException("无效的日程数据");e.revision=UUID.randomUUID().toString();incoming.add(e);
            }
            parseKeys(root.getJSONArray("completed"),ids,done);if(version>=2||root.has("delivered"))parseKeys(root.getJSONArray("delivered"),ids,sent);
            if(restoreStudy){JSONArray history=root.getJSONArray("study");if(history.length()>MAX_RECORDS)throw new IllegalArgumentException("学习记录过多");Set<String> seen=new HashSet<>();for(int i=0;i<history.length();i++){TimeRecord record=TimeRecordStore.parse(history.getJSONObject(i),version);if(!seen.add(record.token))throw new IllegalArgumentException("重复学习记录");study.add(record);}}
        }catch(JSONException|RuntimeException ex){throw new IllegalArgumentException("备份格式不正确",ex);}
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try {
            db.delete("events",null,null);
            if(restoreStudy){db.delete("study_sessions",null,null);for(TimeRecord record:study)saveTimeRecord(record);}
            for(Event e:incoming){ContentValues v=values(e);v.put("id",e.id);db.insertOrThrow("events",null,v);}
            for(String key:done)record("completed",key);for(String key:sent)record("delivered",key);db.setTransactionSuccessful();
        }catch(RuntimeException ex){throw new IllegalArgumentException("恢复失败，原数据已保留",ex);}finally{db.endTransaction();}
    }
    private void parseKeys(JSONArray array,Set<Long> ids,Set<String> output) throws JSONException {
        if(array.length()>MAX_RECORDS)throw new IllegalArgumentException("完成记录过多");for(int i=0;i<array.length();i++){String key=array.getString(i);if(!ids.contains(keyId(key)))throw new IllegalArgumentException("完成记录不属于任何日程");output.add(key);}
    }
}

package cn.qingri.app;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.*;
import java.util.*;
import org.json.*;

final class TimeRecordStore {
    static JSONArray segments(TimeRecord r)throws JSONException{JSONArray a=new JSONArray();for(TimeRecord.Segment s:r.segments)a.put(new JSONObject().put("start",s.start).put("end",s.end).put("duration",s.duration));return a;}
    static void readSegments(TimeRecord r,JSONArray array)throws JSONException{
        for(int i=0;i<array.length();i++){JSONObject o=array.getJSONObject(i);r.segments.add(new TimeRecord.Segment(o.getLong("start"),o.getLong("end"),o.getLong("duration")));}
    }
    static void validate(TimeRecord r){
        if(r.token==null||r.token.isEmpty()||r.token.length()>100||r.label==null||r.label.length()>80||r.start<0||r.end<r.start||r.end>7258118400000L||r.duration<=0||r.duration>Long.MAX_VALUE/EventStore.MAX_RECORDS)throw new IllegalArgumentException("无效的学习记录");
        long total=0;for(TimeRecord.Segment s:r.segments){if(s.start<r.start||s.end>r.end||s.end<s.start||s.duration<=0)throw new IllegalArgumentException("无效的学习片段");total=Math.addExact(total,s.duration);}
        if(!r.segments.isEmpty()&&total!=r.duration)throw new IllegalArgumentException("有效片段与总时长不符");
    }
    static void save(SQLiteDatabase db,TimeRecord r){
        validate(r);ContentValues v=new ContentValues();v.put("session_token",r.token);v.put("start_ms",r.start);v.put("end_ms",r.end);v.put("duration_ms",r.duration);v.put("label",r.label.trim());
        v.put("estimated",r.estimated?1:0);try{v.put("segments_json",segments(r).toString());}catch(JSONException ex){throw new IllegalArgumentException(ex);}
        long id=db.insertWithOnConflict("study_sessions",null,v,SQLiteDatabase.CONFLICT_IGNORE);
        if(id<0)try(Cursor c=db.query("study_sessions",new String[]{"id"},"session_token=?",new String[]{r.token},null,null,null)){if(!c.moveToFirst())throw new SQLiteException("无法保存学习记录");}
    }
    static List<TimeRecord> list(SQLiteDatabase db){return list(db,null,null);}
    static List<TimeRecord> list(SQLiteDatabase db,String where,String[] args){
        List<TimeRecord> result=new ArrayList<>();try(Cursor c=db.query("study_sessions",null,where,args,null,null,"start_ms DESC,id DESC")){
            while(c.moveToNext()){TimeRecord r=new TimeRecord();r.token=c.getString(c.getColumnIndexOrThrow("session_token"));r.label=c.getString(c.getColumnIndexOrThrow("label"));r.start=c.getLong(c.getColumnIndexOrThrow("start_ms"));r.end=c.getLong(c.getColumnIndexOrThrow("end_ms"));r.duration=c.getLong(c.getColumnIndexOrThrow("duration_ms"));r.estimated=c.getInt(c.getColumnIndexOrThrow("estimated"))!=0;try{readSegments(r,new JSONArray(c.getString(c.getColumnIndexOrThrow("segments_json"))));}catch(JSONException ex){throw new SQLiteException("学习片段格式损坏");}result.add(r);}
        }return result;
    }
    static JSONObject json(TimeRecord r)throws JSONException{return new JSONObject().put("token",r.token).put("start",r.start).put("end",r.end).put("duration",r.duration).put("label",r.label).put("estimated",r.estimated).put("segments",segments(r));}
    static TimeRecord parse(JSONObject o,int version)throws JSONException{
        TimeRecord r=new TimeRecord();r.token=o.has("token")?o.getString("token"):UUID.randomUUID().toString();r.start=o.getLong("start");r.end=o.getLong("end");r.duration=o.getLong("duration");
        if(version>=3){r.label=o.getString("label");readSegments(r,o.getJSONArray("segments"));r.estimated=o.optBoolean("estimated",r.segments.isEmpty());}else r.estimated=true;validate(r);return r;
    }
}

package cn.qingri.app.tests;

import android.app.*;
import android.content.*;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import cn.qingri.app.*;
import java.time.*;
import java.util.*;

public class SmokeInstrumentation extends Instrumentation {
    int assertions;
    String mode;
    void check(boolean ok, String message) { assertions++; if (!ok) throw new AssertionError(message); Bundle update=new Bundle();update.putString("stream","ok "+assertions+": "+message+"\n");sendStatus(0,update); }
    @Override public void onCreate(Bundle args) { super.onCreate(args);mode=args==null?"storage":args.getString("mode","storage"); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            if("ui".equals(mode)){uiTests();result.putString("stream","PASS: "+assertions+" Android UI and alarm assertions\n");finish(Activity.RESULT_OK,result);return;}
            if("regression".equals(mode)){regressionTests();result.putString("stream","PASS: "+assertions+" review regression assertions\n");finish(Activity.RESULT_OK,result);return;}
            if("channel".equals(mode)){Context c=getTargetContext();NotificationManager nm=(NotificationManager)c.getSystemService(Context.NOTIFICATION_SERVICE);check(nm.areNotificationsEnabled()&&nm.getNotificationChannel("qingri_schedule").getImportance()==0,"Only the reminder channel is blocked");check(!ReminderScheduler.notificationsAllowed(c),"Blocked channel is reported as disabled");try(EventStore db=new EventStore(c)){Event e=new Event();e.title="渠道关闭验证";e.start=LocalDateTime.now().plusSeconds(1).withNano(0);e.end=e.start.plusMinutes(2);e.reminder=0;db.save(e);ReminderScheduler.reschedule(c);Thread.sleep(1500);check(!db.delivered().contains(e.id+"@"+e.start),"Blocked channel does not consume a reminder");db.delete(e.id);}result.putString("stream","PASS: "+assertions+" channel-permission assertions\n");finish(Activity.RESULT_OK,result);return;}
            if("denied".equals(mode)) {check(!ReminderScheduler.notificationsAllowed(getTargetContext()),"Notification denial visible");check(!ReminderScheduler.exactAllowed(getTargetContext()),"Exact alarm denial visible");ReminderScheduler.reschedule(getTargetContext());result.putString("stream","PASS: "+assertions+" denied-permission assertions\n");finish(Activity.RESULT_OK,result);return;}
            Context context = getTargetContext().createDeviceProtectedStorageContext();
            context.deleteDatabase("qingri.db");
            EventStore store = new EventStore(context);
            Event e = new Event(); e.title="数据库测试"; e.note="中文备注"; e.location="图书馆";
            e.start=LocalDateTime.parse("2026-11-02T09:00"); e.end=e.start.plusHours(1); e.repeat="daily";
            store.save(e);
            check(e.id>0,"Insert generates ID");
            check(store.list().size()==1,"Saved event appears");
            Event loaded=store.find(e.id);
            check(loaded.note.equals("中文备注") && loaded.location.equals("图书馆"),"Unicode fields round trip");
            String key=e.id+"@2026-11-02T09:00";
            store.setCompleted(key,true);
            check(store.completions().contains(key),"Occurrence completion persists");
            loaded.title="修改标题"; store.save(loaded);
            check(store.find(e.id).title.equals("修改标题"),"Edit persists");
            store.saveStudy(1000,2000,500);
            check(store.studyStats(0,10000)[0]==500&&store.studyStats(0,10000)[1]==1,"Study duration and count persist");
            String backup=store.exportJson();
            check(new org.json.JSONObject(backup).getJSONArray("study").length()==1,"Backup includes completed study sessions");
            store.delete(e.id);
            check(store.list().isEmpty() && store.completions().isEmpty(),"Series delete removes completion state");
            store.importJson(backup);
            check(store.list().size()==1 && store.completions().contains(key),"Backup restores data and completion");
            check(store.studyStats(0,10000)[0]==500&&store.studyStats(0,10000)[1]==1,"Backup restores study exactly once");
            try { store.importJson("{\"version\":1,\"events\":[{\"title\":\"损坏\"}],\"completed\":[]}"); throw new AssertionError("Invalid backup was accepted"); }
            catch (IllegalArgumentException expected) { check(store.list().size()==1,"Bad backup keeps existing data"); }
            try { store.importJson("{\"version\":99,\"events\":[],\"completed\":[]}"); throw new AssertionError("Unknown version accepted"); }
            catch (IllegalArgumentException expected) { check(store.list().size()==1,"Unknown version keeps existing data"); }
            store.setCompleted(key,false);
            check(!store.completions().contains(key),"Undo completion persists");
            store.setCompleted(key,true);store.markDelivered(key);loaded=store.find(e.id);loaded.start=loaded.start.plusHours(1);loaded.end=loaded.end.plusHours(1);store.save(loaded);
            check(store.completions().isEmpty()&&store.delivered().isEmpty(),"Changing schedule clears old occurrence state");
            String duplicate=backup.replace("\"events\": [","\"events\": [");
            org.json.JSONObject bad=new org.json.JSONObject(backup);bad.getJSONArray("events").put(bad.getJSONArray("events").getJSONObject(0));
            try {store.importJson(bad.toString());throw new AssertionError("Duplicate IDs accepted");}catch(IllegalArgumentException expected){check(store.list().size()==1,"Duplicate IDs preserve existing data");}
            store.close();
            result.putString("stream","PASS: "+assertions+" Android storage assertions\n");
            finish(Activity.RESULT_OK,result);
        } catch (Throwable ex) {
            result.putString("stream","FAIL: "+android.util.Log.getStackTraceString(ex)); finish(Activity.RESULT_CANCELED,result);
        }
    }
    View byText(View root,String text) {
        if(root instanceof TextView&&((TextView)root).getText().toString().equals(text))return root;
        if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){View result=byText(((ViewGroup)root).getChildAt(i),text);if(result!=null)return result;}
        return null;
    }
    View byDescription(View root,String text) {
        if(text.contentEquals(root.getContentDescription()==null?"":root.getContentDescription()))return root;
        if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){View result=byDescription(((ViewGroup)root).getChildAt(i),text);if(result!=null)return result;}
        return null;
    }
    Activity launch(Class<?> type) {Activity a=startActivitySync(new Intent(getTargetContext(),type).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));waitForIdleSync();return a;}
    void clickText(Activity a,String text){runOnMainSync(()->{View v=byText(a.getWindow().getDecorView(),text);check(v!=null,"Visible action: "+text);v.performClick();});waitForIdleSync();}
    void clickDescription(Activity a,String text){runOnMainSync(()->{View v=byDescription(a.getWindow().getDecorView(),text);check(v!=null,"Visible action: "+text);v.performClick();});waitForIdleSync();}
    boolean activeNotification(long id){for(android.service.notification.StatusBarNotification n:((NotificationManager)getTargetContext().getSystemService(Context.NOTIFICATION_SERVICE)).getActiveNotifications())if(n.getId()==(int)id)return true;return false;}
    void uiTests() throws Exception {
        Context c=getTargetContext();c.deleteDatabase("qingri.db");
        Activity editor=launch(EditorActivity.class);
        runOnMainSync(()->{((EditText)editor.findViewById(101)).setText("阅读与复习");((Spinner)editor.findViewById(102)).setSelection(1);((Spinner)editor.findViewById(104)).setSelection(1);((EditText)editor.findViewById(107)).setText("每天留一点时间给自己");});
        clickText(editor,"保存日程");
        EventStore data=new EventStore(c);check(data.list().size()==1,"Editor creates a persisted event");Event e=data.list().get(0);check(e.title.equals("阅读与复习")&&e.category.equals("学习")&&e.repeat.equals("daily"),"Editor saves selected fields");
        Activity home=launch(MainActivity.class);final Activity initialHome=home;runOnMainSync(()->check(byText(initialHome.getWindow().getDecorView(),"阅读与复习")!=null,"Agenda displays actual data"));
        clickDescription(home,"完成：阅读与复习");check(data.completions().contains(e.id+"@"+e.start),"Completion button saves this occurrence");
        clickDescription(home,"取消完成：阅读与复习");check(data.completions().isEmpty(),"Completion button can undo");
        Activity edit=startActivitySync(new Intent(c,EditorActivity.class).putExtra("id",e.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));waitForIdleSync();runOnMainSync(()->((EditText)edit.findViewById(101)).setText("阅读与复习 · 已更新"));clickText(edit,"保存日程");check(data.find(e.id).title.equals("阅读与复习 · 已更新"),"Editor updates existing event");
        home=launch(MainActivity.class);final Activity main=home;
        clickDescription(main,"搜索日程");runOnMainSync(()->((EditText)main.findViewById(108)).setText("no_such_event_987"));waitForIdleSync();runOnMainSync(()->check(byText(main.getWindow().getDecorView(),"没有找到相关日程")!=null,"Search excludes nonmatching events"));
        runOnMainSync(()->((EditText)main.findViewById(108)).setText("已更新"));waitForIdleSync();runOnMainSync(()->check(byText(main.getWindow().getDecorView(),"阅读与复习 · 已更新")!=null,"Search finds matching recurring event"));
        clickDescription(main,"搜索日程");runOnMainSync(()->check(byText(main.getWindow().getDecorView(),"阅读与复习 · 已更新")!=null,"Closing search restores unfiltered agenda"));
        clickDescription(main,"月历");runOnMainSync(()->check(byDescription(main.getWindow().getDecorView(),LocalDate.now()+"，有日程")!=null,"Calendar marks actual event dates"));
        clickDescription(main,"设置");runOnMainSync(()->check(byText(main.getWindow().getDecorView(),"通知权限")!=null,"Settings exposes permission status"));
        Activity focus=launch(FocusActivity.class);clickText(focus,"开始学习");Thread.sleep(1200);clickText(focus,"暂停");
        check(!StudySession.active(c),"Study can be paused manually");
        long paused=new StudySession(c).timer.elapsed(android.os.SystemClock.elapsedRealtime());Thread.sleep(300);check(new StudySession(c).timer.elapsed(android.os.SystemClock.elapsedRealtime())==paused,"Paused study excludes idle time");
        clickText(focus,"继续学习");check(StudySession.active(c),"Study can be resumed manually");Thread.sleep(400);clickText(focus,"结束本次");check(!StudySession.active(c),"Study ends only after manual action");check(data.studyStats(0,Long.MAX_VALUE)[1]==1&&data.studyStats(0,Long.MAX_VALUE)[0]>=1500,"Manual end persists effective study duration");runOnMainSync(()->focus.finish());
        Event alarmEvent=new Event();alarmEvent.title="自动化提醒验证";alarmEvent.start=LocalDateTime.now().plusSeconds(5).withNano(0);alarmEvent.end=alarmEvent.start.plusMinutes(5);alarmEvent.reminder=0;data.save(alarmEvent);
        Event twin=new Event();twin.title="同时提醒验证";twin.start=alarmEvent.start;twin.end=alarmEvent.end;twin.reminder=0;data.save(twin);
        ReminderScheduler.reschedule(c);long deadline=System.currentTimeMillis()+20000;String key=alarmEvent.id+"@"+alarmEvent.start;
        while((!data.delivered().contains(key)||!data.delivered().contains(twin.id+"@"+twin.start))&&System.currentTimeMillis()<deadline)Thread.sleep(300);
        check(data.delivered().contains(key),"Real AlarmManager broadcast records delivery");check(activeNotification(alarmEvent.id),"Real notification reaches system tray");
        check(data.delivered().contains(twin.id+"@"+twin.start)&&activeNotification(twin.id),"Simultaneous reminders are both delivered");
        for(android.service.notification.StatusBarNotification n:((NotificationManager)c.getSystemService(Context.NOTIFICATION_SERVICE)).getActiveNotifications())if(n.getId()==(int)alarmEvent.id)n.getNotification().actions[0].actionIntent.send();
        deadline=System.currentTimeMillis()+5000;while(!data.completions().contains(key)&&System.currentTimeMillis()<deadline)Thread.sleep(100);
        check(data.completions().contains(key),"Notification action completes the occurrence");check(!activeNotification(alarmEvent.id),"Completion action dismisses notification");data.delete(alarmEvent.id);data.delete(twin.id);
        Event stale=new Event();stale.title="旧提醒取消验证";stale.start=LocalDateTime.now().plusSeconds(5).withNano(0);stale.end=stale.start.plusMinutes(10);stale.reminder=0;data.save(stale);ReminderScheduler.reschedule(c);
        stale.start=stale.start.plusHours(2);stale.end=stale.end.plusHours(2);data.save(stale);ReminderScheduler.reschedule(c);Thread.sleep(6500);check(!activeNotification(stale.id)&&data.delivered().isEmpty(),"Editing cancels old reminder");data.delete(stale.id);
        // The following fixtures live only inside the test emulator, never in the APK.
        e=data.find(e.id);e.title="阅读与复习";e.start=LocalDate.now().atTime(9,0);e.end=e.start.plusMinutes(45);e.reminder=-1;data.save(e);data.setCompleted(e.id+"@"+e.start,true);
        Event meeting=new Event();meeting.title="小组项目讨论";meeting.start=LocalDate.now().atTime(15,0);meeting.end=meeting.start.plusHours(1);meeting.category="学习";meeting.priority=2;meeting.location="图书馆二楼";meeting.reminder=-1;data.save(meeting);
        Event walk=new Event();walk.title="散步，给自己充充电";walk.start=LocalDate.now().atTime(19,30);walk.end=walk.start.plusMinutes(30);walk.reminder=-1;data.save(walk);ReminderScheduler.reschedule(c);data.close();
        launch(MainActivity.class);
    }
    interface CheckedTask {void run() throws Exception;}
    void regressionTests() throws Exception {
        Context c=getTargetContext();java.util.List<String> failures=new ArrayList<>();
        CheckedTask[] tasks={
            ()->{c.deleteDatabase("qingri.db");c.getSharedPreferences("study",0).edit().clear().commit();StudySession a=new StudySession(c);a.start();Thread.sleep(30);a.checkpoint();StudySession b=new StudySession(c);a.finish();b.finish();try(EventStore db=new EventStore(c)){check(db.studyStats(0,Long.MAX_VALUE)[1]==1,"Stale study completion cannot double-count");}},
            ()->{c.deleteDatabase("qingri.db");try(EventStore db=new EventStore(c)){char[] chars=new char[10000];Arrays.fill(chars,'中');for(int i=0;i<220;i++){Event e=new Event();e.title="备份 "+i;e.start=LocalDateTime.now().plusDays(1);e.end=e.start.plusHours(1);e.note=new String(chars);e.reminder=-1;db.save(e);}String backup=db.exportJson();db.importJson(backup);check(db.list().size()==220,"Own large Unicode backup can be restored");}},
            ()->{c.deleteDatabase("qingri.db");try(EventStore db=new EventStore(c)){Event e=new Event();e.title="原数据";e.start=LocalDateTime.now().plusDays(1);e.end=e.start.plusHours(1);db.save(e);org.json.JSONObject bad=new org.json.JSONObject(db.exportJson());bad.put("delivered",new org.json.JSONObject());bad.getJSONArray("events").getJSONObject(0).put("title","损坏备份替换");try{db.importJson(bad.toString());throw new AssertionError("Wrong-typed delivery history accepted");}catch(IllegalArgumentException expected){check(db.find(e.id).title.equals("原数据"),"Malformed history cannot replace live data");}}},
            ()->{c.deleteDatabase("qingri.db");try(EventStore db=new EventStore(c)){for(int i=0;i<501;i++){Event e=new Event();e.title="大量提醒 "+i;e.start=LocalDateTime.now().plusHours(2).plusMinutes(i);e.end=e.start.plusMinutes(10);db.save(e);}ReminderScheduler.reschedule(c);check(db.list().size()==501,"501 reminders do not exceed system alarm limit");} },
            ()->{c.deleteDatabase("qingri.db");try(EventStore db=new EventStore(c)){Event e=new Event();e.title="旧通知";e.start=LocalDateTime.now().plusSeconds(3).withNano(0);e.end=e.start.plusMinutes(30);e.reminder=0;db.save(e);ReminderScheduler.reschedule(c);long deadline=System.currentTimeMillis()+15000;while(!activeNotification(e.id)&&System.currentTimeMillis()<deadline)Thread.sleep(100);PendingIntent oldAction=null;for(android.service.notification.StatusBarNotification n:((NotificationManager)c.getSystemService(Context.NOTIFICATION_SERVICE)).getActiveNotifications())if(n.getId()==(int)e.id)oldAction=n.getNotification().actions[0].actionIntent;check(oldAction!=null,"Stale-action fixture received a real notification");e.title="替换后的新日程";db.save(e);ReminderScheduler.reschedule(c);check(!activeNotification(e.id),"Editing dismisses stale notification");oldAction.send();Thread.sleep(200);check(!db.completions().contains(e.id+"@"+e.start),"Stale action cannot complete replacement event");}},
        };
        for(int i=0;i<tasks.length;i++)try{tasks[i].run();}catch(Throwable ex){failures.add("case "+(i+1)+": "+ex.toString());Bundle update=new Bundle();update.putString("stream","regression failure "+failures.get(failures.size()-1)+"\n");sendStatus(0,update);}
        if(!failures.isEmpty())throw new AssertionError(failures.toString());
    }
}

package cn.qingri.app;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.*;
import org.json.*;
import java.time.*;
import java.util.*;

public final class ReminderScheduler {
    static final String CHANNEL="qingri_schedule";
    static PendingIntent queueIntent(Context c) {
        Intent i=new Intent(c,ReminderReceiver.class).setAction("cn.qingri.QUEUE").setData(Uri.parse("qingri://queue"));
        return PendingIntent.getBroadcast(c,0,i,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    }
    public static boolean notificationsAllowed(Context c) {
        NotificationManager manager=(NotificationManager)c.getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationChannel channel=manager.getNotificationChannel(CHANNEL);
        return manager.areNotificationsEnabled()&&(channel==null||channel.getImportance()!=NotificationManager.IMPORTANCE_NONE)
            &&(Build.VERSION.SDK_INT<33||c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED);
    }
    public static boolean exactAllowed(Context c) {return Build.VERSION.SDK_INT<31||((AlarmManager)c.getSystemService(Context.ALARM_SERVICE)).canScheduleExactAlarms();}
    static void channel(Context c) {
        NotificationChannel channel=new NotificationChannel(CHANNEL,"日程提醒",NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription("你安排的日程与测试提醒");channel.enableVibration(true);
        ((NotificationManager)c.getSystemService(Context.NOTIFICATION_SERVICE)).createNotificationChannel(channel);
    }
    static void alarm(Context c,long at,PendingIntent pi) {
        AlarmManager manager=(AlarmManager)c.getSystemService(Context.ALARM_SERVICE);
        try {if(exactAllowed(c))manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,at,pi);else manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,at,pi);}
        catch(SecurityException ex){manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,at,pi);}
    }
    static List<AlarmPlan.Entry> queued(Context c) {
        List<AlarmPlan.Entry> list=new ArrayList<>();
        try {JSONArray array=new JSONArray(c.getSharedPreferences("alarms",0).getString("queue","[]"));
            for(int n=0;n<Math.min(EventStore.MAX_EVENTS,array.length());n++) {JSONObject o=array.getJSONObject(n);AlarmPlan.Entry e=new AlarmPlan.Entry();e.id=o.getLong("id");e.revision=o.getString("revision");e.start=LocalDateTime.parse(o.getString("start"));e.trigger=LocalDateTime.parse(o.getString("trigger"));e.at=o.getLong("at");list.add(e);}
        }catch(RuntimeException|JSONException ex){list.clear();}return list;
    }
    public static void reschedule(Context c){reschedule(c,false);}
    public static synchronized void reschedule(Context c,boolean force) {
        channel(c);AlarmManager manager=(AlarmManager)c.getSystemService(Context.ALARM_SERVICE);
        SharedPreferences prefs=c.getSharedPreferences("alarms",0);
        if(prefs.getInt("protocol",0)!=2){
            if(Build.VERSION.SDK_INT>=34)manager.cancelAll();
            else for(String value:prefs.getStringSet("ids",Collections.emptySet()))try {
                Intent old=new Intent(c,ReminderReceiver.class).setAction("cn.qingri.REMIND").setData(Uri.parse("qingri://reminder/"+Long.parseLong(value)));
                manager.cancel(PendingIntent.getBroadcast(c,0,old,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT));
            }catch(NumberFormatException ignored){}
            prefs.edit().putInt("protocol",2).commit();force=true;
        }
        List<AlarmPlan.Entry> previous=queued(c),plan;
        try(EventStore store=new EventStore(c)) {
            List<Event> events=store.list();Map<Long,Event> byId=new HashMap<>();for(Event e:events)byId.put(e.id,e);
            NotificationManager notifications=(NotificationManager)c.getSystemService(Context.NOTIFICATION_SERVICE);
            for(android.service.notification.StatusBarNotification n:notifications.getActiveNotifications()) {
                String revision=n.getNotification().extras.getString("qingriRevision");
                if(revision!=null){Event event=byId.get((long)n.getId());if(event==null||!revision.equals(event.revision))notifications.cancel(n.getId());}
            }
            Set<String> excluded=store.completions();excluded.addAll(store.delivered());
            plan=notificationsAllowed(c)?AlarmPlan.build(events,excluded,previous,LocalDateTime.now(),ZoneId.systemDefault()):new ArrayList<>();
        }
        JSONArray serialized=new JSONArray();
        try {for(AlarmPlan.Entry e:plan)serialized.put(new JSONObject().put("id",e.id).put("revision",e.revision).put("start",e.start.toString()).put("trigger",e.trigger.toString()).put("at",e.at));}
        catch(JSONException ex){throw new IllegalStateException(ex);}
        String signature=plan.isEmpty()?"":plan.get(0).key()+"@"+plan.get(0).revision+"@"+plan.get(0).at+"@"+exactAllowed(c);
        boolean keep=!force&&prefs.getBoolean("armed",false)&&signature.equals(prefs.getString("signature",""));
        prefs.edit().putString("queue",serialized.toString()).putString("signature",signature).putBoolean("armed",!plan.isEmpty()).commit();
        if(!keep){manager.cancel(queueIntent(c));if(!plan.isEmpty())alarm(c,plan.get(0).at,queueIntent(c));}
    }
    public static void test(Context c) {
        channel(c);Intent i=new Intent(c,ReminderReceiver.class).setAction("cn.qingri.TEST");
        alarm(c,System.currentTimeMillis()+10000,PendingIntent.getBroadcast(c,99,i,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE));
    }
    static boolean notify(Context c,Occurrence o) {
        if(!notificationsAllowed(c))return false;channel(c);
        Intent open=new Intent(c,MainActivity.class).putExtra("date",o==null?LocalDate.now().toString():o.start.toLocalDate().toString());
        PendingIntent content=PendingIntent.getActivity(c,o==null?-999:(int)o.event.id,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder=new Notification.Builder(c,CHANNEL).setSmallIcon(R.drawable.ic_notification).setColor(Ui.GREEN)
            .setContentTitle(o==null?"轻日提醒已就绪":o.event.title)
            .setContentText(o==null?"测试通知已送达。你可以继续安排今天的日程。":o.start.format(java.time.format.DateTimeFormatter.ofPattern("M月d日 HH:mm"))+(o.event.location.isEmpty()?"":" · "+o.event.location))
            .setAutoCancel(true).setContentIntent(content).setCategory(Notification.CATEGORY_EVENT);
        if(o!=null) {
            Bundle extras=new Bundle();extras.putString("qingriRevision",o.event.revision);builder.addExtras(extras);
            Intent complete=new Intent(c,ReminderReceiver.class).setAction("cn.qingri.COMPLETE")
                .setData(Uri.parse("qingri://complete/"+Uri.encode(o.key())+"/"+o.event.revision))
                .putExtra("id",o.event.id).putExtra("start",o.start.toString()).putExtra("revision",o.event.revision);
            PendingIntent action=PendingIntent.getBroadcast(c,0,complete,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
            builder.addAction(new Notification.Action.Builder(null,"完成这一次",action).build());
            if(!o.event.note.isEmpty())builder.setStyle(new Notification.BigTextStyle().bigText(o.start.format(java.time.format.DateTimeFormatter.ofPattern("M月d日 HH:mm"))+"\n"+o.event.note));
        }
        ((NotificationManager)c.getSystemService(Context.NOTIFICATION_SERVICE)).notify(o==null?-999999:(int)o.event.id,builder.build());return true;
    }
}

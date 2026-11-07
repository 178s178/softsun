package cn.qingri.app;

import android.app.*;
import android.content.*;
import java.time.*;
import java.util.*;

public class ReminderReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c,Intent intent) {
        if("cn.qingri.TEST".equals(intent.getAction())){ReminderScheduler.notify(c,null);return;}
        try(EventStore store=new EventStore(c)) {
            if("cn.qingri.COMPLETE".equals(intent.getAction())) {
                long id=intent.getLongExtra("id",0);Event e=store.find(id);
                if(e!=null&&e.revision.equals(intent.getStringExtra("revision"))) {
                    LocalDateTime start=LocalDateTime.parse(intent.getStringExtra("start"));
                    for(Occurrence o:ScheduleEngine.occurrences(e,start.toLocalDate(),start.toLocalDate(),store.completions()))if(o.start.equals(start)){store.setCompleted(o.key(),true);((NotificationManager)c.getSystemService(Context.NOTIFICATION_SERVICE)).cancel((int)id);break;}
                }
            }else if("cn.qingri.QUEUE".equals(intent.getAction())) {
                c.getSharedPreferences("alarms",0).edit().putBoolean("armed",false).commit();
                Set<String> excluded=store.completions();excluded.addAll(store.delivered());long now=System.currentTimeMillis();
                for(AlarmPlan.Entry p:ReminderScheduler.queued(c))if(p.at<=now&&!excluded.contains(p.key())&&!p.start.isBefore(LocalDateTime.now().minusDays(1))) {
                    Event e=store.find(p.id);if(e==null||!e.revision.equals(p.revision))continue;
                    for(Occurrence o:ScheduleEngine.occurrences(e,p.start.toLocalDate(),p.start.toLocalDate(),store.completions()))if(o.start.equals(p.start)){
                        if(ReminderScheduler.notify(c,o)){store.markDelivered(o.key());excluded.add(o.key());}break;
                    }
                }
            }
        }catch(RuntimeException ex){android.util.Log.e("Qingri","Unable to deliver reminder",ex);}
        ReminderScheduler.reschedule(c);
    }
}

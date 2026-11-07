package cn.qingri.app;

import java.time.*;
import java.util.*;

/** A bounded persistent queue is driven by a single system alarm. */
public final class AlarmPlan {
    public static class Entry {
        public long id,at;
        public String revision;
        public LocalDateTime start,trigger;
        public String key(){return id+"@"+start;}
    }
    public static List<Entry> build(List<Event> events,Set<String> excluded,List<Entry> previous,LocalDateTime now,ZoneId zone){
        Map<Long,Entry> old=new HashMap<>();for(Entry p:previous)old.put(p.id,p);
        List<Entry> plan=new ArrayList<>();
        for(Event e:events){
            if(e.reminder<0||!ScheduleEngine.validate(e).isEmpty())continue;
            Entry p=old.get(e.id),next=null;
            if(p!=null&&p.revision.equals(e.revision)&&!excluded.contains(p.key())&&!p.start.isBefore(now.minusDays(1)))next=p;
            else{Occurrence occurrence=ScheduleEngine.nextReminder(e,now,excluded);if(occurrence!=null){next=new Entry();next.id=e.id;next.revision=e.revision;next.start=occurrence.start;next.trigger=ScheduleEngine.reminderAt(occurrence,now);}}
            if(next!=null){next.at=next.trigger.atZone(zone).toInstant().toEpochMilli();plan.add(next);}
        }
        Collections.sort(plan,(a,b)->{int cmp=Long.compare(a.at,b.at);return cmp==0?Long.compare(a.id,b.id):cmp;});
        return new ArrayList<>(plan.subList(0,Math.min(20000,plan.size())));
    }
}

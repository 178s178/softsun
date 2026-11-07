package cn.qingri.app;

import java.time.*;
import java.util.*;

public class CoreTests {
    static int count;
    static void eq(Object actual, Object expected, String reason) {
        count++;
        if (!Objects.equals(actual, expected)) throw new AssertionError(reason + ": expected " + expected + ", got " + actual);
    }
    static Event event(String at, String repeat) {
        Event e = new Event(); e.id = 7; e.title = "阅读";
        e.start = LocalDateTime.parse(at); e.end = e.start.plusHours(1); e.repeat = repeat; return e;
    }
    static List<Occurrence> between(Event e, String from, String to) {
        return ScheduleEngine.occurrences(e, LocalDate.parse(from), LocalDate.parse(to), Collections.emptySet());
    }
    public static void main(String[] args) {
        Event e = event("2026-11-02T09:00", "none");
        e.title = "  "; eq(ScheduleEngine.validate(e).isEmpty(), false, "Blank title rejected");
        e.title = "阅读"; e.end = e.start; eq(ScheduleEngine.validate(e).isEmpty(), false, "Zero duration rejected");
        e.end = e.start.plusHours(1); eq(ScheduleEngine.validate(e), "", "Valid event accepted");
        eq(between(e,"2026-11-02","2026-11-02").size(),1,"Single event visible");
        eq(between(e,"2026-11-03","2026-11-03").size(),0,"Single event not repeated");
        e = event("2026-11-02T23:30", "none");
        eq(between(e,"2026-11-03","2026-11-03").size(),1,"Overnight event intersects next day");
        e.end = LocalDateTime.parse("2026-11-03T00:00");
        eq(between(e,"2026-11-03","2026-11-03").size(),0,"Midnight end is exclusive");
        e = event("2026-11-02T09:00", "daily");
        eq(between(e,"2026-11-01","2026-11-04").size(),3,"Daily starts at anchor");
        e.repeat="weekdays";
        eq(between(e,"2026-11-06","2026-11-09").size(),2,"Weekdays skip weekend");
        e.repeat="weekly";
        eq(between(e,"2026-11-02","2026-11-16").size(),3,"Weekly on original weekday");
        eq(between(e,"2036-11-02","2036-11-30").size(),4,"Old anchor remains usable");
        e = event("2024-01-31T09:00", "monthly");
        List<Occurrence> months = between(e,"2024-02-01","2024-03-31");
        eq(months.size(),2,"Monthly occurrence count");
        eq(months.get(0).start.toString(),"2024-02-29T09:00","Leap year clamps to 29");
        eq(months.get(1).start.toString(),"2024-03-31T09:00","Monthly retains original anchor");
        eq(between(e,"2025-02-01","2025-02-28").get(0).start.toString(),"2025-02-28T09:00","Non leap year clamps to 28");
        e = event("2026-11-02T09:00", "daily");
        Set<String> complete = new HashSet<>(Arrays.asList("7@2026-11-02T09:00"));
        List<Occurrence> os = ScheduleEngine.occurrences(e,LocalDate.parse("2026-11-02"),LocalDate.parse("2026-11-03"),complete);
        eq(os.get(0).completed,true,"Completion applies to this occurrence");
        eq(os.get(1).completed,false,"Next occurrence remains pending");
        LocalDateTime now = LocalDateTime.parse("2026-11-02T08:55");
        Occurrence next=ScheduleEngine.nextReminder(e,now,Collections.emptySet());
        eq(next.start.toString(),"2026-11-02T09:00","Near start reminder not skipped");
        eq(ScheduleEngine.reminderAt(next,now).toString(),"2026-11-02T09:00","Passed lead time falls back to start");
        eq(ScheduleEngine.reminderAt(next,LocalDateTime.parse("2026-11-02T08:50")).toString(),"2026-11-02T08:50","A reminder due exactly now must not be delayed to event start");
        eq(ScheduleEngine.reminderAt(next,LocalDateTime.parse("2026-11-02T08:50:05")).toString(),"2026-11-02T08:50:05","Small alarm delivery delay must not lose advance reminder");
        eq(ScheduleEngine.nextReminder(e,now,complete).start.toString(),"2026-11-03T09:00","Completed occurrence not reminded");
        e.reminder=-1; eq(ScheduleEngine.nextReminder(e,now,complete),null,"Disabled reminders not scheduled");
        e = event("2027-05-01T09:00","none");
        Occurrence distant=ScheduleEngine.nextReminder(e,now,Collections.emptySet());
        eq(distant==null?null:distant.start.toString(),"2027-05-01T09:00","Distant future event must be scheduled without reopening app");
        e = event("2026-11-02T09:00","none");
        eq(ScheduleEngine.nextReminder(e,LocalDateTime.parse("2026-11-02T09:01"),Collections.emptySet()),null,"Past starts not rescheduled");
        e=event("2026-11-01T09:00","monthly");
        Set<String> aheadDone=new HashSet<>(Arrays.asList("7@2026-11-01T09:00","7@2026-12-01T09:00"));
        Occurrence january=ScheduleEngine.nextReminder(e,LocalDateTime.parse("2026-11-01T08:00"),aheadDone);
        eq(january==null?null:january.start.toString(),"2027-01-01T09:00","Pre-completed monthly instances must not terminate reminders");
        e.repeat="broken"; eq(ScheduleEngine.validate(e).isEmpty(),false,"Unknown recurrence rejected");
        e.repeat="none"; e.reminder=-10; eq(ScheduleEngine.validate(e).isEmpty(),false,"Invalid reminder rejected");
        StudyTimer timer=new StudyTimer(0,false,0);
        timer.start(1000);eq(timer.isRunning(),true,"Manual start enters running state");
        eq(timer.elapsed(2500),1500L,"Study time uses monotonic elapsed milliseconds");
        timer.pause(3000);eq(timer.elapsed(9000),2000L,"Pause excludes time away");
        timer.start(10000);eq(timer.elapsed(11500),3500L,"Resume retains accumulated time");
        timer.start(12000);eq(timer.elapsed(12500),4500L,"Repeated start does not reset running session");
        timer.checkpoint(13000);eq(timer.accumulated(),5000L,"Checkpoint preserves elapsed before persistence");
        StudyTimer restored=new StudyTimer(timer.accumulated(),timer.isRunning(),timer.anchor());
        eq(restored.elapsed(14500),6500L,"Process recreation preserves background time");
        restored.pause(15000);eq(restored.isRunning(),false,"Manual pause stops timer");
        eq(new StudyTimer(0,true,500).elapsed(100),0L,"Clock reset never creates negative elapsed time");
        Event late=event("2026-11-02T09:00","none");late.revision="unchanged";
        AlarmPlan.Entry queued=new AlarmPlan.Entry();queued.id=7;queued.revision="unchanged";queued.start=late.start;queued.trigger=LocalDateTime.parse("2026-11-02T08:50");
        List<AlarmPlan.Entry> kept=AlarmPlan.build(Arrays.asList(late),Collections.emptySet(),Arrays.asList(queued),LocalDateTime.parse("2026-11-02T09:01"),ZoneId.of("Asia/Shanghai"));
        eq(kept.size(),1,"Opening app preserves a late undelivered reminder");
        eq(kept.get(0).trigger.toString(),"2026-11-02T08:50","Late reminder retains its original trigger");
        late.revision="edited";eq(AlarmPlan.build(Arrays.asList(late),Collections.emptySet(),Arrays.asList(queued),LocalDateTime.parse("2026-11-02T09:01"),ZoneId.of("Asia/Shanghai")).size(),0,"Edited event invalidates stale queued reminder");
        List<Event> simultaneous=new ArrayList<>();for(int i=1;i<=501;i++){Event item=event("2026-11-02T09:00","none");item.id=i;simultaneous.add(item);}
        eq(AlarmPlan.build(simultaneous,Collections.emptySet(),Collections.emptyList(),LocalDateTime.parse("2026-11-02T08:00"),ZoneId.of("Asia/Shanghai")).size(),501,"Shared-trigger reminders are retained in the queue");
        System.out.println("PASS: " + count + " core assertions");
    }
}

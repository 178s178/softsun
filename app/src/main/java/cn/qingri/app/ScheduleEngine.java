package cn.qingri.app;

import java.time.*;
import java.util.*;

public final class ScheduleEngine {
    private static final Set<String> REPEATS = new HashSet<>(Arrays.asList("none","daily","weekdays","weekly","monthly"));
    public static String validate(Event event) {
        if (event == null || event.title == null || event.title.trim().isEmpty()) return "请填写日程标题";
        if (event.title.trim().length() > 120) return "标题请控制在 120 字以内";
        if (event.start == null || event.end == null) return "请选择开始和结束时间";
        if (!event.end.isAfter(event.start)) return "结束时间需要晚于开始时间";
        if (Duration.between(event.start,event.end).toDays()>366) return "单次日程最长支持 366 天";
        if (event.start.getYear()<1970 || event.end.getYear()>2199) return "日期应在 1970 至 2199 年之间";
        if (!REPEATS.contains(event.repeat)) return "不支持的重复规则";
        if (event.reminder < -1 || event.reminder > 10080) return "提醒时间应在提前一周以内";
        if (event.priority<0 || event.priority>2) return "不支持的优先级";
        if (event.note==null || event.note.length()>10000 || event.location==null || event.location.length()>300) return "备注或地点过长";
        if (!Arrays.asList("生活","学习","工作","其他").contains(event.category)) return "不支持的分类";
        return "";
    }
    public static List<Occurrence> occurrences(Event event, LocalDate from, LocalDate to, Set<String> completed) {
        List<Occurrence> result = new ArrayList<>();
        if (!validate(event).isEmpty() || to.isBefore(from)) return result;
        Duration duration=Duration.between(event.start,event.end);
        LocalDate anchor=event.start.toLocalDate();
        LocalDate earliest=from.minusDays(duration.toDays()+1);
        if (earliest.isBefore(anchor)) earliest=anchor;
        if ("none".equals(event.repeat)) {
            add(result,event,event.start,duration,from,to,completed);
        } else if ("monthly".equals(event.repeat)) {
            YearMonth month=YearMonth.from(earliest);
            YearMonth last=YearMonth.from(to);
            while (!month.isAfter(last)) {
                LocalDate d=month.atDay(Math.min(anchor.getDayOfMonth(),month.lengthOfMonth()));
                if (!d.isBefore(anchor)) add(result,event,d.atTime(event.start.toLocalTime()),duration,from,to,completed);
                month=month.plusMonths(1);
            }
        } else {
            LocalDate d=earliest;
            if ("weekly".equals(event.repeat)) {
                long offset=java.time.temporal.ChronoUnit.DAYS.between(anchor,d)%7;
                if (offset!=0) d=d.plusDays(7-offset);
            }
            while (!d.isAfter(to)) {
                if (!"weekdays".equals(event.repeat) || d.getDayOfWeek().getValue()<=5)
                    add(result,event,d.atTime(event.start.toLocalTime()),duration,from,to,completed);
                d=d.plusDays("weekly".equals(event.repeat)?7:1);
            }
        }
        return result;
    }
    private static void add(List<Occurrence> list,Event event,LocalDateTime start,Duration duration,LocalDate from,LocalDate to,Set<String> completed) {
        LocalDateTime end=start.plus(duration);
        if (start.isBefore(to.plusDays(1).atStartOfDay()) && end.isAfter(from.atStartOfDay()))
            list.add(new Occurrence(event,start,end,completed.contains(event.id+"@"+start)));
    }
    public static LocalDateTime reminderAt(Occurrence occurrence, LocalDateTime now) {
        if (occurrence.event.reminder<0 || occurrence.start.isBefore(now)) return null;
        LocalDateTime lead=occurrence.start.minusMinutes(occurrence.event.reminder);
        if(!lead.isBefore(now.minusSeconds(30)))return lead.isBefore(now)?now:lead;
        return occurrence.start;
    }
    public static Occurrence nextReminder(Event event, LocalDateTime now, Set<String> completed) {
        if (event.reminder<0 || !validate(event).isEmpty()) return null;
        LocalDate date=event.start.toLocalDate().isAfter(now.toLocalDate())?event.start.toLocalDate():now.toLocalDate();
        if("none".equals(event.repeat))return !event.start.isBefore(now)&&!completed.contains(event.id+"@"+event.start)?new Occurrence(event,event.start,event.end,false):null;
        if("weekly".equals(event.repeat)){long offset=java.time.temporal.ChronoUnit.DAYS.between(event.start.toLocalDate(),date)%7;if(offset!=0)date=date.plusDays(7-offset);}
        if("monthly".equals(event.repeat)){YearMonth m=YearMonth.from(date);date=m.atDay(Math.min(event.start.getDayOfMonth(),m.lengthOfMonth()));}
        Duration duration=Duration.between(event.start,event.end);
        while(true){
            if("weekdays".equals(event.repeat))while(date.getDayOfWeek().getValue()>5)date=date.plusDays(1);
            LocalDateTime start=date.atTime(event.start.toLocalTime());
            if(!start.isBefore(now)&&!start.isBefore(event.start)&&!completed.contains(event.id+"@"+start))return new Occurrence(event,start,start.plus(duration),false);
            if("monthly".equals(event.repeat)){YearMonth m=YearMonth.from(date).plusMonths(1);date=m.atDay(Math.min(event.start.getDayOfMonth(),m.lengthOfMonth()));}else date=date.plusDays("weekly".equals(event.repeat)?7:1);
        }
    }
}

package cn.qingri.app;

import java.time.*;
import java.util.*;

public class TimeTests {
    static int count;
    static void eq(Object actual,Object want,String why){count++;if(!Objects.equals(actual,want))throw new AssertionError(why+": expected "+want+", got "+actual);}
    static final ZoneId ZONE=ZoneId.of("Asia/Shanghai");
    static long at(String date){return LocalDateTime.parse(date).atZone(ZONE).toInstant().toEpochMilli();}
    static TimeRecord record(String token,String start,String end,long duration){TimeRecord r=new TimeRecord();r.token=token;r.start=at(start);r.end=at(end);r.duration=duration;return r;}
    public static void main(String[] args){
        long[] d=TimeStats.day(LocalDate.parse("2026-11-03"),ZONE);
        TimeRecord r=record("a","2026-11-03T09:00","2026-11-03T10:00",3600000);
        eq(TimeStats.within(r,d[0],d[1]),3600000L,"One session counts on its day");
        eq(TimeStats.within(r,d[1],d[1]+86400000),0L,"Outside day excluded");
        TimeRecord night=record("b","2026-10-31T23:30","2026-11-01T00:30",3600000);
        long[] oct=TimeStats.month(YearMonth.of(2026,10),ZONE),nov=TimeStats.month(YearMonth.of(2026,11),ZONE);
        eq(TimeStats.within(night,oct[0],oct[1]),1800000L,"Cross-month first half allocated to October");
        eq(TimeStats.within(night,nov[0],nov[1]),1800000L,"Cross-month second half allocated to November");
        TimeRecord paused=record("c","2026-11-02T23:00","2026-11-03T01:00",1800000);
        paused.segments.add(new TimeRecord.Segment(at("2026-11-02T23:00"),at("2026-11-02T23:10"),600000));
        paused.segments.add(new TimeRecord.Segment(at("2026-11-03T00:40"),at("2026-11-03T01:00"),1200000));
        eq(TimeStats.within(paused,d[0],d[1]),1200000L,"Pause is excluded and only actual day segments count");
        TimeRecord legacy=record("d","2026-11-02T23:00","2026-11-03T01:00",3600000);
        eq(TimeStats.within(legacy,d[0],d[1]),1800000L,"Legacy effective time allocated proportionally");
        TimeRecord tiny=record("tiny","2026-11-02T23:59:59","2026-11-03T00:00:01",3);
        long[] prev=TimeStats.day(LocalDate.parse("2026-11-02"),ZONE);
        eq(TimeStats.within(tiny,prev[0],prev[1])+TimeStats.within(tiny,d[0],d[1]),3L,"Boundary allocation preserves every millisecond");
        TimeRecord second=record("e","2026-11-03T11:00","2026-11-03T11:30",1800000);
        TimeStats.Summary summary=TimeStats.summarize(Arrays.asList(r,second),d[0],d[1]);
        eq(summary.total,5400000L,"Summary sums recorded effective time");
        eq(summary.slices.size(),2,"Each session remains a distinct slice");
        eq(summary.slices.get(0).tenths,667,"Larger session share rounds to66.7 percent");
        eq(summary.slices.get(1).tenths,333,"Smaller session share rounds to33.3 percent");
        eq(TimeStats.summarize(Collections.emptyList(),d[0],d[1]).slices.size(),0,"Empty period has no fabricated slices");
        eq(TimeStats.duration(90061000),"25小时1分1秒","Duration does not wrap at24 hours");
        eq(TimeStats.duration(250),"不足1秒","Subsecond records remain visible");
        long[] dst=TimeStats.day(LocalDate.parse("2026-03-08"),ZoneId.of("America/New_York"));
        eq(dst[1]-dst[0],82800000L,"Calendar days respect DST rather than assuming24 hours");
        long[] leap=TimeStats.month(YearMonth.of(2024,2),ZONE);eq(leap[1]-leap[0],2505600000L,"Leap February includes29 days");
        TimeRecord equalA=record("aa","2026-11-03T09:00","2026-11-03T09:00:01",1000),equalB=record("bb","2026-11-03T09:01","2026-11-03T09:01:01",1000),equalC=record("cc","2026-11-03T09:02","2026-11-03T09:02:01",1000);
        TimeStats.Summary thirds=TimeStats.summarize(Arrays.asList(equalA,equalB,equalC),d[0],d[1]);
        eq(thirds.slices.stream().mapToInt(slice->slice.tenths).sum(),1000,"Rounded per-session percentages sum to100.0 percent");
        System.out.println("PASS: "+count+" time record assertions");
    }
}

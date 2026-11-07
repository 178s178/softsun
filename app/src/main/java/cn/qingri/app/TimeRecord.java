package cn.qingri.app;

import java.util.*;

public class TimeRecord {
    public String token="",label="";
    public long start,end,duration;
    public boolean estimated;
    public final List<Segment> segments=new ArrayList<>();
    public static class Segment {
        public final long start,end,duration;
        public Segment(long start,long end,long duration){this.start=start;this.end=end;this.duration=duration;}
    }
}

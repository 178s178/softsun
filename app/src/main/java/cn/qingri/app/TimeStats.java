package cn.qingri.app;

import java.time.*;
import java.util.*;
import java.math.BigInteger;

public final class TimeStats {
    public static class Slice {
        public TimeRecord record;
        public long duration;
        public int tenths;
    }
    public static class Summary {
        public long total;
        public final List<Slice> slices=new ArrayList<>();
    }
    private static long portion(long start,long end,long duration,long from,long to){
        if(duration<=0||from>=to)return 0;
        if(end<=start)return start>=from&&start<to?duration:0;
        if(from<=start&&to>=end)return duration;
        long left=Math.max(start,from),right=Math.min(end,to);if(right<=left)return 0;
        BigInteger amount=BigInteger.valueOf(duration),span=BigInteger.valueOf(end-start);
        return amount.multiply(BigInteger.valueOf(right-start)).divide(span).subtract(amount.multiply(BigInteger.valueOf(left-start)).divide(span)).longValue();
    }
    public static long within(TimeRecord record,long from,long to){
        if(record.segments.isEmpty())return portion(record.start,record.end,record.duration,from,to);
        long sum=0;for(TimeRecord.Segment s:record.segments)sum=Math.addExact(sum,portion(s.start,s.end,s.duration,from,to));return sum;
    }
    public static Summary summarize(List<TimeRecord> records,long from,long to){
        Summary result=new Summary();
        for(TimeRecord record:records){long amount=within(record,from,to);if(amount>0){Slice slice=new Slice();slice.record=record;slice.duration=amount;result.total=Math.addExact(result.total,amount);result.slices.add(slice);}}
        result.slices.sort((a,b)->{int order=Long.compare(b.duration,a.duration);if(order==0)order=Long.compare(a.record.start,b.record.start);return order!=0?order:a.record.token.compareTo(b.record.token);});
        if(result.total>0){int used=0;Map<Slice,BigInteger> remainders=new HashMap<>();for(Slice slice:result.slices){BigInteger[] pair=BigInteger.valueOf(slice.duration).multiply(BigInteger.valueOf(1000)).divideAndRemainder(BigInteger.valueOf(result.total));slice.tenths=pair[0].intValue();used+=slice.tenths;remainders.put(slice,pair[1]);}List<Slice> order=new ArrayList<>(result.slices);order.sort((a,b)->remainders.get(b).compareTo(remainders.get(a)));for(int i=0;i<1000-used;i++)order.get(i).tenths++;}
        return result;
    }
    public static String duration(long millis){if(millis<=0)return "0秒";if(millis<1000)return "不足1秒";long s=millis/1000,h=s/3600,m=s/60%60;return(h>0?h+"小时":"")+(m>0?m+"分":"")+(s%60>0?s%60+"秒":"");}
    public static long[] day(LocalDate date,ZoneId zone){return new long[]{date.atStartOfDay(zone).toInstant().toEpochMilli(),date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()};}
    public static long[] month(YearMonth month,ZoneId zone){return new long[]{month.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli(),month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()};}
}

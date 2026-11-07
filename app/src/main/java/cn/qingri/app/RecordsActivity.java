package cn.qingri.app;

import android.app.*;
import android.graphics.Typeface;
import android.os.*;
import android.text.*;
import android.view.*;
import android.widget.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;

/** Daily and monthly effective-time statistics, with one slice for every session. */
public class RecordsActivity extends Activity {
    private static final LocalDate MIN_DATE=LocalDate.of(1970,1,1),MAX_DATE=LocalDate.of(2199,12,31);
    private static final DateTimeFormatter FULL_TIME=DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm");
    private static final DateTimeFormatter ROW_TIME=DateTimeFormatter.ofPattern("M月d日 HH:mm");
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private Future<?> pendingLoad;
    private LocalDate date=LocalDate.now();
    private boolean monthly,destroyed;
    private String selectedToken;
    private int generation;
    private long from,to;
    private ZoneId zone=ZoneId.systemDefault();
    private TimeStats.Summary summary=new TimeStats.Summary();
    private ListView list;
    private LegendAdapter adapter;
    private PieView pie;
    private TextView dayTab,monthTab,dateLabel,periodLabel,totalLabel,countLabel,selectedLabel,estimateLabel,sectionLabel,emptyTitle,emptyAction;
    private View previous,next,chartCard,emptyCard,dailySection;
    private LinearLayout days;
    private Parcelable restoredScroll;

    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);
        String initial=saved==null?getIntent().getStringExtra("date"):saved.getString("date");
        if(initial!=null)try{date=LocalDate.parse(initial);}catch(RuntimeException ignored){}
        if(date.isBefore(MIN_DATE)||date.isAfter(MAX_DATE))date=LocalDate.now();
        monthly=saved==null?getIntent().getBooleanExtra("monthly",false):saved.getBoolean("monthly");
        if(saved!=null){selectedToken=saved.getString("selected");restoredScroll=saved.getParcelable("scroll");}
        build();
    }
    @Override protected void onResume(){super.onResume();refresh(false);}
    @Override protected void onSaveInstanceState(Bundle out){out.putString("date",date.toString());out.putBoolean("monthly",monthly);out.putString("selected",selectedToken);out.putParcelable("scroll",list.onSaveInstanceState());super.onSaveInstanceState(out);}
    @Override protected void onDestroy(){destroyed=true;generation++;if(pendingLoad!=null)pendingLoad.cancel(true);worker.shutdown();super.onDestroy();}
    private void build(){
        LinearLayout root=Ui.column(this);root.setBackgroundColor(Ui.BG);Ui.window(this,root);setContentView(root);
        LinearLayout top=Ui.row(this);top.setPadding(dp(16),dp(10),dp(16),dp(10));top.addView(Ui.iconButton(this,"back","返回",()->finish()),Ui.lp(this,44,44));
        TextView title=Ui.bold(this,"时间记录",21,Ui.INK);title.setPadding(dp(12),0,dp(8),0);Ui.weight(top,title);
        TextView add=Ui.button(this,"补记时间",false,()->TimeRecordEditor.show(this,date,()->refresh(false)));add.setTextSize(13);add.setPadding(dp(12),dp(8),dp(12),dp(8));add.setContentDescription("补记时间");top.addView(add,Ui.lp(this,-2,44));root.addView(top);
        LinearLayout tabs=Ui.row(this);tabs.setPadding(dp(4),dp(4),dp(4),dp(4));tabs.setBackground(Ui.shape(Ui.PALE,15,this));
        dayTab=tab("日统计",false);monthTab=tab("月统计",true);tabs.addView(dayTab,new LinearLayout.LayoutParams(0,dp(40),1));tabs.addView(monthTab,new LinearLayout.LayoutParams(0,dp(40),1));
        LinearLayout.LayoutParams tabParams=Ui.lp(this,-1,48);tabParams.setMargins(dp(22),dp(6),dp(22),dp(4));root.addView(tabs,tabParams);
        LinearLayout navigation=Ui.row(this);navigation.setPadding(dp(16),dp(2),dp(16),dp(2));previous=Ui.iconButton(this,"prev","上一天",()->move(-1));next=Ui.iconButton(this,"next","下一天",()->move(1));navigation.addView(previous,Ui.lp(this,48,48));
        dateLabel=Ui.centered(Ui.bold(this,"",16,Ui.INK));dateLabel.setMinHeight(dp(52));dateLabel.setOnClickListener(v->pickPeriod());dateLabel.setOnLongClickListener(v->{date=LocalDate.now();refresh(true);return true;});Ui.weight(navigation,dateLabel);navigation.addView(next,Ui.lp(this,48,48));root.addView(navigation);
        list=new ListView(this);list.setId(310);list.setDivider(null);list.setCacheColorHint(android.graphics.Color.TRANSPARENT);list.setClipToPadding(false);list.setPadding(dp(18),0,dp(18),dp(24));list.setSelector(android.R.color.transparent);list.setVerticalScrollBarEnabled(false);
        list.addHeaderView(buildHeader(),null,false);adapter=new LegendAdapter();list.setAdapter(adapter);list.setOnItemClickListener((parent,v,position,id)->{int index=position-list.getHeaderViewsCount();if(index>=0&&index<summary.slices.size())select(index);});list.setOnItemLongClickListener((parent,v,position,id)->{int index=position-list.getHeaderViewsCount();if(index<0||index>=summary.slices.size())return false;select(index);details(summary.slices.get(index));return true;});
        root.addView(list,new LinearLayout.LayoutParams(-1,0,1));updateNavigation();
    }
    private TextView tab(String label,boolean month){TextView view=Ui.centered(Ui.bold(this,label,14,Ui.GREEN));view.setContentDescription(label);view.setOnClickListener(v->{if(monthly!=month){monthly=month;refresh(true);}});return view;}
    private View buildHeader(){
        LinearLayout header=Ui.column(this);header.setPadding(0,dp(6),0,0);
        LinearLayout card=Ui.column(this);card.setPadding(dp(16),dp(20),dp(16),dp(14));card.setBackground(Ui.shape(Ui.WHITE,22,this));chartCard=card;
        LinearLayout totalRow=Ui.row(this);LinearLayout numbers=Ui.column(this);periodLabel=Ui.text(this,"本日有效时长",12,Ui.MUTED);numbers.addView(periodLabel);Ui.gap(numbers,8);totalLabel=Ui.bold(this,"…",29,Ui.GREEN);totalLabel.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));numbers.addView(totalLabel);Ui.weight(totalRow,numbers);
        LinearLayout count=Ui.column(this);countLabel=Ui.centered(Ui.bold(this,"—",20,Ui.INK));count.addView(countLabel);Ui.gap(count,8);count.addView(Ui.centered(Ui.text(this,"学习记录",11,Ui.MUTED)));totalRow.addView(count,Ui.lp(this,80,-2));card.addView(totalRow);Ui.gap(card,4);
        pie=new PieView(this);pie.setId(311);pie.setOnSliceSelectedListener(i->select(i));card.addView(pie,Ui.lp(this,-1,254));
        selectedLabel=Ui.centered(Ui.text(this,"点选扇区或下方记录，查看本次占比",12,Ui.MUTED));selectedLabel.setMinHeight(dp(44));selectedLabel.setLineSpacing(dp(3),1);selectedLabel.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);card.addView(selectedLabel);
        estimateLabel=Ui.text(this,"含历史记录估算：跨期时长按时间跨度分配。",11,0xff826743);estimateLabel.setLineSpacing(dp(3),1);estimateLabel.setPadding(dp(6),dp(8),dp(6),0);estimateLabel.setVisibility(View.GONE);card.addView(estimateLabel);header.addView(card);
        LinearLayout empty=Ui.column(this);empty.setPadding(dp(24),dp(36),dp(24),dp(30));empty.setBackground(Ui.shape(Ui.WHITE,22,this));Ui.Icon icon=new Ui.Icon(this,"leaf",Ui.GREEN);LinearLayout.LayoutParams iconParams=Ui.lp(this,52,52);iconParams.gravity=Gravity.CENTER_HORIZONTAL;empty.addView(icon,iconParams);Ui.gap(empty,18);emptyTitle=Ui.centered(Ui.bold(this,"这一天还没有记录",18,Ui.INK));empty.addView(emptyTitle);Ui.gap(empty,10);TextView hint=Ui.centered(Ui.text(this,"从一段专注开始，或补记已经完成的学习。",13,Ui.MUTED));hint.setLineSpacing(dp(4),1);empty.addView(hint);Ui.gap(empty,22);emptyAction=Ui.button(this,"补记时间",true,()->TimeRecordEditor.show(this,date,()->refresh(false)));empty.addView(emptyAction,Ui.lp(this,-1,48));emptyCard=empty;empty.setVisibility(View.GONE);LinearLayout.LayoutParams emptyParams=Ui.lp(this,-1,-2);emptyParams.topMargin=dp(12);header.addView(empty,emptyParams);
        LinearLayout daily=Ui.column(this);daily.setPadding(0,dp(20),0,0);daily.addView(Ui.bold(this,"月内每天",15,Ui.INK));Ui.gap(daily,4);daily.addView(Ui.text(this,"点选日期，查看当天每次学习",11,Ui.MUTED));Ui.gap(daily,10);HorizontalScrollView dayScroll=new HorizontalScrollView(this);dayScroll.setHorizontalScrollBarEnabled(false);days=Ui.row(this);dayScroll.addView(days);daily.addView(dayScroll);dailySection=daily;daily.setVisibility(View.GONE);header.addView(daily);
        sectionLabel=Ui.bold(this,"每次学习",16,Ui.INK);sectionLabel.setPadding(dp(4),dp(22),dp(4),dp(12));header.addView(sectionLabel);return header;
    }
    private int dp(float value){return Ui.dp(this,value);}
    private void updateNavigation(){
        dayTab.setBackground(Ui.shape(monthly?Ui.PALE:Ui.WHITE,12,this));monthTab.setBackground(Ui.shape(monthly?Ui.WHITE:Ui.PALE,12,this));dayTab.setSelected(!monthly);monthTab.setSelected(monthly);
        dayTab.setTextColor(monthly?Ui.MUTED:Ui.GREEN);monthTab.setTextColor(monthly?Ui.GREEN:Ui.MUTED);
        String suffix="";if(!monthly){String[] week={"周一","周二","周三","周四","周五","周六","周日"};suffix="  "+week[date.getDayOfWeek().getValue()-1];}
        dateLabel.setText(monthly?date.format(DateTimeFormatter.ofPattern("yyyy年M月")):date.format(DateTimeFormatter.ofPattern("yyyy年M月d日"))+suffix);dateLabel.setContentDescription(monthly?"选择月份":"选择日期");
        previous.setContentDescription(monthly?"上个月":"上一天");next.setContentDescription(monthly?"下个月":"下一天");boolean first=monthly?YearMonth.from(date).equals(YearMonth.from(MIN_DATE)):date.equals(MIN_DATE);boolean last=monthly?YearMonth.from(date).equals(YearMonth.from(MAX_DATE)):date.equals(MAX_DATE);previous.setEnabled(!first);previous.setAlpha(first?.3f:1f);next.setEnabled(!last);next.setAlpha(last?.3f:1f);periodLabel.setText(monthly?"本月有效时长":"本日有效时长");
    }
    private void move(int delta){date=monthly?date.plusMonths(delta):date.plusDays(delta);refresh(true);}
    private void refresh(boolean periodChanged){
        if(destroyed)return;if(periodChanged){selectedToken=null;restoredScroll=null;list.setSelection(0);}updateNavigation();
        zone=ZoneId.systemDefault();long[] range=monthly?TimeStats.month(YearMonth.from(date),zone):TimeStats.day(date,zone);from=range[0];to=range[1];final long rangeFrom=from,rangeTo=to;final LocalDate periodDate=date;final boolean month=monthly;final ZoneId periodZone=zone;final int ticket=++generation;
        if(pendingLoad!=null)pendingLoad.cancel(true);
        if(periodChanged||summary.slices.isEmpty()){summary=new TimeStats.Summary();adapter.notifyDataSetChanged();totalLabel.setText("…");countLabel.setText("读取中");pie.setVisibility(View.GONE);selectedLabel.setText("正在读取学习记录…");emptyCard.setVisibility(View.GONE);dailySection.setVisibility(View.GONE);sectionLabel.setVisibility(View.GONE);estimateLabel.setVisibility(View.GONE);}
        pendingLoad=worker.submit(()->{
            try(EventStore store=new EventStore(getApplicationContext())){
                List<TimeRecord> records=store.studyRecords(rangeFrom,rangeTo);if(Thread.currentThread().isInterrupted())return;
                TimeStats.Summary data=TimeStats.summarize(records,rangeFrom,rangeTo);long[] totals=month?dailyTotals(data,periodDate,periodZone):new long[0];if(Thread.currentThread().isInterrupted())return;
                runOnUiThread(()->{if(!destroyed&&ticket==generation)apply(data,totals);});
            }catch(CancellationException ignored){}catch(RuntimeException ex){runOnUiThread(()->{if(!destroyed&&ticket==generation)showReadFailure();});}
        });
    }
    private static long[] dailyTotals(TimeStats.Summary data,LocalDate date,ZoneId zone){
        YearMonth month=YearMonth.from(date);int count=month.lengthOfMonth();long[] totals=new long[count],edges=new long[count+1];for(int d=0;d<=count;d++)edges[d]=month.atDay(1).plusDays(d).atStartOfDay(zone).toInstant().toEpochMilli();
        for(TimeStats.Slice slice:data.slices){if(Thread.currentThread().isInterrupted())throw new CancellationException();TimeRecord r=slice.record;for(int d=0;d<count;d++)if(r.start<edges[d+1]&&r.end>=edges[d])totals[d]=Math.addExact(totals[d],TimeStats.within(r,edges[d],edges[d+1]));}return totals;
    }
    private void apply(TimeStats.Summary data,long[] dayTotals){
        summary=data;totalLabel.setText(TimeStats.duration(data.total));countLabel.setText(data.slices.size()+" 次");pie.setVisibility(data.total>0?View.VISIBLE:View.GONE);pie.setData(data);chartCard.setVisibility(View.VISIBLE);
        emptyCard.setVisibility(data.total==0?View.VISIBLE:View.GONE);emptyTitle.setText(monthly?"这个月还没有记录":"这一天还没有记录");emptyAction.setText("补记时间");emptyAction.setOnClickListener(v->TimeRecordEditor.show(this,date,()->refresh(false)));
        sectionLabel.setVisibility(data.total>0?View.VISIBLE:View.GONE);sectionLabel.setText("每次学习  ·  "+data.slices.size()+" 条");boolean estimated=false;for(TimeStats.Slice slice:data.slices)if(estimated(slice.record)){estimated=true;break;}estimateLabel.setVisibility(estimated?View.VISIBLE:View.GONE);
        selectedLabel.setVisibility(data.total>0?View.VISIBLE:View.GONE);selectedLabel.setText("点选扇区或下方记录，查看本次占比");selectedLabel.setTextColor(Ui.MUTED);int selected=-1;for(int i=0;i<data.slices.size();i++)if(data.slices.get(i).record.token.equals(selectedToken)){selected=i;break;}if(selected>=0)select(selected);else selectedToken=null;
        adapter.notifyDataSetChanged();dailySection.setVisibility(monthly&&data.total>0?View.VISIBLE:View.GONE);days.removeAllViews();
        if(monthly&&data.total>0){YearMonth month=YearMonth.from(date);for(int d=0;d<dayTotals.length;d++){final LocalDate chosen=month.atDay(d+1);long value=dayTotals[d];LinearLayout tile=Ui.column(this);tile.setGravity(Gravity.CENTER);tile.setPadding(dp(9),dp(12),dp(9),dp(12));tile.setBackground(Ui.shape(value>0?Ui.PALE:Ui.WHITE,13,this));tile.addView(Ui.centered(Ui.bold(this,(d+1)+"日",13,value>0?Ui.GREEN:Ui.MUTED)));Ui.gap(tile,7);tile.addView(Ui.centered(Ui.text(this,compactDuration(value),10,value>0?Ui.GREEN:Ui.MUTED)));tile.setContentDescription(chosen.getMonthValue()+"月"+chosen.getDayOfMonth()+"日，有效学习"+TimeStats.duration(value)+"，查看日统计");tile.setOnClickListener(v->{date=chosen;monthly=false;refresh(true);});LinearLayout.LayoutParams p=Ui.lp(this,74,72);p.rightMargin=dp(7);days.addView(tile,p);}}
        if(restoredScroll!=null){list.onRestoreInstanceState(restoredScroll);restoredScroll=null;}
    }
    private void showReadFailure(){summary=new TimeStats.Summary();adapter.notifyDataSetChanged();totalLabel.setText("—");countLabel.setText("读取失败");pie.setVisibility(View.GONE);selectedLabel.setVisibility(View.GONE);estimateLabel.setVisibility(View.GONE);sectionLabel.setVisibility(View.GONE);dailySection.setVisibility(View.GONE);emptyCard.setVisibility(View.VISIBLE);emptyTitle.setText("暂时无法读取记录");emptyAction.setText("重新读取");emptyAction.setOnClickListener(v->refresh(false));Ui.toast(this,"读取失败，请重试");}
    private static String compactDuration(long value){if(value==0)return "无记录";if(value<60000)return value<1000?"不足1秒":value/1000+"秒";long min=value/60000;return min>=60?(min/60)+"时"+(min%60>0?min%60+"分":""):min+"分钟";}
    private boolean estimated(TimeRecord record){return(record.estimated||record.segments.isEmpty())&&(record.start<from||record.end>to);}
    private static String label(TimeRecord record){return record.label==null||record.label.trim().isEmpty()?"未命名学习":record.label.trim();}
    private static String percentage(TimeStats.Slice slice){return slice.duration>0&&slice.tenths==0?"<0.1%":String.format(Locale.CHINA,"%.1f%%",slice.tenths/10d);}
    private void select(int index){
        if(index<0||index>=summary.slices.size())return;TimeStats.Slice slice=summary.slices.get(index);selectedToken=slice.record.token;pie.setSelectedIndex(index);String text=label(slice.record)+"  ·  "+TimeStats.duration(slice.duration)+"  ·  "+percentage(slice);selectedLabel.setText(text);selectedLabel.setTextColor(Ui.GREEN);pie.setContentDescription("已选中 "+label(slice.record)+"，"+TimeStats.duration(slice.duration)+"，占比"+percentage(slice));adapter.notifyDataSetChanged();
    }
    private void pickPeriod(){
        if(!monthly){DatePickerDialog picker=new DatePickerDialog(this,(view,year,month,day)->{date=LocalDate.of(year,month+1,day);refresh(true);},date.getYear(),date.getMonthValue()-1,date.getDayOfMonth());picker.setTitle("选择日期");picker.getDatePicker().setMinDate(0);picker.getDatePicker().setMaxDate(7258118399999L);picker.show();return;}
        LinearLayout content=Ui.row(this);content.setGravity(Gravity.CENTER);content.setPadding(dp(20),dp(12),dp(20),dp(8));NumberPicker year=new NumberPicker(this),month=new NumberPicker(this);year.setMinValue(1970);year.setMaxValue(2199);year.setValue(date.getYear());year.setWrapSelectorWheel(false);year.setContentDescription("年份");month.setMinValue(1);month.setMaxValue(12);month.setDisplayedValues(new String[]{"1月","2月","3月","4月","5月","6月","7月","8月","9月","10月","11月","12月"});month.setValue(date.getMonthValue());month.setContentDescription("月份");year.setDescendantFocusability(NumberPicker.FOCUS_BLOCK_DESCENDANTS);month.setDescendantFocusability(NumberPicker.FOCUS_BLOCK_DESCENDANTS);content.addView(year,Ui.lp(this,132,170));content.addView(month,Ui.lp(this,112,170));new AlertDialog.Builder(this).setTitle("选择月份").setView(content).setNegativeButton("取消",null).setNeutralButton("本月",(d,w)->{date=LocalDate.now();refresh(true);}).setPositiveButton("确定",(d,w)->{YearMonth picked=YearMonth.of(year.getValue(),month.getValue());date=picked.atDay(Math.min(date.getDayOfMonth(),picked.lengthOfMonth()));refresh(true);}).show();
    }
    private void details(TimeStats.Slice slice){
        TimeRecord record=slice.record;String message="开始  "+FULL_TIME.format(Instant.ofEpochMilli(record.start).atZone(zone))+"\n结束  "+FULL_TIME.format(Instant.ofEpochMilli(record.end).atZone(zone))+"\n\n总有效时长  "+TimeStats.duration(record.duration)+"\n本期计入  "+TimeStats.duration(slice.duration)+"\n本期占比  "+percentage(slice);
        if(estimated(record))message+="\n\n历史估算：这条记录缺少完整计时片段，本期时长按时间跨度分配。";
        else if(record.start<from||record.end>to)message+="\n\n此记录跨越当前统计范围，仅统计本期内的有效片段。";
        new AlertDialog.Builder(this).setTitle(label(record)).setMessage(message).setNegativeButton("关闭",null).setPositiveButton("修改名称",(d,w)->rename(record)).setNeutralButton("删除记录",(d,w)->confirmDelete(record)).show();
    }
    private void rename(TimeRecord record){
        EditText input=new EditText(this);input.setId(302);input.setContentDescription("修改记录名称");input.setText(record.label);input.setSingleLine(true);input.setSelectAllOnFocus(true);input.setHint("留空显示为未命名学习");input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(80)});input.setPadding(dp(16),dp(12),dp(16),dp(12));input.setTextColor(Ui.INK);LinearLayout box=Ui.column(this);box.setPadding(dp(20),dp(8),dp(20),dp(4));box.addView(input);TextView error=Ui.text(this,"",12,0xffa34232);box.addView(error);AlertDialog dialog=new AlertDialog.Builder(this).setTitle("修改名称").setView(box).setNegativeButton("取消",null).setPositiveButton("保存",null).create();dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{String value=input.getText().toString().trim();dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);worker.execute(()->{try(EventStore store=new EventStore(getApplicationContext())){store.renameStudy(record.token,value);runOnUiThread(()->{if(destroyed)return;dialog.dismiss();Ui.toast(this,"名称已更新");refresh(false);});}catch(RuntimeException ex){runOnUiThread(()->{if(destroyed)return;dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);error.setText("保存失败，请重试。");});}});}));dialog.show();
    }
    private void confirmDelete(TimeRecord record){
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("删除这条记录？").setMessage("“"+label(record)+"”的学习时长将从统计中移除，此操作无法撤销。").setNegativeButton("保留记录",null).setPositiveButton("删除",null).create();dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);worker.execute(()->{try(EventStore store=new EventStore(getApplicationContext())){store.deleteStudy(record.token);runOnUiThread(()->{if(destroyed)return;dialog.dismiss();Ui.toast(this,"记录已删除");refresh(false);});}catch(RuntimeException ex){runOnUiThread(()->{if(destroyed)return;dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);Ui.toast(this,"删除失败，请重试");});}});}));dialog.show();
    }
    private final class LegendAdapter extends BaseAdapter {
        @Override public int getCount(){return summary.slices.size();}
        @Override public TimeStats.Slice getItem(int position){return summary.slices.get(position);}
        @Override public long getItemId(int position){String token=getItem(position).record.token;long hash=0xcbf29ce484222325L;for(int i=0;i<token.length();i++)hash=(hash^token.charAt(i))*0x100000001b3L;return hash;}
        @Override public boolean hasStableIds(){return true;}
        @Override public View getView(int position,View recycled,android.view.ViewGroup parent){
            Row row;if(recycled==null){row=new Row();recycled=row.root;recycled.setTag(row);}else row=(Row)recycled.getTag();
            TimeStats.Slice slice=getItem(position);TimeRecord record=slice.record;String name=label(record);boolean active=record.token.equals(selectedToken);row.root.setBackground(new android.graphics.drawable.InsetDrawable(Ui.outline(active?Ui.PALE:Ui.WHITE,active?0xffa4c9b5:Ui.LINE,16,RecordsActivity.this),0,0,0,dp(8)));row.dot.setBackground(Ui.shape(PieView.colorFor(record),4,RecordsActivity.this));row.name.setText(name);row.amount.setText(TimeStats.duration(slice.duration));row.percent.setText(percentage(slice));
            String time=ROW_TIME.format(Instant.ofEpochMilli(record.start).atZone(zone))+" — "+(Instant.ofEpochMilli(record.start).atZone(zone).toLocalDate().equals(Instant.ofEpochMilli(record.end).atZone(zone).toLocalDate())?DateTimeFormatter.ofPattern("HH:mm").format(Instant.ofEpochMilli(record.end).atZone(zone)):ROW_TIME.format(Instant.ofEpochMilli(record.end).atZone(zone)));
            if(estimated(record))time+="  · 历史估算";else if(record.start<from||record.end>to)time+="  · 本期时长";row.time.setText(time);row.more.setContentDescription("查看"+name+"详情");row.more.setOnClickListener(v->{select(position);details(slice);});row.root.setContentDescription(name+"，"+TimeStats.duration(slice.duration)+"，占比"+percentage(slice)+(active?"，已选中":""));row.root.setSelected(active);return recycled;
        }
        private final class Row {
            final LinearLayout root=Ui.row(RecordsActivity.this);
            final View dot=new View(RecordsActivity.this);
            final TextView name=Ui.bold(RecordsActivity.this,"",15,Ui.INK),time=Ui.text(RecordsActivity.this,"",11,Ui.MUTED),amount=Ui.bold(RecordsActivity.this,"",14,Ui.INK),percent=Ui.text(RecordsActivity.this,"",12,Ui.GREEN),more=Ui.centered(Ui.text(RecordsActivity.this,"详情",12,Ui.GREEN));
            Row(){
                AbsListView.LayoutParams outer=new AbsListView.LayoutParams(-1,-2);root.setLayoutParams(outer);root.setPadding(dp(13),dp(15),dp(5),dp(23));root.setMinimumHeight(dp(100));root.setDescendantFocusability(android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS);
                LinearLayout.LayoutParams dotParams=Ui.lp(RecordsActivity.this,10,30);dotParams.rightMargin=dp(12);root.addView(dot,dotParams);LinearLayout middle=Ui.column(RecordsActivity.this);name.setMaxLines(1);name.setEllipsize(TextUtils.TruncateAt.END);middle.addView(name);Ui.gap(middle,8);time.setMaxLines(2);time.setLineSpacing(dp(3),1);middle.addView(time);Ui.weight(root,middle);LinearLayout values=Ui.column(RecordsActivity.this);values.setPadding(dp(8),0,dp(1),0);values.setGravity(Gravity.RIGHT);amount.setMaxLines(2);amount.setGravity(Gravity.RIGHT);values.addView(amount);Ui.gap(values,7);values.addView(percent);root.addView(values,Ui.lp(RecordsActivity.this,88,-2));more.setMinHeight(dp(48));root.addView(more,Ui.lp(RecordsActivity.this,44,48));
            }
        }
    }
}

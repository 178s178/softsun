package cn.qingri.app;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.*;
import android.text.InputFilter;
import android.view.*;
import android.widget.*;
import java.time.*;
import java.time.format.DateTimeFormatter;

public class EditorActivity extends Activity {
    private EventStore store;
    private Event event;
    private EditText title,note,location;
    private Spinner category,priority,repeat,reminder;
    private LocalDateTime start,end;
    private TextView startValue,endValue;
    private final String[] repeats={"none","daily","weekdays","weekly","monthly"};
    private final int[] reminderValues={-1,0,5,10,15,30,60,1440};
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);store=new EventStore(this);long id=getIntent().getLongExtra("id",0);event=id>0?store.find(id):new Event();
        if(event==null){Ui.toast(this,"日程已被删除");finish();return;}
        if(event.start==null){LocalDate day=LocalDate.now();try{day=LocalDate.parse(getIntent().getStringExtra("date"));}catch(Exception ignored){}LocalDateTime proposed=LocalDateTime.now().plusHours(1).withMinute(0).withSecond(0).withNano(0);event.start=day.atTime(proposed.toLocalTime());if(day.equals(LocalDate.now())&&event.start.isBefore(LocalDateTime.now()))event.start=proposed;event.end=event.start.plusHours(1);}
        start=event.start;end=event.end;
        if(saved!=null){start=LocalDateTime.parse(saved.getString("start"));end=LocalDateTime.parse(saved.getString("end"));}
        LinearLayout root=Ui.column(this);root.setBackgroundColor(Ui.BG);Ui.window(this,root);setContentView(root);
        LinearLayout bar=Ui.row(this);bar.setPadding(Ui.dp(this,16),Ui.dp(this,12),Ui.dp(this,20),Ui.dp(this,14));bar.addView(Ui.iconButton(this,"back","返回",()->onBackPressed()),Ui.lp(this,42,42));TextView heading=Ui.bold(this,id>0?"编辑日程":"新的小安排",21,Ui.INK);heading.setPadding(Ui.dp(this,12),0,0,0);Ui.weight(bar,heading);root.addView(bar);
        ScrollView scroll=new ScrollView(this);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));LinearLayout body=Ui.column(this);body.setPadding(Ui.dp(this,22),Ui.dp(this,12),Ui.dp(this,22),Ui.dp(this,24));scroll.addView(body);
        body.addView(Ui.text(this,"把想做的事，放进时间里。",13,Ui.MUTED));Ui.gap(body,22);
        title=field(body,"日程标题","想安排什么？",event.title,120,false);title.setId(101);
        LinearLayout times=Ui.column(this);times.setBackground(Ui.shape(Ui.WHITE,18,this));Ui.pad(times,17);body.addView(times);startValue=timeRow(times,"开始",true);Ui.gap(times,18);endValue=timeRow(times,"结束",false);refreshTimes();Ui.gap(body,20);
        category=picker(body,"分类",new String[]{"生活","学习","工作","其他"},event.category);category.setId(102);
        priority=picker(body,"优先级",new String[]{"低 · 可以慢慢来","普通 · 按计划完成","高 · 重要的事"},new String[]{"低 · 可以慢慢来","普通 · 按计划完成","高 · 重要的事"}[event.priority]);priority.setId(103);
        String[] repeatLabels={"不重复","每天","工作日（周一至周五）","每周","每月（按开始日期）"};int ri=0;for(int i=0;i<repeats.length;i++)if(repeats[i].equals(event.repeat))ri=i;repeat=picker(body,"重复",repeatLabels,repeatLabels[ri]);repeat.setId(104);
        String[] reminders={"不提醒","准时提醒","提前 5 分钟","提前 10 分钟","提前 15 分钟","提前 30 分钟","提前 1 小时","提前 1 天"};int mi=3;for(int i=0;i<reminderValues.length;i++)if(reminderValues[i]==event.reminder)mi=i;reminder=picker(body,"提醒",reminders,reminders[mi]);reminder.setId(105);
        location=field(body,"地点 · 可选","例如：图书馆、会议室",event.location,300,false);location.setId(106);
        note=field(body,"备注 · 可选","记下一点细节…",event.note,10000,true);note.setId(107);
        if(id>0&&!"none".equals(event.repeat)){TextView hint=Ui.text(this,"编辑会应用于整个重复系列；调整开始时间或重复规则会重置这个系列的完成记录。",11,Ui.MUTED);hint.setLineSpacing(Ui.dp(this,4),1);body.addView(hint);}
        LinearLayout footer=Ui.column(this);footer.setPadding(Ui.dp(this,22),Ui.dp(this,12),Ui.dp(this,22),Ui.dp(this,14));footer.setBackgroundColor(Ui.WHITE);footer.addView(Ui.button(this,"保存日程",true,()->save()),Ui.lp(this,-1,52));root.addView(footer);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE|WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
    }
    private EditText field(LinearLayout body,String label,String hint,String value,int max,boolean multiline) {
        body.addView(Ui.bold(this,label,13,Ui.INK));Ui.gap(body,9);EditText input=new EditText(this);input.setTextSize(15);input.setTextColor(Ui.INK);input.setHintTextColor(Ui.MUTED);input.setText(value);input.setHint(hint);input.setSingleLine(!multiline);input.setGravity(Gravity.TOP);input.setBackground(Ui.shape(Ui.WHITE,13,this));input.setPadding(Ui.dp(this,15),Ui.dp(this,14),Ui.dp(this,15),Ui.dp(this,14));input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(max)});if(multiline){input.setMinLines(3);input.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE|android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);}body.addView(input,Ui.lp(this,-1,multiline?110:52));Ui.gap(body,20);return input;
    }
    private Spinner picker(LinearLayout body,String label,String[] labels,String selected) {
        body.addView(Ui.bold(this,label,13,Ui.INK));Ui.gap(body,9);Spinner spinner=new Spinner(this,Spinner.MODE_DROPDOWN);ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,labels);spinner.setAdapter(adapter);spinner.setBackground(Ui.shape(Ui.WHITE,13,this));spinner.setPadding(Ui.dp(this,9),0,Ui.dp(this,9),0);for(int i=0;i<labels.length;i++)if(labels[i].equals(selected))spinner.setSelection(i);body.addView(spinner,Ui.lp(this,-1,50));Ui.gap(body,20);return spinner;
    }
    private TextView timeRow(LinearLayout body,String label,boolean isStart) {LinearLayout row=Ui.row(this);Ui.weight(row,Ui.bold(this,label,14,Ui.INK));TextView val=Ui.text(this,"",14,Ui.GREEN);val.setPadding(Ui.dp(this,10),Ui.dp(this,7),0,Ui.dp(this,7));row.addView(val);body.addView(row);row.setOnClickListener(v->chooseDate(isStart));return val;}
    private void refreshTimes(){DateTimeFormatter f=DateTimeFormatter.ofPattern("M月d日 HH:mm");startValue.setText(start.format(f));endValue.setText(end.format(f));startValue.setContentDescription("开始时间 "+start);endValue.setContentDescription("结束时间 "+end);}
    private void chooseDate(boolean first) {
        LocalDateTime initial=first?start:end;
        DatePickerDialog date=new DatePickerDialog(this,(picker,y,m,d)->new TimePickerDialog(this,(tp,h,min)->{
            LocalDateTime chosen=LocalDate.of(y,m+1,d).atTime(h,min);
            if(first){Duration duration=Duration.between(start,end);start=chosen;end=start.plus(duration);}else end=chosen;
            refreshTimes();
        },initial.getHour(),initial.getMinute(),true).show(),initial.getYear(),initial.getMonthValue()-1,initial.getDayOfMonth());
        date.getDatePicker().setMinDate(LocalDate.of(1970,1,1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli());date.getDatePicker().setMaxDate(LocalDate.of(2199,12,31).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli());date.show();
    }
    private void save() {
        event.title=title.getText().toString().trim();event.note=note.getText().toString().trim();event.location=location.getText().toString().trim();event.start=start;event.end=end;event.category=(String)category.getSelectedItem();event.priority=priority.getSelectedItemPosition();event.repeat=repeats[repeat.getSelectedItemPosition()];event.reminder=reminderValues[reminder.getSelectedItemPosition()];
        String error=ScheduleEngine.validate(event);if(!error.isEmpty()){Ui.toast(this,error);return;}
        try{store.save(event);ReminderScheduler.reschedule(this);}catch(Exception ex){Ui.toast(this,"保存失败，请检查手机可用存储空间");return;}
        if(event.reminder>=0&&Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},4);else {Ui.toast(this,"日程已保存");finish();}
    }
    @Override public void onRequestPermissionsResult(int code,String[] names,int[] results){super.onRequestPermissionsResult(code,names,results);if(code==4){ReminderScheduler.reschedule(this);Ui.toast(this,ReminderScheduler.notificationsAllowed(this)?"日程已保存":"已保存，通知未开启，可在设置中启用");finish();}}
    @Override protected void onSaveInstanceState(Bundle b){b.putString("start",start.toString());b.putString("end",end.toString());super.onSaveInstanceState(b);}
    @Override protected void onDestroy(){if(store!=null)store.close();super.onDestroy();}
    @Override public void onBackPressed(){new AlertDialog.Builder(this).setTitle("放弃这次编辑？").setNegativeButton("继续编辑",null).setPositiveButton("放弃",(d,w)->finish()).show();}
}

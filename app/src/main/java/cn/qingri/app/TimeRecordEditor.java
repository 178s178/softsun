package cn.qingri.app;

import android.app.*;
import android.os.Bundle;
import android.text.InputFilter;
import android.view.*;
import android.widget.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.concurrent.*;

/** Native manual-entry dialog. Invalid input keeps the dialog open. */
public final class TimeRecordEditor {
    private static final long MAX_DURATION=366L*24*60*60*1000;
    private static final DateTimeFormatter FORMAT=DateTimeFormatter.ofPattern("yyyy年M月d日  HH:mm");
    private TimeRecordEditor(){}
    public static void show(Activity activity,LocalDate date,Runnable onSaved){
        new EntryDialog(activity,date,onSaved).show();
    }
    private static final class EntryDialog extends AlertDialog {
        private final Activity activity;
        private final Runnable onSaved;
        private LocalDateTime start,end;
        private TextView startButton,endButton,preview,error;
        private EditText name;
        private boolean saving;
        private final String token=UUID.randomUUID().toString();
        EntryDialog(Activity activity,LocalDate date,Runnable onSaved){
            super(activity);this.activity=activity;this.onSaved=onSaved;
            LocalTime now=LocalTime.now().withSecond(0).withNano(0);
            end=LocalDateTime.of(date,now);start=end.minusMinutes(30);
        }
        @Override protected void onCreate(Bundle state){
            setTitle("补记时间");
            ScrollView scroll=new ScrollView(activity);LinearLayout content=Ui.column(activity);content.setPadding(Ui.dp(activity,24),Ui.dp(activity,8),Ui.dp(activity,24),Ui.dp(activity,12));scroll.addView(content);
            TextView description=Ui.text(activity,"每次补记保存为一条记录，按实际起止时间计算时长。",13,Ui.MUTED);description.setLineSpacing(Ui.dp(activity,4),1);content.addView(description);Ui.gap(content,18);
            content.addView(Ui.bold(activity,"名称（选填）",13,Ui.INK));Ui.gap(content,5);
            name=new EditText(activity);name.setId(301);name.setSingleLine(true);name.setTextSize(16);name.setTextColor(Ui.INK);name.setHint("例如：英语阅读");name.setFilters(new InputFilter[]{new InputFilter.LengthFilter(80)});name.setContentDescription("记录名称，选填");content.addView(name,Ui.lp(activity,-1,50));Ui.gap(content,18);
            content.addView(Ui.bold(activity,"开始时间",13,Ui.INK));Ui.gap(content,8);startButton=Ui.button(activity,"",false,()->pickDate(true));startButton.setTextSize(14);startButton.setContentDescription("选择开始日期和时间");content.addView(startButton,Ui.lp(activity,-1,52));Ui.gap(content,16);
            content.addView(Ui.bold(activity,"结束时间",13,Ui.INK));Ui.gap(content,8);endButton=Ui.button(activity,"",false,()->pickDate(false));endButton.setTextSize(14);endButton.setContentDescription("选择结束日期和时间");content.addView(endButton,Ui.lp(activity,-1,52));Ui.gap(content,18);
            preview=Ui.bold(activity,"",17,Ui.GREEN);preview.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);content.addView(preview);Ui.gap(content,8);content.addView(Ui.text(activity,"支持跨日；单次最长 366 天。",12,Ui.MUTED));Ui.gap(content,8);
            error=Ui.text(activity,"",13,0xffa34232);error.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);content.addView(error);
            setView(scroll);setButton(BUTTON_NEGATIVE,"取消",(dialog,which)->{});setButton(BUTTON_POSITIVE,"保存记录",(dialog,which)->{});
            super.onCreate(state);getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);getButton(BUTTON_POSITIVE).setOnClickListener(v->save());update();
        }
        private void pickDate(boolean first){
            if(saving)return;LocalDateTime chosen=first?start:end;
            DatePickerDialog picker=new DatePickerDialog(activity,(view,year,month,day)->{
                LocalDate date=LocalDate.of(year,month+1,day);
                TimePickerDialog time=new TimePickerDialog(activity,(clock,hour,minute)->{
                    LocalDateTime result=LocalDateTime.of(date,LocalTime.of(hour,minute));if(first)start=result;else end=result;error.setText("");update();
                },chosen.getHour(),chosen.getMinute(),true);time.setTitle(first?"开始时间":"结束时间");time.show();
            },chosen.getYear(),chosen.getMonthValue()-1,chosen.getDayOfMonth());
            picker.setTitle(first?"开始日期":"结束日期");picker.getDatePicker().setMinDate(0);picker.getDatePicker().setMaxDate(7258118399999L);picker.show();
        }
        private long millis(LocalDateTime time){return time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();}
        private void update(){startButton.setText(FORMAT.format(start));endButton.setText(FORMAT.format(end));long duration=millis(end)-millis(start);preview.setText(duration>0?"记录时长  "+TimeStats.duration(duration):"请选择晚于开始的结束时间");}
        private void save(){
            if(saving)return;
            ZoneId zone=ZoneId.systemDefault();
            if(zone.getRules().getValidOffsets(start).isEmpty()||zone.getRules().getValidOffsets(end).isEmpty()){error.setText("所选时间因夏令时调整而不存在，请重新选择。");return;}
            long from=millis(start),to=millis(end),duration=to-from;
            if(from<0||to>7258118400000L){error.setText("请选择 1970 至 2199 年内的时间。");return;}
            if(duration<=0){error.setText("结束时间须晚于开始时间。");return;}
            if(duration>MAX_DURATION){error.setText("单次记录不能超过 366 天，请分次补记。");return;}
            TimeRecord record=new TimeRecord();record.token=token;record.label=name.getText().toString().trim();record.start=from;record.end=to;record.duration=duration;record.segments.add(new TimeRecord.Segment(from,to,duration));
            saving=true;setCancelable(false);getButton(BUTTON_POSITIVE).setEnabled(false);getButton(BUTTON_NEGATIVE).setEnabled(false);name.setEnabled(false);startButton.setEnabled(false);endButton.setEnabled(false);error.setText("正在保存…");
            ExecutorService executor=Executors.newSingleThreadExecutor();executor.execute(()->{
                String failure=null;try(EventStore store=new EventStore(activity.getApplicationContext())){store.saveTimeRecord(record);}catch(RuntimeException ex){failure="保存失败，请重试。";}final String result=failure;executor.shutdown();
                activity.runOnUiThread(()->{
                    if(activity.isFinishing()||activity.isDestroyed())return;
                    saving=false;if(result==null){dismiss();Ui.toast(activity,"已保存学习记录");if(onSaved!=null)onSaved.run();}
                    else{setCancelable(true);getButton(BUTTON_POSITIVE).setEnabled(true);getButton(BUTTON_NEGATIVE).setEnabled(true);name.setEnabled(true);startButton.setEnabled(true);endButton.setEnabled(true);error.setText(result);}
                });
            });
        }
    }
}

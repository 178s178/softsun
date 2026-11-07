package cn.qingri.app;

import android.app.Activity;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import java.lang.ref.WeakReference;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** The model can draft a plan here; only the explicit adoption action writes it. */
public class AgentActivity extends Activity {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("M月d日 EEEE",Locale.CHINA);
    private static final int IDLE=0, GENERATING=1, APPLYING=2;

    private final ExecutorService executor=Executors.newCachedThreadPool();
    private final Set<Long> movableSelection=new LinkedHashSet<>();
    private final List<CheckBox> movableBoxes=new ArrayList<>();
    private final List<CheckBox> slotBoxes=new ArrayList<>();
    private final List<AgentPlanner.Slot> displayedSlots=new ArrayList<>();
    private SharedPreferences drafts;
    private LocalDate date;
    private LocalTime windowStart,windowEnd;
    private EditText goal,focus,rest;
    private TextView dateValue,startValue,endValue,modelValue,modeValue,generate,configure,memoryButton,adopt,progress,cancel;
    private ProgressBar spinner;
    private LinearLayout movableList,preview;
    private AgentService.Outcome outcome;
    private AgentService activeService,loadingService;
    private Future<?> operation,loading;
    private long operationSerial,loadSerial;
    private int working=IDLE;
    private boolean foreground,destroyed,initializing=true,movableLoaded,movableLoading,interruptedAdoption;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        drafts=getSharedPreferences("agent_draft",MODE_PRIVATE);
        interruptedAdoption=saved!=null?saved.getBoolean("was_saving"):drafts.getBoolean("was_saving",false);
        date=parseDate(value(saved,"date",LocalDate.now().toString()));
        if(saved==null) {
            String launchDate=getIntent().getStringExtra("date");
            if(launchDate!=null)try{date=LocalDate.parse(launchDate);}catch(RuntimeException ignored){}
        }
        windowStart=parseTime(value(saved,"start","08:00"),LocalTime.of(8,0));
        windowEnd=parseTime(value(saved,"end","22:00"),LocalTime.of(22,0));
        for(String id:value(saved,"movable","").split(",")) {
            try { if(!id.isEmpty()) movableSelection.add(Long.parseLong(id)); } catch(NumberFormatException ignored) {}
        }
        if(saved==null&&!date.toString().equals(drafts.getString("date",date.toString())))movableSelection.clear();

        LinearLayout root=Ui.column(this);
        root.setBackgroundColor(Ui.BG);Ui.window(this,root);setContentView(root);
        LinearLayout bar=Ui.row(this);
        bar.setPadding(Ui.dp(this,16),Ui.dp(this,12),Ui.dp(this,20),Ui.dp(this,12));
        bar.addView(Ui.iconButton(this,"back","返回",this::onBackPressed),Ui.lp(this,42,42));
        TextView title=Ui.bold(this,"日程 Agent",21,Ui.INK);title.setPadding(Ui.dp(this,12),0,0,0);Ui.weight(bar,title);
        configure=Ui.button(this,"模型设置",false,this::showConfiguration);configure.setId(403);
        bar.addView(configure,Ui.lp(this,-2,44));root.addView(bar);

        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);
        root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout body=Ui.column(this);
        body.setPadding(Ui.dp(this,22),Ui.dp(this,10),Ui.dp(this,22),Ui.dp(this,24));scroll.addView(body);
        body.addView(Ui.bold(this,"给目标，留一段好时光",23,Ui.INK));Ui.gap(body,9);
        body.addView(paragraph("说说想完成什么，让 DeepSeek 在空闲时间里提供安排。建议需要你确认采纳，才会保存到日程。",13,Ui.MUTED));Ui.gap(body,17);

        LinearLayout modelCard=card(body);
        modelValue=Ui.bold(this,"DeepSeek · 待配置",14,Ui.GREEN);modelCard.addView(modelValue);Ui.gap(modelCard,7);
        modelCard.addView(paragraph("在线生成前会发送：本次目标、勾选日程的标题、当天占用时段，以及近 7 天的学习总量。不会发送日程备注或地点。\n"+AgentMemoryDialog.DISCLOSURE,12,Ui.MUTED));
        Ui.gap(modelCard,12);
        memoryButton=Ui.button(this,"Agent 记忆",false,this::showMemory);memoryButton.setId(409);
        modelCard.addView(memoryButton,Ui.lp(this,-1,-2));Ui.gap(body,18);

        LinearLayout timeCard=card(body);
        dateValue=timeRow(timeCard,"安排日期",405,this::chooseDate);Ui.gap(timeCard,11);
        startValue=timeRow(timeCard,"可用开始",406,()->chooseTime(true));Ui.gap(timeCard,11);
        endValue=timeRow(timeCard,"可用结束",407,()->chooseTime(false));refreshTimes();Ui.gap(body,20);

        goal=field(body,"这次想完成什么？","例如：复习高数两小时，完成英语阅读；先处理紧急作业。可写任务时长、优先级和截止时间。",value(saved,"goal",""),true);goal.setId(401);
        LinearLayout durations=Ui.row(this);
        LinearLayout focusColumn=Ui.column(this),restColumn=Ui.column(this);
        focus=numberField(focusColumn,"专注块 · 分钟",value(saved,"focus","50"));
        rest=numberField(restColumn,"块间休息 · 分钟",value(saved,"rest","10"));
        Ui.weight(durations,focusColumn);View spacer=new View(this);durations.addView(spacer,Ui.lp(this,12,1));Ui.weight(durations,restColumn);body.addView(durations);
        Ui.gap(body,7);body.addView(Ui.text(this,"专注 15–120 分钟 · 休息 0–60 分钟",11,Ui.MUTED));Ui.gap(body,21);

        body.addView(Ui.bold(this,"允许调整的原日程",15,Ui.INK));Ui.gap(body,7);
        body.addView(paragraph("只列出所选日期尚未开始、未完成且不重复的日程。勾选后可调整时间；未勾选的日程保持固定占用。",12,Ui.MUTED));Ui.gap(body,10);
        movableList=card(body);movableList.addView(Ui.text(this,"正在读取日程…",13,Ui.MUTED));Ui.gap(body,10);
        modeValue=Ui.bold(this,"生成模式 · 原日程全部固定",12,Ui.GREEN);body.addView(modeValue);Ui.gap(body,20);

        preview=Ui.column(this);preview.setVisibility(View.GONE);body.addView(preview,Ui.lp(this,-1,-2));
        adopt=Ui.button(this,"采纳选中的建议",true,this::applySelection);adopt.setId(404);adopt.setVisibility(View.GONE);
        body.addView(adopt,Ui.lp(this,-1,52));

        LinearLayout footer=Ui.column(this);footer.setBackgroundColor(Ui.WHITE);
        footer.setPadding(Ui.dp(this,22),Ui.dp(this,11),Ui.dp(this,22),Ui.dp(this,14));
        LinearLayout statusRow=Ui.row(this);
        spinner=new ProgressBar(this);spinner.setIndeterminate(true);spinner.setVisibility(View.GONE);
        statusRow.addView(spinner,Ui.lp(this,20,20));
        progress=Ui.text(this,"配置模型后，就可以生成建议。",12,Ui.MUTED);progress.setId(408);
        progress.setPadding(Ui.dp(this,6),0,0,0);progress.setLineSpacing(Ui.dp(this,3),1);Ui.weight(statusRow,progress);
        footer.addView(statusRow);Ui.gap(footer,9);
        LinearLayout actions=Ui.row(this);
        generate=Ui.button(this,"配置 DeepSeek 后生成",true,this::generatePlan);generate.setId(402);Ui.weight(actions,generate);
        cancel=Ui.button(this,"停止",false,this::stopOperation);cancel.setVisibility(View.GONE);
        LinearLayout.LayoutParams cancelParams=Ui.lp(this,76,48);cancelParams.leftMargin=Ui.dp(this,10);actions.addView(cancel,cancelParams);
        footer.addView(actions);root.addView(footer);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE|WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);

        watch(goal);watch(focus);watch(rest);initializing=false;
        if(interruptedAdoption)
            status("上次保存过程被中断，请先到日程页确认是否已保存，再重新生成。",false);
        else if((saved!=null&&saved.getBoolean("had_preview"))||drafts.getBoolean("had_preview",false))
            status("上次未采纳的建议尚未保存，请重新生成。",false);
        else if(saved!=null&&saved.getBoolean("was_working"))
            status("上次任务已停止，输入已保留，可以重新生成。",false);
        else if(AgentSettings.configured(this))status("输入目标，生成可确认的日程建议。",false);
        updateControls();
    }

    private String value(Bundle saved,String key,String fallback) {
        return saved!=null&&saved.containsKey(key)?saved.getString(key,fallback):drafts.getString(key,fallback);
    }
    private static LocalDate parseDate(String text) {try{return LocalDate.parse(text);}catch(Exception ignored){return LocalDate.now();}}
    private static LocalTime parseTime(String text,LocalTime fallback) {try{return LocalTime.parse(text);}catch(Exception ignored){return fallback;}}

    private TextView paragraph(String text,int size,int color) {
        TextView view=Ui.text(this,text,size,color);view.setLineSpacing(Ui.dp(this,4),1);return view;
    }
    private LinearLayout card(LinearLayout parent) {
        LinearLayout card=Ui.column(this);Ui.pad(card,16);card.setBackground(Ui.shape(Ui.WHITE,17,this));parent.addView(card,Ui.lp(this,-1,-2));return card;
    }
    private TextView timeRow(LinearLayout parent,String label,int id,Runnable action) {
        LinearLayout row=Ui.row(this);Ui.weight(row,Ui.bold(this,label,13,Ui.INK));
        TextView value=Ui.bold(this,"",14,Ui.GREEN);value.setId(id);value.setGravity(Gravity.CENTER_VERTICAL);
        value.setMinHeight(Ui.dp(this,36));value.setPadding(Ui.dp(this,12),0,0,0);
        row.addView(value);row.setOnClickListener(v->{if(working==IDLE)action.run();});
        value.setOnClickListener(v->{if(working==IDLE)action.run();});parent.addView(row);return value;
    }
    private EditText field(LinearLayout body,String label,String hint,String text,boolean multiline) {
        body.addView(Ui.bold(this,label,14,Ui.INK));Ui.gap(body,9);
        EditText input=new EditText(this);styleInput(input);input.setHint(hint);input.setText(text);
        input.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE|InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setGravity(Gravity.TOP);input.setMinLines(4);input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(2000)});
        body.addView(input,Ui.lp(this,-1,multiline?140:52));Ui.gap(body,18);return input;
    }
    private EditText numberField(LinearLayout parent,String label,String value) {
        parent.addView(Ui.bold(this,label,12,Ui.INK));Ui.gap(parent,8);
        EditText input=new EditText(this);styleInput(input);input.setInputType(InputType.TYPE_CLASS_NUMBER);input.setSingleLine(true);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(3)});input.setText(value);parent.addView(input,Ui.lp(this,-1,50));return input;
    }
    private void styleInput(EditText input) {
        input.setTextSize(15);input.setTextColor(Ui.INK);input.setHintTextColor(Ui.MUTED);
        input.setBackground(Ui.shape(Ui.WHITE,13,this));input.setPadding(Ui.dp(this,14),Ui.dp(this,13),Ui.dp(this,14),Ui.dp(this,13));
    }
    private void watch(EditText input) {
        input.addTextChangedListener(new TextWatcher(){
            public void beforeTextChanged(CharSequence text,int start,int count,int after){}
            public void onTextChanged(CharSequence text,int start,int before,int count){if(!initializing){invalidatePreview("输入已改变，请重新生成建议。");saveDraft();}}
            public void afterTextChanged(Editable value){}
        });
    }
    private void refreshTimes() {
        dateValue.setText(date.format(DAY));dateValue.setContentDescription("安排日期 "+date);
        startValue.setText(windowStart.format(TIME));startValue.setContentDescription("可用开始 "+windowStart.format(TIME));
        endValue.setText(windowEnd.format(TIME));endValue.setContentDescription("可用结束 "+windowEnd.format(TIME));
    }
    private void chooseDate() {
        DatePickerDialog dialog=new DatePickerDialog(this,(picker,y,m,d)->{
            if(working!=IDLE||destroyed)return;
            LocalDate chosen=LocalDate.of(y,m+1,d);if(chosen.equals(date))return;
            date=chosen;movableSelection.clear();movableLoaded=false;refreshTimes();
            invalidatePreview("日期已改变，请重新生成建议。");saveDraft();loadMovableEvents();
        },date.getYear(),date.getMonthValue()-1,date.getDayOfMonth());
        dialog.getDatePicker().setMinDate(LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli());
        dialog.getDatePicker().setMaxDate(LocalDate.of(2199,12,31).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli());dialog.show();
    }
    private void chooseTime(boolean first) {
        LocalTime initial=first?windowStart:windowEnd;
        new TimePickerDialog(this,(picker,h,m)->{
            if(working!=IDLE||destroyed)return;
            LocalTime chosen=LocalTime.of(h,m);if(chosen.equals(first?windowStart:windowEnd))return;
            if(first)windowStart=chosen;else windowEnd=chosen;
            refreshTimes();invalidatePreview("可用时间已改变，请重新生成建议。");saveDraft();
        },initial.getHour(),initial.getMinute(),true).show();
    }

    private void showConfiguration() {
        if(working!=IDLE)return;
        AgentSettings.show(this,()->{if(!destroyed){updateControls();if(AgentSettings.configured(this))status("DeepSeek 已配置，可以生成建议。",false);}});
    }
    private void showMemory() {
        if(working!=IDLE||destroyed)return;
        AgentMemoryDialog.show(this,()->{
            if(destroyed)return;
            refreshMemoryStatus();
            if(outcome!=null){
                invalidatePreview("Agent 记忆已更新，请重新生成建议。");
                saveDraft();
            }
        },this::useRememberedDefaults);
    }
    private void useRememberedDefaults(AgentMemory.Defaults defaults) {
        if(working!=IDLE||destroyed)return;
        windowStart=defaults.windowStart;
        windowEnd=defaults.windowEnd;
        initializing=true;
        try {
            focus.setText(Integer.toString(defaults.focusMinutes));
            rest.setText(Integer.toString(defaults.breakMinutes));
        } finally {
            initializing=false;
        }
        focus.setError(null);rest.setError(null);
        refreshTimes();
        invalidatePreview("已填入上次采纳的安排参数，请重新生成建议。");
        saveDraft();
        Ui.toast(this,"已将上次安排参数填入草稿");
    }
    private void refreshMemoryStatus() {
        memoryButton.setText("Agent 记忆 · "+AgentMemoryDialog.status(this));
    }
    private void updateControls() {
        if(generate==null)return;
        boolean ready=AgentSettings.configured(this),idle=working==IDLE;
        modelValue.setText("DeepSeek · "+AgentSettings.model(this)+(ready?" · 已配置":" · 待配置密钥"));
        generate.setText(working==GENERATING?"正在生成…":working==APPLYING?"正在保存…":ready?"生成日程建议":"配置 DeepSeek 后生成");
        generate.setEnabled(idle&&(!ready||!movableLoading));generate.setAlpha(generate.isEnabled()?1f:.55f);
        configure.setEnabled(idle);configure.setAlpha(idle?1f:.55f);
        memoryButton.setEnabled(idle);memoryButton.setAlpha(idle?1f:.55f);
        spinner.setVisibility(idle?View.GONE:View.VISIBLE);cancel.setVisibility(working==GENERATING?View.VISIBLE:View.GONE);
        goal.setEnabled(idle);focus.setEnabled(idle);rest.setEnabled(idle);
        dateValue.setEnabled(idle);startValue.setEnabled(idle);endValue.setEnabled(idle);
        for(CheckBox box:movableBoxes)box.setEnabled(idle);
        for(CheckBox box:slotBoxes)box.setEnabled(idle);
        updateAdoption();
    }
    private void updateMode() {
        modeValue.setText(movableSelection.isEmpty()?"生成模式 · 原日程全部固定":"优化模式 · 允许调整 "+movableSelection.size()+" 项原日程");
    }
    private void status(String message,boolean error) {
        progress.setText(message);progress.setTextColor(error?0xFF9C4E3C:Ui.MUTED);
    }

    private interface Delivery {void receive(AgentActivity activity);}
    private static void deliver(WeakReference<AgentActivity> reference,long token,boolean isLoad,Delivery delivery) {
        MAIN.post(()->{
            AgentActivity activity=reference.get();
            if(activity==null||activity.destroyed||!activity.foreground)return;
            if(token!=(isLoad?activity.loadSerial:activity.operationSerial))return;
            delivery.receive(activity);
        });
    }
    private void loadMovableEvents() {
        if(!foreground||destroyed)return;
        cancelLoading();movableLoading=true;movableLoaded=false;
        movableList.removeAllViews();movableBoxes.clear();movableList.addView(Ui.text(this,"正在读取日程…",13,Ui.MUTED));updateControls();
        final LocalDate chosenDate=date;final long token=++loadSerial;
        final AgentService service=new AgentService(getApplicationContext());loadingService=service;
        final WeakReference<AgentActivity> reference=new WeakReference<>(this);
        loading=executor.submit(()->{
            try {
                final List<Event> events=service.movableEvents(chosenDate);
                deliver(reference,token,true,activity->activity.showMovableEvents(events));
            } catch(Exception error) {
                final String message=safeMessage(error,"读取日程失败，请稍后重新打开此页。");
                deliver(reference,token,true,activity->{
                    activity.movableLoading=false;activity.movableLoaded=false;activity.loading=null;activity.loadingService=null;
                    activity.movableList.removeAllViews();activity.movableList.addView(activity.paragraph(message,12,Ui.MUTED));
                    activity.status("无法核对原日程，请点击重试读取。",true);
                    TextView retry=Ui.button(activity,"重新读取日程",false,activity::loadMovableEvents);activity.movableList.addView(retry);
                    activity.updateControls();
                    // Generating without this list could silently omit intended movable choices.
                    activity.generate.setEnabled(!AgentSettings.configured(activity));
                    activity.generate.setAlpha(activity.generate.isEnabled()?1f:.55f);
                });
            }
        });
    }
    private void showMovableEvents(List<Event> events) {
        movableLoading=false;movableLoaded=true;loading=null;loadingService=null;
        movableList.removeAllViews();movableBoxes.clear();Set<Long> available=new LinkedHashSet<>();
        LocalDateTime now=LocalDateTime.now();
        for(Event event:events) {
            if(event==null||event.start==null||event.end==null||!event.start.toLocalDate().equals(date)||!event.start.isAfter(now)||!"none".equals(event.repeat))continue;
            available.add(event.id);
            CheckBox box=new CheckBox(this);box.setTextColor(Ui.INK);box.setTextSize(13);box.setMinHeight(Ui.dp(this,52));
            box.setText(event.title+"\n"+event.start.format(TIME)+"–"+event.end.format(TIME));
            box.setContentDescription("允许调整 "+event.title+" "+event.start.format(TIME)+"至"+event.end.format(TIME));
            box.setChecked(movableSelection.contains(event.id));
            box.setOnCheckedChangeListener((button,checked)->{
                if(checked)movableSelection.add(event.id);else movableSelection.remove(event.id);
                updateMode();invalidatePreview("允许调整的日程已改变，请重新生成。");saveDraft();
            });
            movableBoxes.add(box);movableList.addView(box,Ui.lp(this,-1,-2));
        }
        if(movableSelection.retainAll(available))invalidatePreview("原日程已有变化，请重新生成建议。");
        if(movableBoxes.isEmpty())movableList.addView(paragraph("这一天没有可调整的未来非重复日程。你仍可以描述新目标，生成空闲时段的安排。",12,Ui.MUTED));
        updateMode();updateControls();saveDraft();
    }

    private void generatePlan() {
        if(working!=IDLE)return;
        if(!AgentSettings.configured(this)){showConfiguration();return;}
        if(movableLoading)return;
        if(!movableLoaded){status("请先重新读取原日程。",true);loadMovableEvents();return;}
        final AgentService.Input input=new AgentService.Input();
        input.date=date;input.windowStart=windowStart;input.windowEnd=windowEnd;input.goal=goal.getText().toString().trim();
        if(input.goal.isEmpty()){goal.setError("写下想完成的目标");goal.requestFocus();return;}
        if(date.isBefore(LocalDate.now())){status("请选择今天或未来的日期。",true);return;}
        if(!windowEnd.isAfter(windowStart)){status("可用结束时间需要晚于开始时间。",true);return;}
        if(date.atTime(windowEnd).isBefore(LocalDateTime.now())){status("今天的可用时间已结束，请调整时间或日期。",true);return;}
        try {input.focusMinutes=Integer.parseInt(focus.getText().toString());}catch(NumberFormatException error){input.focusMinutes=-1;}
        try {input.breakMinutes=Integer.parseInt(rest.getText().toString());}catch(NumberFormatException error){input.breakMinutes=-1;}
        if(input.focusMinutes<15||input.focusMinutes>120){focus.setError("请输入 15–120");focus.requestFocus();return;}
        if(input.breakMinutes<0||input.breakMinutes>60){rest.setError("请输入 0–60");rest.requestFocus();return;}
        input.movableIds.addAll(movableSelection);saveDraft();
        invalidatePreview("");working=GENERATING;final long token=++operationSerial;
        final AgentService service=new AgentService(getApplicationContext());activeService=service;
        status("正在连接 DeepSeek，读取占用与学习总量…",false);updateControls();
        final WeakReference<AgentActivity> reference=new WeakReference<>(this);
        operation=executor.submit(()->{
            try {
                final AgentService.Outcome result=service.generate(input,message->deliver(reference,token,false,activity->activity.status(message,false)));
                deliver(reference,token,false,activity->{activity.finishOperation();activity.showPreview(result);});
            } catch(Exception error) {
                final String message=safeMessage(error,"生成失败，请检查网络和模型配置后重试。");
                deliver(reference,token,false,activity->{activity.finishOperation();activity.status(message,true);});
            }
        });
    }
    private void showPreview(AgentService.Outcome result) {
        if(result==null||result.plan==null){status("未收到有效的日程建议，请重新生成。",true);return;}
        outcome=result;preview.removeAllViews();displayedSlots.clear();slotBoxes.clear();preview.setVisibility(View.VISIBLE);
        preview.addView(Ui.bold(this,"建议预览 · 尚未保存",18,Ui.INK));Ui.gap(preview,9);
        if(result.summary!=null&&!result.summary.trim().isEmpty()){preview.addView(paragraph(result.summary,13,Ui.INK));Ui.gap(preview,12);}
        preview.addView(paragraph("选择要采纳的条目后，再点击下方按钮。部分采纳也会重新校验原日程与时间冲突。",12,Ui.MUTED));Ui.gap(preview,12);
        for(AgentPlanner.Slot slot:result.plan.slots) {
            if(slot==null||slot.task==null||slot.start==null||slot.end==null)continue;
            LinearLayout item=card(preview);CheckBox checkbox=new CheckBox(this);checkbox.setTextColor(Ui.INK);checkbox.setTextSize(14);checkbox.setChecked(true);
            String title=slot.task.title;
            if(slot.totalParts>1)title+=" · "+slot.part+"/"+slot.totalParts;
            String time=slot.start.format(TIME)+"–"+slot.end.format(TIME);
            checkbox.setText(title+"\n"+time+(slot.task.sourceId>0?" · 调整原日程":" · 新安排"));
            checkbox.setContentDescription("采纳 "+title+" "+slot.start.toLocalDate()+" "+time);
            checkbox.setOnCheckedChangeListener((button,checked)->updateAdoption());item.addView(checkbox,Ui.lp(this,-1,-2));
            if(slot.task.sourceId>0) {
                Event original=result.originals.get(slot.task.sourceId);
                if(original!=null&&original.start!=null&&original.end!=null){Ui.gap(item,5);item.addView(paragraph("原时间："+original.start.format(TIME)+"–"+original.end.format(TIME),11,Ui.MUTED));}
            }
            if(slot.reason!=null&&!slot.reason.trim().isEmpty()){Ui.gap(item,6);item.addView(paragraph(slot.reason,12,Ui.MUTED));}
            displayedSlots.add(slot);slotBoxes.add(checkbox);Ui.gap(preview,10);
        }
        if(result.plan.slots.isEmpty()){preview.addView(paragraph("本次没有可采纳的时间安排。可以调整可用时间或目标后重试。",13,Ui.MUTED));Ui.gap(preview,12);}
        if(!result.plan.warnings.isEmpty()) {
            preview.addView(Ui.bold(this,"需要留意",14,Ui.INK));Ui.gap(preview,7);
            for(String warning:result.plan.warnings){preview.addView(paragraph("• "+warning,12,Ui.MUTED));Ui.gap(preview,6);}
            Ui.gap(preview,10);
        }
        if(!result.plan.unscheduled.isEmpty()) {
            preview.addView(Ui.bold(this,"暂未排入",14,Ui.INK));Ui.gap(preview,7);
            for(AgentPlanner.Task task:result.plan.unscheduled){preview.addView(paragraph("• "+task.title+" · "+task.minutes+" 分钟",12,Ui.MUTED));Ui.gap(preview,6);}
            Ui.gap(preview,10);
        }
        status("建议已生成，确认采纳后才会写入日程。",false);updateControls();saveDraft();
    }
    private void updateAdoption() {
        if(adopt==null)return;int selected=0;for(CheckBox box:slotBoxes)if(box.isChecked())selected++;
        adopt.setVisibility(outcome!=null&&!displayedSlots.isEmpty()?View.VISIBLE:View.GONE);
        adopt.setText(working==APPLYING?"正在保存…":"采纳选中的 "+selected+" 项建议");
        adopt.setEnabled(working==IDLE&&selected>0);adopt.setAlpha(adopt.isEnabled()?1f:.55f);
    }
    private void applySelection() {
        if(working!=IDLE||outcome==null)return;
        final List<AgentPlanner.Slot> selected=new ArrayList<>();
        for(int i=0;i<slotBoxes.size();i++)if(slotBoxes.get(i).isChecked())selected.add(displayedSlots.get(i));
        if(selected.isEmpty()){status("先选择至少一项建议。",true);return;}
        final AgentService.Outcome plan=outcome;working=APPLYING;final long token=++operationSerial;
        final AgentService service=new AgentService(getApplicationContext());activeService=service;
        status("正在核对日程变化并保存选中建议…",false);updateControls();
        final WeakReference<AgentActivity> reference=new WeakReference<>(this);
        operation=executor.submit(()->{
            try {
                final AgentService.Applied result=service.apply(plan,selected);
                deliver(reference,token,false,activity->{
                    activity.finishOperation();activity.interruptedAdoption=false;activity.invalidatePreview("");activity.setResult(RESULT_OK);
                    activity.status(result.alreadyApplied?"这份计划已经采纳，无需重复保存。":"已采纳：新增 "+result.added+" 项，调整 "+result.moved+" 项日程。",false);
                    activity.movableLoaded=false;activity.loadMovableEvents();activity.saveDraft();
                });
            } catch(Exception error) {
                final String message=safeMessage(error,"保存失败，建议仍保留，可以重试或重新生成。");
                deliver(reference,token,false,activity->{activity.finishOperation();activity.status(message+"\n建议仍保留，可重试采纳或重新生成。",true);});
            }
        });
    }
    private static String safeMessage(Exception error,String fallback) {
        if(error instanceof CancellationException||error instanceof InterruptedException)return "任务已停止，未采纳的建议不会保存。";
        if(!(error instanceof IllegalArgumentException)&&!(error instanceof IllegalStateException))return fallback;
        String message=error.getMessage();return message==null||message.trim().isEmpty()?fallback:message;
    }
    private void finishOperation() {working=IDLE;operation=null;activeService=null;updateControls();}
    private void stopOperation() {
        if(working==IDLE)return;boolean saving=working==APPLYING;
        if(saving)interruptedAdoption=true;
        ++operationSerial;if(activeService!=null)activeService.cancel();if(operation!=null)operation.cancel(true);
        finishOperation();
        status(saving?"保存过程已中断；再次采纳时会核对是否已经保存。":"生成已停止，输入已保留。",false);
    }
    private void cancelLoading() {
        ++loadSerial;if(loadingService!=null)loadingService.cancel();if(loading!=null)loading.cancel(true);
        loading=null;loadingService=null;movableLoading=false;
    }
    private void invalidatePreview(String message) {
        boolean had=outcome!=null;outcome=null;displayedSlots.clear();slotBoxes.clear();
        if(preview!=null){preview.removeAllViews();preview.setVisibility(View.GONE);}if(adopt!=null)adopt.setVisibility(View.GONE);
        if(had&&!message.isEmpty())status(message,false);
    }
    private String selectedIds() {StringBuilder text=new StringBuilder();for(Long id:movableSelection){if(text.length()>0)text.append(',');text.append(id);}return text.toString();}
    private Bundle captureDraft() {
        Bundle state=new Bundle();
        state.putString("date",date.toString());state.putString("start",windowStart.toString());state.putString("end",windowEnd.toString());
        state.putString("goal",goal.getText().toString());state.putString("focus",focus.getText().toString());state.putString("rest",rest.getText().toString());
        state.putString("movable",selectedIds());state.putBoolean("had_preview",outcome!=null);state.putBoolean("was_saving",interruptedAdoption||working==APPLYING);
        return state;
    }
    private void saveDraft() {
        if(drafts==null||goal==null)return;
        saveDraft(captureDraft());
    }
    private void saveDraft(Bundle state) {
        SharedPreferences.Editor editor=drafts.edit();
        for(String key:state.keySet()) {
            Object value=state.get(key);
            if(value instanceof String)editor.putString(key,(String)value);
            else if(value instanceof Boolean)editor.putBoolean(key,(Boolean)value);
        }
        editor.apply();
    }
    @Override protected void onStart() {
        super.onStart();foreground=true;refreshMemoryStatus();updateControls();if(!movableLoaded)loadMovableEvents();
    }
    @Override protected void onStop() {
        saveDraft();foreground=false;if(working!=IDLE)stopOperation();cancelLoading();
        // Refresh the list after returning: another screen may have edited a schedule.
        movableLoaded=false;super.onStop();
    }
    @Override protected void onSaveInstanceState(Bundle saved) {
        Bundle state=captureDraft();saved.putAll(state);saved.putBoolean("was_working",working!=IDLE);
        saveDraft(state);super.onSaveInstanceState(saved);
    }
    @Override protected void onDestroy() {
        destroyed=true;++operationSerial;cancelLoading();if(activeService!=null)activeService.cancel();
        if(operation!=null)operation.cancel(true);executor.shutdownNow();super.onDestroy();
    }
    @Override public void onBackPressed() {
        if(working==APPLYING){Ui.toast(this,"正在保存日程，请稍候。重复点击不会重复保存。");return;}
        if(working==GENERATING)stopOperation();saveDraft();super.onBackPressed();
    }
}

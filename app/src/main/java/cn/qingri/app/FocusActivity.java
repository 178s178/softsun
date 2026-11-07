package cn.qingri.app;

import android.app.*;
import android.graphics.Typeface;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.time.*;

public class FocusActivity extends Activity {
    private StudySession session;
    private TextView clock;
    private EditText sessionName;
    private long lastSession=-1,lastCheckpoint;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Runnable tick=new Runnable(){public void run(){if(lastSession<0&&!session.isCurrent()){session=new StudySession(FocusActivity.this);render();}if(clock!=null&&lastSession<0)clock.setText(StudySession.format(session.timer.elapsed(SystemClock.elapsedRealtime())));if(lastSession<0&&session.timer.isRunning()&&SystemClock.elapsedRealtime()-lastCheckpoint>15000){session.checkpoint();lastCheckpoint=SystemClock.elapsedRealtime();}handler.postDelayed(this,250);}};
    @Override public void onCreate(Bundle state){super.onCreate(state);session=new StudySession(this);if(state!=null)lastSession=state.getLong("lastSession",-1);render();if(session.rebooted)Ui.toast(this,"设备已重启，计时已暂停并保留最后保存的时长");}
    @Override protected void onResume(){super.onResume();if(lastSession<0&&!session.isCurrent()){session=new StudySession(this);render();}handler.post(tick);}
    @Override protected void onPause(){handler.removeCallbacks(tick);if(lastSession<0)session.checkpoint();super.onPause();}
    @Override protected void onSaveInstanceState(Bundle b){b.putLong("lastSession",lastSession);super.onSaveInstanceState(b);}
    private void render(){
        LinearLayout root=Ui.column(this);root.setBackgroundColor(Ui.BG);Ui.window(this,root);setContentView(root);
        LinearLayout header=Ui.row(this);header.setPadding(Ui.dp(this,18),Ui.dp(this,12),Ui.dp(this,22),Ui.dp(this,12));header.addView(Ui.iconButton(this,"back","返回日程",()->finish()),Ui.lp(this,42,42));TextView heading=Ui.bold(this,"学习模式",21,Ui.INK);heading.setPadding(Ui.dp(this,13),0,0,0);Ui.weight(header,heading);root.addView(header);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));LinearLayout body=Ui.column(this);body.setPadding(Ui.dp(this,24),Ui.dp(this,9),Ui.dp(this,24),Ui.dp(this,24));scroll.addView(body);
        body.addView(Ui.centered(Ui.text(this,"把注意力，交还给当下。",14,Ui.MUTED)));Ui.gap(body,22);body.addView(new NatureView(this),Ui.lp(this,-1,205));Ui.gap(body,31);
        sessionName=null;
        if(session.startedWall==0&&lastSession<0){sessionName=new EditText(this);sessionName.setId(202);sessionName.setText(session.label);sessionName.setHint("这次学什么？（可选）");sessionName.setTextSize(14);sessionName.setSingleLine(true);sessionName.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(80)});sessionName.setBackground(Ui.shape(Ui.WHITE,13,this));sessionName.setPadding(Ui.dp(this,14),0,Ui.dp(this,14),0);body.addView(sessionName,Ui.lp(this,-1,46));Ui.gap(body,18);}
        else if(!session.label.isEmpty()){body.addView(Ui.centered(Ui.bold(this,session.label,15,Ui.INK)));Ui.gap(body,14);}
        boolean started=session.startedWall>0;String state=lastSession>=0?"这一段专注，值得被记住。":session.timer.isRunning()?"正在学习 · 按自己的节奏":started?"暂歇片刻，也没关系":"自由计时 · 不设终点";
        body.addView(Ui.centered(Ui.bold(this,state,14,Ui.GREEN)));Ui.gap(body,16);
        clock=Ui.centered(Ui.bold(this,StudySession.format(lastSession>=0?lastSession:session.timer.elapsed(SystemClock.elapsedRealtime())),49,Ui.INK));clock.setTypeface(Typeface.create("sans-serif-light",Typeface.NORMAL));clock.setId(201);body.addView(clock);Ui.gap(body,12);
        body.addView(Ui.centered(Ui.text(this,lastSession>=0?"本次有效学习时长":"开始、暂停和结束，都由你决定",12,Ui.MUTED)));Ui.gap(body,25);
        long[] stats;try(EventStore store=new EventStore(this)){long from=LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(),to=LocalDate.now().plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();stats=store.studyStats(from,to);}
        LinearLayout summary=Ui.row(this);summary.setBackground(Ui.shape(Ui.PALE,18,this));summary.setPadding(Ui.dp(this,12),Ui.dp(this,18),Ui.dp(this,12),Ui.dp(this,18));
        LinearLayout duration=Ui.column(this);duration.addView(Ui.centered(Ui.bold(this,String.valueOf(stats[0]/60000),26,Ui.GREEN)));Ui.gap(duration,7);duration.addView(Ui.centered(Ui.text(this,"今日完成 · 分钟",10,Ui.GREEN)));Ui.weight(summary,duration);
        LinearLayout count=Ui.column(this);count.addView(Ui.centered(Ui.bold(this,String.valueOf(stats[1]),26,Ui.GREEN)));Ui.gap(count,7);count.addView(Ui.centered(Ui.text(this,"今日完成 · 次",10,Ui.GREEN)));Ui.weight(summary,count);body.addView(summary);Ui.gap(body,22);
        Switch keep=new Switch(this);keep.setText("保持屏幕常亮");keep.setTextSize(13);keep.setTextColor(Ui.MUTED);keep.setChecked(getPreferences(0).getBoolean("keep",false));setAwake(keep.isChecked());keep.setOnCheckedChangeListener((v,on)->{getPreferences(0).edit().putBoolean("keep",on).apply();setAwake(on);});body.addView(keep,Ui.lp(this,-1,44));Ui.gap(body,10);
        TextView hint=Ui.centered(Ui.text(this,"离开页面后仍会计时，随时回来暂停或结束。",11,Ui.MUTED));hint.setLineSpacing(Ui.dp(this,4),1);body.addView(hint);
        LinearLayout footer=Ui.column(this);footer.setBackgroundColor(Ui.WHITE);footer.setPadding(Ui.dp(this,24),Ui.dp(this,14),Ui.dp(this,24),Ui.dp(this,18));
        if(lastSession>=0){footer.addView(Ui.button(this,"再开始一段",true,()->{lastSession=-1;session=new StudySession(this);session.start();render();}),Ui.lp(this,-1,52));}
        else if(!started)footer.addView(Ui.button(this,"开始学习",true,()->{if(sessionName!=null)session.label=sessionName.getText().toString().trim();session.start();render();}),Ui.lp(this,-1,52));
        else{LinearLayout controls=Ui.row(this);TextView pause=Ui.button(this,session.timer.isRunning()?"暂停":"继续学习",false,()->{if(session.timer.isRunning())session.pause();else session.start();render();});LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,Ui.dp(this,52),1);p.rightMargin=Ui.dp(this,12);controls.addView(pause,p);TextView end=Ui.button(this,"结束本次",true,()->{try{lastSession=session.finish();render();}catch(RuntimeException ex){Ui.toast(this,"学习记录保存失败，请稍后重试");}});controls.addView(end,new LinearLayout.LayoutParams(0,Ui.dp(this,52),1));footer.addView(controls);}
        root.addView(footer);
    }
    private void setAwake(boolean on){if(on)getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);}
}

package cn.qingri.app;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Paint;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.text.*;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class MainActivity extends Activity {
    private EventStore store;
    private LocalDate selected=LocalDate.now();
    private YearMonth month=YearMonth.now();
    private int tab=0;
    private String category="全部",query="";
    private boolean searchVisible=false,testAfterGrant=false;
    private LinearLayout listArea;
    private List<Event> events=new ArrayList<>();
    private Set<String> completed=new HashSet<>();
    private final DateTimeFormatter time=DateTimeFormatter.ofPattern("HH:mm");

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);store=new EventStore(this);
        if(b!=null) { tab=b.getInt("tab");selected=LocalDate.parse(b.getString("selected",LocalDate.now().toString()));month=YearMonth.parse(b.getString("month",YearMonth.now().toString())); }
        readDateIntent(getIntent());
    }
    private void readDateIntent(Intent i) { try { if(i.hasExtra("date")) { selected=LocalDate.parse(i.getStringExtra("date"));month=YearMonth.from(selected);tab=0; } }catch(Exception ignored){} }
    @Override protected void onNewIntent(Intent i) { super.onNewIntent(i);setIntent(i);readDateIntent(i);render(); }
    @Override protected void onResume() { super.onResume();ReminderScheduler.reschedule(this);render(); }
    @Override protected void onDestroy() { if(store!=null)store.close();super.onDestroy(); }
    @Override protected void onSaveInstanceState(Bundle b) { b.putInt("tab",tab);b.putString("selected",selected.toString());b.putString("month",month.toString());super.onSaveInstanceState(b); }
    private LinearLayout marginColumn(LinearLayout parent) { LinearLayout c=Ui.column(this);c.setPadding(Ui.dp(this,20),0,Ui.dp(this,20),0);parent.addView(c,new LinearLayout.LayoutParams(-1,-2));return c; }
    private void render() {
        events=store.list();completed=store.completions();
        LinearLayout root=Ui.column(this);root.setBackgroundColor(Ui.BG);Ui.window(this,root);setContentView(root);
        LinearLayout header=Ui.row(this);header.setPadding(Ui.dp(this,22),Ui.dp(this,14),Ui.dp(this,20),Ui.dp(this,14));
        Ui.Icon logo=new Ui.Icon(this,"calendar",Ui.GREEN);header.addView(logo,Ui.lp(this,27,27));
        LinearLayout brand=Ui.column(this);brand.setPadding(Ui.dp(this,10),0,0,0);brand.addView(Ui.bold(this,"轻日",23,Ui.INK));Ui.gap(brand,3);brand.addView(Ui.text(this,"让每一天，轻一点",10,Ui.MUTED));Ui.weight(header,brand);
        TextView agent=Ui.button(this,"日程 Agent",false,()->startActivity(new Intent(this,AgentActivity.class).putExtra("date",selected.toString())));agent.setTextSize(12);agent.setContentDescription("日程Agent");header.addView(agent);
        if(tab!=2)header.addView(Ui.iconButton(this,"search","搜索日程",()->{searchVisible=!searchVisible;if(!searchVisible)query="";render();}),Ui.lp(this,44,44));
        root.addView(header);
        FrameLayout frame=new FrameLayout(this);root.addView(frame,new LinearLayout.LayoutParams(-1,0,1));
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setClipToPadding(false);frame.addView(scroll,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout content=Ui.column(this);content.setPadding(0,Ui.dp(this,4),0,Ui.dp(this,88));scroll.addView(content);
        LinearLayout body=marginColumn(content);
        if(tab==2) buildSettings(body);else {
            if(tab==0)buildAgenda(body);else buildCalendar(body);
            if(searchVisible)buildSearch(body);
            buildFilters(body);
            listArea=Ui.column(this);body.addView(listArea);refreshList();
        }
        if(tab!=2) {
            Ui.Icon fab=new Ui.Icon(this,"plus",Ui.WHITE);fab.setPadding(Ui.dp(this,17),Ui.dp(this,17),Ui.dp(this,17),Ui.dp(this,17));fab.setContentDescription("新增日程");fab.setBackground(Ui.shape(Ui.GREEN,21,this));fab.setElevation(Ui.dp(this,7));fab.setOnClickListener(v->openEditor(null));
            FrameLayout.LayoutParams fp=new FrameLayout.LayoutParams(Ui.dp(this,60),Ui.dp(this,60),Gravity.END|Gravity.BOTTOM);fp.setMargins(0,0,Ui.dp(this,22),Ui.dp(this,20));frame.addView(fab,fp);
        }
        LinearLayout nav=Ui.row(this);nav.setBackgroundColor(Ui.WHITE);nav.setPadding(Ui.dp(this,20),Ui.dp(this,8),Ui.dp(this,20),Ui.dp(this,7));
        String[] labels={"日程","月历","学习","记录","设置"},icons={"empty","calendar","leaf","pie","settings"};
        for(int i=0;i<5;i++){ final int index=i;boolean active=(i==4&&tab==2)||(i<2&&tab==i);LinearLayout item=Ui.column(this);item.setGravity(Gravity.CENTER);Ui.Icon icon=new Ui.Icon(this,icons[i],active?Ui.GREEN:Ui.MUTED);item.addView(icon,Ui.lp(this,23,23));Ui.gap(item,5);item.addView(Ui.centered(Ui.bold(this,i==2&&StudySession.active(this)?"学习中":labels[i],11,active?Ui.GREEN:Ui.MUTED)));item.setContentDescription(labels[i]);item.setOnClickListener(v->{if(index==2){startActivity(new Intent(this,FocusActivity.class));return;}if(index==3){startActivity(new Intent(this,RecordsActivity.class).putExtra("date",selected.toString()).putExtra("monthly",tab==1));return;}tab=index==4?2:index;query="";searchVisible=false;render();});nav.addView(item,new LinearLayout.LayoutParams(0,Ui.dp(this,50),1));}
        root.addView(nav);
    }
    private void buildAgenda(LinearLayout body) {
        LinearLayout title=Ui.row(this);LinearLayout words=Ui.column(this);
        words.addView(Ui.text(this,selected.format(DateTimeFormatter.ofPattern("yyyy 年 M 月 d 日")),12,Ui.MUTED));Ui.gap(words,7);
        words.addView(Ui.bold(this,selected.equals(LocalDate.now())?"今天，慢慢来。":selected.format(DateTimeFormatter.ofPattern("M月d日，安排一下。")),27,Ui.INK));Ui.weight(title,words);
        TextView today=Ui.button(this,"今天",false,()->{selected=LocalDate.now();month=YearMonth.from(selected);render();});title.addView(today);body.addView(title);Ui.gap(body,19);
        List<Occurrence> todayItems=forDate(selected,false);int done=0;for(Occurrence o:todayItems)if(o.completed)done++;
        LinearLayout hero=Ui.row(this);hero.setBackground(Ui.shape(Ui.GREEN,22,this));hero.setPadding(Ui.dp(this,21),Ui.dp(this,19),Ui.dp(this,16),Ui.dp(this,19));
        LinearLayout copy=Ui.column(this);copy.addView(Ui.text(this,"给重要的事，一点时间",12,0xFFD3E4D9));Ui.gap(copy,13);copy.addView(Ui.bold(this,(todayItems.size()-done)+" 件待办",29,Ui.WHITE));Ui.gap(copy,11);copy.addView(Ui.text(this,todayItems.isEmpty()?"从一件小事，开启新的一天":"已完成 "+done+" / "+todayItems.size()+" 项 · 按自己的节奏就好",11,0xFFD3E4D9));Ui.weight(hero,copy);hero.addView(new Ui.Ring(this,done,todayItems.size()),Ui.lp(this,92,92));body.addView(hero);Ui.gap(body,17);
        LinearLayout week=Ui.row(this);
        LocalDate monday=selected.minusDays(selected.getDayOfWeek().getValue()-1);
        for(int n=0;n<7;n++){LocalDate d=monday.plusDays(n);boolean active=d.equals(selected);LinearLayout day=Ui.column(this);day.setGravity(Gravity.CENTER);day.setBackground(Ui.shape(active?Ui.GREEN:Ui.WHITE,14,this));day.addView(Ui.centered(Ui.text(this,new String[]{"一","二","三","四","五","六","日"}[n],10,active?0xFFD8E7DD:Ui.MUTED)));Ui.gap(day,8);day.addView(Ui.centered(Ui.bold(this,String.valueOf(d.getDayOfMonth()),19,active?Ui.WHITE:Ui.INK)));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,Ui.dp(this,68),1);p.setMargins(Ui.dp(this,2),0,Ui.dp(this,2),0);week.addView(day,p);day.setContentDescription(d.toString());day.setOnClickListener(v->{selected=d;month=YearMonth.from(d);render();});}
        body.addView(week);Ui.gap(body,20);
    }
    private void buildCalendar(LinearLayout body) {
        LinearLayout row=Ui.row(this);LinearLayout title=Ui.column(this);title.addView(Ui.text(this,"留一点空白，装下生活",12,Ui.MUTED));Ui.gap(title,7);title.addView(Ui.bold(this,month.getYear()+" 年 "+month.getMonthValue()+" 月",26,Ui.INK));Ui.weight(row,title);
        row.addView(Ui.iconButton(this,"prev","上个月",()->{month=month.minusMonths(1);selected=month.atDay(1);render();}),Ui.lp(this,38,38));Ui.gap(body,3);
        row.addView(Ui.iconButton(this,"next","下个月",()->{month=month.plusMonths(1);selected=month.atDay(1);render();}),Ui.lp(this,38,38));body.addView(row);Ui.gap(body,18);
        Set<LocalDate> occupied=new HashSet<>();
        for(Event e:events)for(Occurrence o:ScheduleEngine.occurrences(e,month.atDay(1),month.atEndOfMonth(),completed)) {
            LocalDate first=o.start.toLocalDate().isBefore(month.atDay(1))?month.atDay(1):o.start.toLocalDate();LocalDate last=o.end.minusNanos(1).toLocalDate();if(last.isAfter(month.atEndOfMonth()))last=month.atEndOfMonth();
            for(LocalDate d=first;!d.isAfter(last);d=d.plusDays(1))occupied.add(d);
        }
        body.addView(new MonthGrid(this,month,selected,occupied,d->{selected=d;render();}));Ui.gap(body,14);
        TextView today=Ui.text(this,"回到今天",13,Ui.GREEN);today.setGravity(Gravity.END);today.setPadding(0,Ui.dp(this,6),0,Ui.dp(this,6));today.setOnClickListener(v->{selected=LocalDate.now();month=YearMonth.from(selected);render();});body.addView(today);Ui.gap(body,8);
    }
    private void buildSearch(LinearLayout body) {
        EditText search=new EditText(this);search.setId(108);search.setSingleLine(true);search.setTextSize(14);search.setTextColor(Ui.INK);search.setHintTextColor(Ui.MUTED);search.setHint("搜索标题、备注或地点");search.setText(query);search.setBackground(Ui.outline(Ui.WHITE,Ui.LINE,13,this));search.setPadding(Ui.dp(this,14),Ui.dp(this,12),Ui.dp(this,14),Ui.dp(this,12));body.addView(search,Ui.lp(this,-1,48));Ui.gap(body,12);
        search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int f){}public void onTextChanged(CharSequence s,int a,int b,int c){query=s.toString();refreshList();}public void afterTextChanged(Editable e){}});
    }
    private void buildFilters(LinearLayout body) {
        HorizontalScrollView scroll=new HorizontalScrollView(this);scroll.setHorizontalScrollBarEnabled(false);LinearLayout chips=Ui.row(this);scroll.addView(chips);
        for(String label:new String[]{"全部","生活","学习","工作","其他"}){TextView t=Ui.bold(this,label,12,label.equals(category)?Ui.GREEN:Ui.MUTED);t.setGravity(Gravity.CENTER);t.setPadding(Ui.dp(this,15),Ui.dp(this,8),Ui.dp(this,15),Ui.dp(this,8));t.setBackground(Ui.shape(label.equals(category)?Ui.PALE:Ui.WHITE,18,this));LinearLayout.LayoutParams p=Ui.lp(this,-2,36);p.rightMargin=Ui.dp(this,8);chips.addView(t,p);t.setOnClickListener(v->{category=label;render();});}
        body.addView(scroll);Ui.gap(body,18);
    }
    private List<Occurrence> forDate(LocalDate date,boolean filter) {
        List<Occurrence> result=new ArrayList<>();
        for(Event e:events)if(!filter||matches(e))result.addAll(ScheduleEngine.occurrences(e,date,date,completed));
        Collections.sort(result,(a,b)->{if(a.completed!=b.completed)return a.completed?1:-1;int t=a.start.compareTo(b.start);return t!=0?t:Integer.compare(b.event.priority,a.event.priority);});return result;
    }
    private boolean matches(Event e) { String q=query.trim().toLowerCase(Locale.ROOT);return (category.equals("全部")||category.equals(e.category))&&(q.isEmpty()||(e.title+" "+e.note+" "+e.location).toLowerCase(Locale.ROOT).contains(q)); }
    private void refreshList() {
        if(listArea==null||tab==2)return;listArea.removeAllViews();
        List<Occurrence> items;
        if(!query.trim().isEmpty()) {
            items=new ArrayList<>();LocalDate from=LocalDate.now().minusDays(30),to=LocalDate.now().plusDays(365);
            for(Event e:events)if(matches(e)) { if("none".equals(e.repeat))items.addAll(ScheduleEngine.occurrences(e,e.start.toLocalDate(),e.end.toLocalDate(),completed));else items.addAll(ScheduleEngine.occurrences(e,from,to,completed)); }
            Collections.sort(items,(a,b)->a.start.compareTo(b.start));listArea.addView(Ui.bold(this,"搜索结果 · "+items.size()+" 项",16,Ui.INK));Ui.gap(listArea,6);listArea.addView(Ui.text(this,"重复事项搜索范围：过去30天至未来一年",11,Ui.MUTED));
        }else { items=forDate(selected,true);LinearLayout heading=Ui.row(this);Ui.weight(heading,Ui.bold(this,selected.getMonthValue()+"月"+selected.getDayOfMonth()+"日 · 日程",17,Ui.INK));heading.addView(Ui.text(this,items.size()+" 项",12,Ui.MUTED));listArea.addView(heading); }
        Ui.gap(listArea,14);
        if(items.isEmpty())emptyState(listArea);else for(int i=0;i<Math.min(items.size(),300);i++)addCard(listArea,items.get(i),!query.trim().isEmpty());
        if(items.size()>300)listArea.addView(Ui.text(this,"已展示前300项，请缩小搜索范围",12,Ui.MUTED));
        if(query.trim().isEmpty()&&selected.equals(LocalDate.now())) {
            List<Occurrence> overdue=new ArrayList<>();LocalDate today=LocalDate.now();
            for(Event e:events)if(matches(e)) {
                LocalDate from="none".equals(e.repeat)?e.start.toLocalDate():today.minusDays(30);
                for(Occurrence o:ScheduleEngine.occurrences(e,from,today,completed))if(!o.completed&&!o.end.isAfter(today.atStartOfDay()))overdue.add(o);
            }
            Collections.sort(overdue,(a,b)->b.start.compareTo(a.start));
            if(!overdue.isEmpty()){Ui.gap(listArea,22);listArea.addView(Ui.bold(this,"还有这些，等你安排",16,Ui.INK));Ui.gap(listArea,6);listArea.addView(Ui.text(this,"往日未完成 · 重复事项显示近30天",11,Ui.MUTED));Ui.gap(listArea,12);for(int i=0;i<Math.min(overdue.size(),100);i++)addCard(listArea,overdue.get(i),true);}
        }
    }
    private void emptyState(LinearLayout area) {
        LinearLayout empty=Ui.column(this);empty.setGravity(Gravity.CENTER);empty.setPadding(Ui.dp(this,16),Ui.dp(this,30),Ui.dp(this,16),Ui.dp(this,33));Ui.Icon icon=new Ui.Icon(this,"empty",Ui.GREEN);empty.addView(icon,Ui.lp(this,48,48));Ui.gap(empty,17);empty.addView(Ui.centered(Ui.bold(this,query.isEmpty()?"这一天，留白也很好":"没有找到相关日程",16,Ui.INK)));Ui.gap(empty,9);TextView tip=Ui.text(this,query.isEmpty()?"点右下角 +，安排第一件小事":"试试其他关键词或切换分类",12,Ui.MUTED);tip.setGravity(Gravity.CENTER);empty.addView(tip);area.addView(empty,Ui.lp(this,-1,-2));
    }
    private void addCard(LinearLayout area,Occurrence o,boolean includeDate) {
        LinearLayout card=Ui.row(this);card.setBackground(Ui.shape(Ui.WHITE,17,this));Ui.pad(card,15);LinearLayout.LayoutParams cp=Ui.lp(this,-1,-2);cp.bottomMargin=Ui.dp(this,10);area.addView(card,cp);
        View stripe=new View(this);stripe.setBackground(Ui.shape(Ui.category(o.event.category),2,this));card.addView(stripe,Ui.lp(this,3,48));
        LinearLayout detail=Ui.column(this);detail.setPadding(Ui.dp(this,12),0,Ui.dp(this,10),0);
        String stamp=(includeDate?o.start.format(DateTimeFormatter.ofPattern("M/d ")):"")+o.start.format(time)+" – "+(o.end.toLocalDate().equals(o.start.toLocalDate())?o.end.format(time):o.end.format(DateTimeFormatter.ofPattern("M/d HH:mm")));
        detail.addView(Ui.text(this,stamp,11,Ui.MUTED));Ui.gap(detail,7);TextView name=Ui.bold(this,o.event.title,16,o.completed?Ui.MUTED:Ui.INK);name.setMaxLines(2);name.setEllipsize(TextUtils.TruncateAt.END);if(o.completed)name.setPaintFlags(name.getPaintFlags()|Paint.STRIKE_THRU_TEXT_FLAG);detail.addView(name);Ui.gap(detail,9);
        String tags=o.event.category+(o.event.priority==2?"  ·  高优先":"")+(!"none".equals(o.event.repeat)?"  ·  "+repeatLabel(o.event.repeat):"")+(o.event.location.isEmpty()?"":"  ·  "+o.event.location);
        TextView tag=Ui.text(this,tags,10,Ui.category(o.event.category));tag.setMaxLines(1);tag.setEllipsize(TextUtils.TruncateAt.END);detail.addView(tag);Ui.weight(card,detail);
        Ui.Icon check=new Ui.Icon(this,o.completed?"check":"circle",o.completed?Ui.GREEN:0xFFB9C6BB);check.setPadding(Ui.dp(this,9),Ui.dp(this,9),Ui.dp(this,9),Ui.dp(this,9));check.setContentDescription(o.completed?"取消完成："+o.event.title:"完成："+o.event.title);card.addView(check,Ui.lp(this,44,44));check.setOnClickListener(v->{store.setCompleted(o.key(),!o.completed);((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).cancel((int)o.event.id);ReminderScheduler.reschedule(this);render();});card.setOnClickListener(v->showDetails(o));
    }
    public static String repeatLabel(String value) { switch(value){case "daily":return "每天";case "weekdays":return "工作日";case "weekly":return "每周";case "monthly":return "每月";default:return "不重复";} }
    private void openEditor(Event e) { Intent i=new Intent(this,EditorActivity.class).putExtra("date",selected.toString());if(e!=null)i.putExtra("id",e.id);startActivity(i); }
    private void showDetails(Occurrence o) {
        String text=o.start.format(DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm"))+"\n至 "+o.end.format(DateTimeFormatter.ofPattern("M月d日 HH:mm"))+"\n\n"+o.event.category+" · "+repeatLabel(o.event.repeat)+(o.event.reminder<0?" · 不提醒":" · 提前"+o.event.reminder+"分钟提醒")+(o.event.location.isEmpty()?"":"\n地点："+o.event.location)+(o.event.note.isEmpty()?"":"\n\n"+o.event.note);
        new AlertDialog.Builder(this).setTitle(o.event.title).setMessage(text).setPositiveButton("编辑",(d,w)->openEditor(o.event)).setNeutralButton("删除",(d,w)->new AlertDialog.Builder(this).setTitle("删除日程？").setMessage("none".equals(o.event.repeat)?"删除后无法撤销。":"这会删除整个重复系列及其完成记录。若只完成这一次，请点击卡片右侧圆圈。").setNegativeButton("取消",null).setPositiveButton("删除",(dialog,which)->{store.delete(o.event.id);ReminderScheduler.reschedule(this);render();}).show()).setNegativeButton("关闭",null).show();
    }
    private void buildSettings(LinearLayout body) {
        body.addView(Ui.bold(this,"把生活，安排轻一点。",25,Ui.INK));Ui.gap(body,9);body.addView(Ui.text(this,"提醒与数据，都由你掌握",12,Ui.MUTED));Ui.gap(body,23);
        LinearLayout privacy=Ui.column(this);privacy.setBackground(Ui.shape(Ui.PALE,20,this));Ui.pad(privacy,20);privacy.addView(Ui.bold(this,"数据，由你掌握",18,Ui.GREEN));Ui.gap(privacy,10);
        TextView desc=Ui.text(this,"日程与学习记录默认保存在本机，离线可用。卸载前请导出备份。\n主动生成日程建议时，会向 DeepSeek 发送目标、选中标题、占用时段与近 7 天学习总量。不会发送日程备注或地点。\n"+AgentMemoryDialog.DISCLOSURE,12,Ui.GREEN);
        desc.setLineSpacing(Ui.dp(this,5),1);privacy.addView(desc);body.addView(privacy);Ui.gap(body,25);
        body.addView(Ui.bold(this,"日程 Agent",16,Ui.INK));Ui.gap(body,12);
        settingRow(body,"DeepSeek 接口",AgentSettings.configured(this)?"已保存密钥 · "+AgentSettings.model(this):"密钥待填写 · 默认 DeepSeek",()->AgentSettings.show(this,()->render()));
        settingRow(body,"Agent 记忆",AgentMemoryDialog.status(this)+" · 管理长期偏好与最近采纳参数",()->AgentMemoryDialog.show(this,this::render,null)).setId(410);
        settingRow(body,"生成与优化日程","先看建议，再选择采纳",()->startActivity(new Intent(this,AgentActivity.class).putExtra("date",selected.toString())));Ui.gap(body,18);
        body.addView(Ui.bold(this,"让提醒准时到达",16,Ui.INK));Ui.gap(body,12);
        settingRow(body,"通知权限",ReminderScheduler.notificationsAllowed(this)?"已开启":"未开启 · 点击设置",()->requestNotifications(false));
        settingRow(body,"准时提醒",ReminderScheduler.exactAllowed(this)?"精确闹钟已允许":"未授权 · 提醒可能延迟",()->{
            if(Build.VERSION.SDK_INT>=31)openSystem(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,Uri.parse("package:"+getPackageName())));else Ui.toast(this,"此系统支持精确提醒");
        });
        settingRow(body,"后台与省电设置","小米手机请检查自启动和后台省电限制",()->openSystem(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName()))));
        settingRow(body,"10 秒后测试提醒","点按后可锁屏，检查是否收到通知",()->{if(!ReminderScheduler.notificationsAllowed(this))requestNotifications(true);else {ReminderScheduler.test(this);Ui.toast(this,"已安排，约10秒后发送测试通知");}});
        TextView note=Ui.text(this,"关闭通知会阻止提醒。未允许精确闹钟时，系统可能推迟提醒；强行停止应用后，需重新打开才能恢复。",11,Ui.MUTED);note.setLineSpacing(Ui.dp(this,4),1);body.addView(note);Ui.gap(body,24);
        body.addView(Ui.bold(this,"备份与恢复",16,Ui.INK));Ui.gap(body,12);
        settingRow(body,"导出备份","保存日程、完成记录与学习统计为 JSON",()->{Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/json").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,"轻日备份-"+LocalDate.now()+".json");try{startActivityForResult(i,11);}catch(ActivityNotFoundException e){Ui.toast(this,"系统没有可用的文件保存应用");}});
        settingRow(body,"从备份恢复","确认后替换日程及已完成学习记录",()->{Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);try{startActivityForResult(i,12);}catch(ActivityNotFoundException e){Ui.toast(this,"系统没有可用的文件选择器");}});
        Ui.gap(body,18);TextView version=Ui.text(this,"轻日 1.4.0\n给生活留白，也给重要的事留位置。",11,Ui.MUTED);version.setGravity(Gravity.CENTER);version.setLineSpacing(Ui.dp(this,7),1);body.addView(version);
    }
    private LinearLayout settingRow(LinearLayout parent,String title,String desc,Runnable action) {
        LinearLayout row=Ui.row(this);row.setBackground(Ui.shape(Ui.WHITE,15,this));Ui.pad(row,16);LinearLayout copy=Ui.column(this);copy.addView(Ui.bold(this,title,14,Ui.INK));Ui.gap(copy,6);TextView sub=Ui.text(this,desc,11,Ui.MUTED);sub.setLineSpacing(Ui.dp(this,3),1);copy.addView(sub);Ui.weight(row,copy);Ui.Icon arrow=new Ui.Icon(this,"next",Ui.MUTED);row.addView(arrow,Ui.lp(this,16,16));LinearLayout.LayoutParams p=Ui.lp(this,-1,-2);p.bottomMargin=Ui.dp(this,9);parent.addView(row,p);row.setOnClickListener(v->action.run());
        return row;
    }
    private void openSystem(Intent i) { try { startActivity(i); }catch(ActivityNotFoundException ex){Ui.toast(this,"请在系统设置的应用管理中调整权限");} }
    private void requestNotifications(boolean test) {
        testAfterGrant=test;
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) {
            if(getPreferences(0).getBoolean("notification_asked",false)&&!shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS))openSystem(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,getPackageName()));
            else {getPreferences(0).edit().putBoolean("notification_asked",true).apply();requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},3);}
        } else if(!ReminderScheduler.notificationsAllowed(this))openSystem(new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,getPackageName()).putExtra(Settings.EXTRA_CHANNEL_ID,"qingri_schedule"));
        else if(test){ReminderScheduler.test(this);Ui.toast(this,"约10秒后发送测试通知");}else Ui.toast(this,"通知已经开启");
    }
    @Override public void onRequestPermissionsResult(int code,String[] names,int[] results) {
        super.onRequestPermissionsResult(code,names,results);if(code==3){if(ReminderScheduler.notificationsAllowed(this)&&testAfterGrant){ReminderScheduler.test(this);Ui.toast(this,"约10秒后发送测试通知");}ReminderScheduler.reschedule(this);render();testAfterGrant=false;}
    }
    @Override protected void onActivityResult(int req,int result,Intent data) {
        super.onActivityResult(req,result,data);if(result!=RESULT_OK||data==null||data.getData()==null)return;Uri uri=data.getData();
        try {
            if(req==11) { String exported=store.exportJson();try(OutputStream out=getContentResolver().openOutputStream(uri,"wt")){if(out==null)throw new IOException();out.write(exported.getBytes(StandardCharsets.UTF_8));}Ui.toast(this,"备份已保存"); }
            if(req==12) { String json;try(InputStream in=getContentResolver().openInputStream(uri);ByteArrayOutputStream out=new ByteArrayOutputStream()){if(in==null)throw new IOException();byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1){out.write(buffer,0,n);if(out.size()>EventStore.MAX_BACKUP_BYTES)throw new IOException("备份文件不能超过64MB");}json=new String(out.toByteArray(),StandardCharsets.UTF_8);}
                final String backup=json;
                new AlertDialog.Builder(this).setTitle("确认恢复备份？").setMessage("这会替换当前全部日程、完成记录和已完成的学习记录。旧版备份仅替换日程。建议先导出当前数据；校验失败时会保留原数据。").setNegativeButton("取消",null).setPositiveButton("恢复",(d,w)->{try{store.importJson(backup);ReminderScheduler.reschedule(this);render();Ui.toast(this,"日程已恢复");}catch(IllegalArgumentException ex){Ui.toast(this,"备份格式不正确，原数据已保留");}}).show();
            }
        }catch(Exception ex){Ui.toast(this,ex.getMessage()!=null&&ex.getMessage().contains("64MB")?ex.getMessage():"文件操作失败，请换一个文件或保存位置");}
    }
}

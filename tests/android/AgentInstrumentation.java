package cn.qingri.app.tests;

import android.app.*;
import android.content.*;
import android.os.Bundle;
import cn.qingri.app.*;
import java.time.*;
import java.util.*;
import org.json.*;

/** All data is synthetic and isolated to the QA emulator's app sandbox. */
public class AgentInstrumentation extends Instrumentation {
    int checks;
    void check(boolean p,String why){checks++;if(!p)throw new AssertionError(why);}
    void rejected(Runnable action,String why){try{action.run();throw new AssertionError(why);}catch(IllegalArgumentException|IllegalStateException expected){checks++;}}
    @Override public void onCreate(Bundle b){super.onCreate(b);start();}
    Event event(EventStore db,LocalDate d,String title,int h,int m,int minutes){Event e=new Event();e.title=title;e.start=d.atTime(h,m);e.end=e.start.plusMinutes(minutes);e.note="private-note-never-send";e.location="private-location-never-send";db.save(e);return e;}
    DeepSeekAgent.Transport fake(JSONArray tasks,List<String> requests){return new DeepSeekAgent.Transport(){int round;public void cancel(){}public JSONObject request(JSONObject body,String key)throws Exception{
        requests.add(body.toString());check(key.equals("sk-qa-synthetic-only"),"Encrypted key can be read for request");
        JSONObject msg=new JSONObject().put("role","assistant");
        if(round++==0){JSONArray calls=new JSONArray();calls.put(call("context","get_day_context",new JSONObject()));calls.put(call("study","get_study_summary",new JSONObject()));calls.put(call("draft","draft_plan",new JSONObject().put("tasks",tasks)));msg.put("content",JSONObject.NULL).put("tool_calls",calls);}else msg.put("content","已根据目标安排，请确认后采纳。");
        return new JSONObject().put("choices",new JSONArray().put(new JSONObject().put("message",msg).put("finish_reason",round==1?"tool_calls":"stop")));
    }};}
    JSONObject call(String id,String name,JSONObject args)throws Exception{return new JSONObject().put("id",id).put("type","function").put("function",new JSONObject().put("name",name).put("arguments",args.toString()));}
    JSONObject task(String title,int minutes,long source)throws Exception{return new JSONObject().put("title",title).put("minutes",minutes).put("source_id",source).put("splittable",false);}
    AgentService.Outcome generate(Context c,AgentService.Input input,JSONArray tasks,List<String> requests){return new AgentService(c,new DeepSeekAgent(fake(tasks,requests))).generate(input,s->{});}
    private JSONObject dayContext(List<String> requests) throws JSONException {
        JSONArray messages = new JSONObject(requests.get(requests.size() - 1)).getJSONArray("messages");
        for (int i = 0; i < messages.length(); i++) {
            JSONObject message = messages.getJSONObject(i);
            if ("tool".equals(message.optString("role")) && "context".equals(message.optString("tool_call_id"))) {
                return new JSONObject(message.getString("content"));
            }
        }
        throw new AssertionError("Missing get_day_context result in model request");
    }

    private void checkDefaults(Context c, AgentService.Input input, String why) {
        AgentMemory.Defaults saved = AgentMemory.load(c).lastAccepted;
        check(saved != null && input.windowStart.equals(saved.windowStart) && input.windowEnd.equals(saved.windowEnd)
                && input.focusMinutes == saved.focusMinutes && input.breakMinutes == saved.breakMinutes, why);
    }

    private void testPlanningMemory(Context c, EventStore db, LocalDate day) throws Exception {
        try {
            AgentService service = new AgentService(c);
            String preference = "qa-preference-先安排复习";
            AgentMemory.save(c, true, preference);

            AgentService.Input first = new AgentService.Input();
            first.date = day;
            first.goal = "复习单词";
            first.windowStart = LocalTime.of(7, 35);
            first.windowEnd = LocalTime.of(19, 10);
            first.focusMinutes = 37;
            first.breakMinutes = 8;
            Event movable = event(db, day, "待调整的复习", 14, 0, 30);
            first.movableIds.add(movable.id);
            List<String> requests = new ArrayList<>();
            AgentService.Outcome firstPlan = generate(c, first, new JSONArray().put(task("复习", 30, movable.id)), requests);
            JSONObject memory = dayContext(requests).getJSONObject("planning_memory");
            check(memory.length() == 1 && preference.equals(memory.getString("preference_note")),
                    "Opt-in sends the saved preference before any acceptance");
            check(AgentMemory.load(c).lastAccepted == null, "Generation alone does not update memory");
            String payload = String.join("", requests);
            check(!payload.contains(movable.note) && !payload.contains(movable.location) && !payload.contains("sk-qa-synthetic-only"),
                    "Opt-in still omits event notes, locations and API credentials from model messages");
            firstPlan.input.focusMinutes = 120;
            firstPlan.request.breakMinutes = 60;
            check(service.apply(firstPlan, firstPlan.plan.slots).moved == 1, "Opt-in plan can be accepted");
            checkDefaults(c, first, "Acceptance remembers the sealed planning inputs");

            AgentService.Input next = new AgentService.Input();
            next.date = day.plusDays(1);
            next.goal = "整理笔记";
            next.windowStart = LocalTime.of(13, 5);
            next.windowEnd = LocalTime.of(18, 20);
            next.focusMinutes = 46;
            next.breakMinutes = 12;
            JSONArray nextTasks = new JSONArray().put(task("整理笔记", 30, 0));
            requests.clear();
            AgentService.Outcome blocked = generate(c, next, nextTasks, requests);
            memory = dayContext(requests).getJSONObject("planning_memory");
            JSONObject defaults = memory.getJSONObject("last_accepted");
            check(memory.length() == 2 && preference.equals(memory.getString("preference_note")) && defaults.length() == 4
                            && first.windowStart.toString().equals(defaults.getString("window_start"))
                            && first.windowEnd.toString().equals(defaults.getString("window_end"))
                            && first.focusMinutes == defaults.getInt("focus_minutes")
                            && first.breakMinutes == defaults.getInt("break_minutes"),
                    "The context exposes only the explicit preference and four previously accepted defaults");
            AgentPlanner.Slot slot = blocked.plan.slots.get(0);
            event(db, next.date, "新增冲突", slot.start.getHour(), slot.start.getMinute(), 30);
            rejected(() -> service.apply(blocked, blocked.plan.slots), "Conflicting adoption must fail");
            checkDefaults(c, first, "Failed adoption does not replace remembered defaults");

            AgentService.Outcome nextPlan = generate(c, next, nextTasks, new ArrayList<>());
            check(service.apply(nextPlan, nextPlan.plan.slots).added == 1, "A newer feasible plan can be accepted");
            check(service.apply(firstPlan, firstPlan.plan.slots).alreadyApplied, "An old acceptance remains idempotent");
            checkDefaults(c, next, "Replaying an acceptance does not replace newer defaults");

            next.date = day.plusDays(2);
            AgentService.Outcome beforeClear = generate(c, next, nextTasks, new ArrayList<>());
            AgentMemory.save(c, false, preference);
            requests.clear();
            generate(c, next, nextTasks, requests);
            check(!dayContext(requests).has("planning_memory") && !String.join("", requests).contains(preference),
                    "Disabling memory omits retained content from model requests");

            AgentMemory.clear(c);
            AgentMemory.Snapshot cleared = AgentMemory.load(c);
            check(!cleared.enabled && "".equals(cleared.note) && cleared.lastAccepted == null,
                    "Clearing removes both kinds of memory and disables sharing");
            AgentMemory.save(c, true, "");
            check(service.apply(beforeClear, beforeClear.plan.slots).added == 1,
                    "Clearing memory does not invalidate the outstanding schedule draft");
            check(AgentMemory.load(c).lastAccepted == null, "A draft generated before clearing cannot restore defaults");
            requests.clear();
            generate(c, next, nextTasks, requests);
            check(!dayContext(requests).has("planning_memory"), "An enabled empty memory is omitted from model requests");
        } finally {
            AgentMemory.clear(c);
        }
    }

    @Override public void onStart(){Bundle result=new Bundle();try{
        checks+=DeepSeekTests.run();Context c=getTargetContext();String device=(android.os.Build.FINGERPRINT+" "+android.os.Build.MODEL+" "+android.os.Build.PRODUCT).toLowerCase(Locale.ROOT);check(device.contains("generic")||device.contains("emulator")||device.contains("sdk_gphone")||device.contains("sdk_phone"),"Destructive agent fixtures restricted to QA emulator");c.deleteDatabase("qingri.db");AgentSettings.clear(c);
        AgentMemory.clear(c);
        AgentService.Input in=new AgentService.Input();in.date=LocalDate.now().plusDays(2);in.goal="安排复习和休息";in.windowStart=LocalTime.of(9,0);in.windowEnd=LocalTime.of(16,0);
        List<String> requests=new ArrayList<>();rejected(()->generate(c,in,new JSONArray(),requests),"Missing key must fail before network");check(requests.isEmpty(),"Missing key makes zero requests");
        AgentSettings.save(c,"deepseek-flash","sk-qa-synthetic-only");check(AgentSettings.configured(c),"Key configuration saved");
        check(!c.getSharedPreferences("deepseek_private",0).getAll().toString().contains("sk-qa-synthetic-only"),"Key never stored plaintext");
        try(EventStore db=new EventStore(c)){
            Event fixed=event(db,in.date,"private-fixed-title",9,0,60);Event move=event(db,in.date,"高数复习",13,0,60);in.movableIds.add(move.id);
            JSONArray tasks=new JSONArray().put(task("模型不可更改原标题",20,move.id)).put(task("英语练习",30,0));
            AgentService.Outcome out=generate(c,in,tasks,requests);check(out.plan.slots.size()==2,"Real tool loop returns feasible local draft");
            check(db.list().size()==2&&db.find(move.id).start.equals(move.start),"Generation never writes schedule");
            String payload=String.join("",requests);check(!payload.contains("private-note-never-send")&&!payload.contains("private-location-never-send")&&!payload.contains("private-fixed-title"),"Payload omits notes location and fixed titles");
            check(!dayContext(requests).has("planning_memory"), "Default-off memory is omitted from model requests");
            AgentPlanner.Slot moved=out.plan.slots.stream().filter(s->s.task.sourceId>0).findFirst().get();check(moved.task.title.equals(move.title)&&Duration.between(moved.start,moved.end).toMinutes()==60,"Moving preserves authoritative title and duration");
            AgentService service=new AgentService(c);AgentService.Applied applied=service.apply(out,out.plan.slots);check(applied.added==1&&applied.moved==1&&db.list().size()==3,"Explicit acceptance applies chosen changes");
            check(db.find(move.id).note.equals(move.note)&&db.find(move.id).location.equals(move.location),"Moved event retains metadata");check(service.apply(out,out.plan.slots).alreadyApplied&&db.list().size()==3,"Repeated acceptance is idempotent");
            AgentService.Outcome stale=generate(c,in,tasks,new ArrayList<>());Event edited=db.find(move.id);edited.title="用户已修改";db.save(edited);int size=db.list().size();rejected(()->service.apply(stale,stale.plan.slots),"Stale original must be rejected");check(db.list().size()==size,"Stale plan makes no partial writes");
            AgentService.Input fresh=new AgentService.Input();fresh.date=in.date;fresh.goal="写作文";fresh.windowStart=LocalTime.of(17,0);fresh.windowEnd=LocalTime.of(20,0);
            AgentService.Outcome conflict=generate(c,fresh,new JSONArray().put(task("作文",30,0)),new ArrayList<>());AgentPlanner.Slot proposed=conflict.plan.slots.get(0);event(db,in.date,"后来新增",proposed.start.getHour(),proposed.start.getMinute(),30);int before=db.list().size();rejected(()->service.apply(conflict,conflict.plan.slots),"Concurrent new conflict must reject");check(db.list().size()==before,"Conflict does not partially commit");
            AgentService.Outcome mutated=generate(c,fresh,new JSONArray().put(task("作文",30,0)),new ArrayList<>());AgentPlanner.Slot original=mutated.plan.slots.get(0);LocalDateTime sealedStart=original.start;original.start=original.start.minusHours(5);service.apply(mutated,mutated.plan.slots);check(db.list().stream().anyMatch(e->e.title.equals("作文")&&e.start.equals(sealedStart)),"UI-facing draft mutation cannot alter accepted sealed times");
            check(!db.exportJson().contains("sk-qa-synthetic-only"),"Backup excludes credentials");
            LocalDate edgeDay=in.date.plusDays(2);Event fractional=event(db,edgeDay,"非整分钟任务",10,0,60);fractional.end=fractional.end.plusNanos(500000000);db.save(fractional);
            check(new AgentService(c).movableEvents(edgeDay).stream().noneMatch(e->e.id==fractional.id),"Subsecond duration cannot be shortened when moving");
            db.getWritableDatabase().execSQL("UPDATE sqlite_sequence SET seq=2147483647 WHERE name='events'");Event longId=event(db,edgeDay.plusDays(1),"长编号日程",10,0,60);check(longId.id>Integer.MAX_VALUE,"Long-ID fixture is valid SQLite event");
            AgentService.Input longInput=new AgentService.Input();longInput.date=edgeDay.plusDays(1);longInput.goal="调整这项日程";longInput.movableIds.add(longId.id);
            AgentService.Outcome longPlan=generate(c,longInput,new JSONArray().put(task("保留原时长",60,longId.id)),new ArrayList<>());check(longPlan.plan.slots.size()==1&&longPlan.plan.slots.get(0).task.sourceId==longId.id,"Agent supports full-width SQLite event identities");
            AgentService.Input partial=new AgentService.Input();partial.date=edgeDay.plusDays(2);partial.windowStart=LocalTime.of(13,0);partial.windowEnd=LocalTime.of(16,0);partial.goal="调整两项日程";
            Event a=event(db,partial.date,"较低优先",13,0,60),b=event(db,partial.date,"较高优先",14,0,60);b.priority=2;db.save(b);partial.movableIds.add(a.id);partial.movableIds.add(b.id);
            AgentService.Outcome swaps=generate(c,partial,new JSONArray().put(task(a.title,60,a.id)).put(task(b.title,60,b.id)),new ArrayList<>());
            AgentPlanner.Slot onlyA=swaps.plan.slots.stream().filter(s->s.task.sourceId==a.id).findFirst().get();int swapCount=db.list().size();rejected(()->service.apply(swaps,Collections.singletonList(onlyA)),"Partial adoption must retain unselected original occupancy");check(db.find(a.id).start.equals(a.start)&&db.find(b.id).start.equals(b.start)&&db.list().size()==swapCount,"Rejected partial move preserves both originals");
            check(service.apply(swaps,swaps.plan.slots).moved==2,"Complete conflict-free swap is accepted atomically");
            check(AgentMemory.load(c).lastAccepted == null, "Acceptance while memory is disabled does not remember defaults");
            testPlanningMemory(c, db, edgeDay.plusDays(3));
        }
        AgentSettings.clear(c);check(!AgentSettings.configured(c),"Key can be cleared");
        result.putString("stream","PASS: "+checks+" agent assertions\n");finish(Activity.RESULT_OK,result);
    }catch(Throwable ex){result.putString("stream","FAIL: "+android.util.Log.getStackTraceString(ex));finish(Activity.RESULT_CANCELED,result);}}
}

package cn.qingri.app.tests;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inspector.WindowInspector;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;
import cn.qingri.app.AgentMemory;
import cn.qingri.app.AgentSettings;
import java.time.LocalDate;
import java.util.Locale;

/** Real native UI checks without adding a credential or contacting the model. */
public class AgentUiInstrumentation extends Instrumentation {
    private static final String ACTIVITY="cn.qingri.app.AgentActivity";
    private static final String GOAL="QA 日程草稿 · 高数与英语\n先复习 45 分钟，再做阅读。";
    private static final String MEMORY_TITLE="Agent 记忆";
    private static final String CLEAR_MEMORY_TITLE="清除 Agent 记忆？";
    private Activity current;
    private int assertions;
    private int originalOrientation=ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
    private boolean safeEmulator;

    @Override public void onCreate(Bundle arguments) {super.onCreate(arguments);start();}

    @Override public void onStart() {
        Bundle result=new Bundle();
        Throwable failure=null;
        try {runUiTests();} catch(Throwable error) {failure=error;}
        try {cleanup();} catch(Throwable error) {if(failure==null)failure=error;else failure.addSuppressed(error);}
        result.putString("stream",failure==null?"PASS: "+assertions+" agent UI assertions\n"
                :"FAIL: "+android.util.Log.getStackTraceString(failure));
        finish(failure==null?Activity.RESULT_OK:Activity.RESULT_CANCELED,result);
    }

    private void check(boolean value,String reason) {
        assertions++;if(!value)throw new AssertionError(reason);
        Bundle update=new Bundle();update.putString("stream","ok "+assertions+": "+reason+"\n");sendStatus(0,update);
    }

    private void runUiTests() throws Exception {
        Context context=getTargetContext();
        check("cn.qingri.app".equals(context.getPackageName()),"Uses the app target context");
        String device=(Build.FINGERPRINT+" "+Build.MODEL+" "+Build.PRODUCT).toLowerCase(Locale.ROOT);
        safeEmulator=device.contains("generic")||device.contains("emulator")||device.contains("sdk_gphone")||device.contains("sdk_phone");
        check(safeEmulator,"Credential cleanup is restricted to the disposable QA emulator");
        AgentSettings.clear(context);
        AgentMemory.clear(context);
        check(!AgentSettings.configured(context),"The test starts without an API credential");

        LocalDate chosen=LocalDate.now().plusDays(3);
        // A conflicting older draft proves that the launch date is actually honored.
        check(context.getSharedPreferences("agent_draft",0).edit().clear()
                .putString("date",chosen.minusDays(1).toString()).putString("goal","旧草稿")
                .putString("start","09:15").putString("end","21:30")
                .putString("focus","45").putString("rest","5").commit(),"A synthetic input draft is prepared");
        Class<?> type=Class.forName(ACTIVITY);
        current=startActivitySync(new Intent(context,type).putExtra("date",chosen.toString())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
        check(current!=null&&ACTIVITY.equals(current.getClass().getName()),"The real AgentActivity opens");
        runOnMainSync(()->originalOrientation=current.getRequestedOrientation());
        awaitUi("Agent input is ready",activity->activity.findViewById(401) instanceof EditText
                &&activity.findViewById(402)!=null&&activity.findViewById(403)!=null);
        check(ui(activity->("安排日期 "+chosen).contentEquals(activity.findViewById(405).getContentDescription())),
                "Launch Intent date overrides the older saved draft");
        check(ui(activity->"09:15".contentEquals(((TextView)activity.findViewById(406)).getText())
                &&"21:30".contentEquals(((TextView)activity.findViewById(407)).getText())),"Saved availability window is restored");
        check(ui(activity->text(activity,402).contains("配置")&&activity.findViewById(402).isEnabled()),
                "Missing-key action clearly offers configuration");
        check(ui(this::noPreview),"No generated plan or adoption action is fabricated without a key");
        check(ui(activity->containsText(activity.getWindow().getDecorView(),"在线生成前会发送")
                &&containsText(activity.getWindow().getDecorView(),"不会发送日程备注或地点")),"Online data disclosure is visible before generation");

        runOnMainSync(()->((EditText)current.findViewById(401)).setText(GOAL));waitForIdleSync();
        check(ui(activity->GOAL.contentEquals(((EditText)activity.findViewById(401)).getText())),"The goal field accepts a multiline draft");
        clickId(402,"Missing-key generate action opens configuration");
        awaitDialog("DeepSeek configuration opens");
        check(dialog(view->containsText(view,"DeepSeek 设置")&&byDescription(view,"DeepSeek模型") instanceof EditText),
                "The action opens the real DeepSeek configuration dialog");
        check(dialog(view->{
            View field=byDescription(view,"DeepSeek密钥");
            return field instanceof EditText&&((EditText)field).getText().length()==0
                    &&(((EditText)field).getInputType()&InputType.TYPE_MASK_VARIATION)==InputType.TYPE_TEXT_VARIATION_PASSWORD;
        }),"The credential field is empty and masked");
        if(Build.VERSION.SDK_INT>=29)check(dialog(view->view.getLayoutParams() instanceof WindowManager.LayoutParams
                &&((((WindowManager.LayoutParams)view.getLayoutParams()).flags&WindowManager.LayoutParams.FLAG_SECURE)!=0)),
                "The real configuration window sets FLAG_SECURE");
        clickDialogText("取消");
        await("Configuration cancellation closes the dialog",()->!hasDialog());
        check(!AgentSettings.configured(context),"Canceling configuration stores no credential");
        check(ui(this::noPreview),"Canceling missing-key configuration does not generate a preview");
        check(ui(activity->GOAL.contentEquals(((EditText)activity.findViewById(401)).getText())),"Canceling configuration preserves the goal draft");

        // A configuration recreation uses the same save/restore lifecycle as rotation.
        Activity previous=current;
        ActivityMonitor monitor=addMonitor(ACTIVITY,null,false);
        Activity recreated;
        try {
            runOnMainSync(previous::recreate);
            recreated=waitForMonitorWithTimeout(monitor,12000);
        } finally {removeMonitor(monitor);}
        check(recreated!=null&&recreated!=previous,"recreate produces a new monitored AgentActivity instance");
        current=recreated;waitForIdleSync();
        awaitUi("Recreated Agent input is ready",activity->activity.findViewById(401) instanceof EditText);
        check(ui(activity->("安排日期 "+chosen).contentEquals(activity.findViewById(405).getContentDescription())),
                "Chosen date survives recreation");
        check(ui(activity->GOAL.contentEquals(((EditText)activity.findViewById(401)).getText())),"Multiline goal survives recreation");
        check(ui(activity->"09:15".equals(text(activity,406))&&"21:30".equals(text(activity,407))),"Availability window survives recreation");
        check(ui(this::noPreview),"Recreation leaves adoption hidden without a generated plan");
        check(!AgentSettings.configured(context),"Recreation leaves the API key unconfigured");
        clickId(403,"The dedicated model settings entry remains available");
        awaitDialog("Dedicated model settings opens");clickDialogText("取消");
        await("Dedicated configuration dialog closes",()->!hasDialog());
        check(!AgentSettings.configured(context),"Both configuration entry points leave credentials empty after cancel");
        testPlanningMemory(context);
    }

    private void testPlanningMemory(Context context) throws Exception {
        String preference="QA 优先安排上午复习";
        check(ui(activity->"Agent 记忆 · 已关闭".equals(text(activity,409))),"Memory entry starts disabled");
        clickId(409,"The Agent memory entry is available");
        await("Agent memory dialog opens",()->titledDialog(MEMORY_TITLE,root->root.findViewById(411) instanceof Switch));
        check(titledDialog(MEMORY_TITLE,root->!((Switch)root.findViewById(411)).isChecked()
                &&((EditText)root.findViewById(412)).getText().length()==0),"The memory dialog starts off with an empty preference");
        check(titledDialog(MEMORY_TITLE,root->{
            Switch enabled=root.findViewById(411);
            EditText note=root.findViewById(412);
            if(!enabled.isEnabled())return false;
            enabled.performClick();
            note.setText(preference);
            return enabled.isChecked();
        }),"The memory switch and preference field accept edits");
        clickDialogText(root->byText(root,MEMORY_TITLE)!=null,"保存","The memory editor can be saved");
        await("Saving closes the editor and updates the entry",()->!titledDialog(MEMORY_TITLE,root->true)
                &&ui(activity->"Agent 记忆 · 已开启".equals(text(activity,409))));
        AgentMemory.Snapshot saved=AgentMemory.load(context);
        check(saved.enabled&&preference.equals(saved.note),"Saving persists the memory switch and note");

        clickId(409,"Saved memory can be reopened");
        await("Saved preference is shown",()->titledDialog(MEMORY_TITLE,root->((Switch)root.findViewById(411)).isChecked()
                &&preference.contentEquals(((EditText)root.findViewById(412)).getText())));
        clickDialogText(root->byText(root,MEMORY_TITLE)!=null,"清除记忆","Clear requests confirmation");
        await("Memory clear confirmation opens",()->titledDialog(CLEAR_MEMORY_TITLE,root->byText(root,"清除")!=null));
        check(AgentMemory.load(context).enabled,"Requesting confirmation leaves memory enabled");
        clickDialogText(root->byText(root,CLEAR_MEMORY_TITLE)!=null,"清除","The clear confirmation can be accepted");
        await("Clearing disables the memory entry",()->ui(activity->"Agent 记忆 · 已关闭".equals(text(activity,409))));
        clickId(409,"Cleared memory can be reopened");
        check(titledDialog(MEMORY_TITLE,root->!((Switch)root.findViewById(411)).isChecked()
                &&((EditText)root.findViewById(412)).getText().length()==0
                &&((TextView)root.findViewById(413)).getText().toString().startsWith("暂无")),"Clearing leaves the editor disabled and empty");
        clickDialogText(root->byText(root,MEMORY_TITLE)!=null,"取消","The empty memory editor can be closed");
    }

    private void cleanup() throws Exception {
        if(!safeEmulator)return;
        // Never retain even a pre-existing synthetic credential after this no-key QA run.
        AgentSettings.clear(getTargetContext());
        AgentMemory.clear(getTargetContext());
        if(current!=null) {
            if(titledDialog(CLEAR_MEMORY_TITLE,root->true))sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
            if(hasDialog()||titledDialog(MEMORY_TITLE,root->true))sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
            runOnMainSync(()->{if(!current.isDestroyed())current.setRequestedOrientation(originalOrientation);});
        }
    }

    private interface Condition {boolean ready() throws Exception;}
    private interface ActivityCondition {boolean ready(Activity activity);}
    private interface WindowCondition {boolean ready(View root);}
    private void await(String reason,Condition condition) throws Exception {
        long deadline=SystemClock.uptimeMillis()+12000;
        do {waitForIdleSync();if(condition.ready())return;SystemClock.sleep(50);}while(SystemClock.uptimeMillis()<deadline);
        throw new AssertionError("Timed out: "+reason);
    }
    private void awaitUi(String reason,ActivityCondition condition) throws Exception {await(reason,()->ui(condition));}
    private boolean ui(ActivityCondition condition) {
        boolean[] value=new boolean[1];runOnMainSync(()->value[0]=current!=null&&!current.isDestroyed()&&condition.ready(current));return value[0];
    }
    private boolean noPreview(Activity activity) {
        View adopt=activity.findViewById(404);
        return adopt!=null&&adopt.getVisibility()==View.GONE&&!containsText(activity.getWindow().getDecorView(),"建议预览 · 尚未保存");
    }
    private String text(Activity activity,int id) {
        View view=activity.findViewById(id);return view instanceof TextView?((TextView)view).getText().toString():"";
    }
    private void clickId(int id,String reason) {
        boolean[] clicked=new boolean[1];runOnMainSync(()->{View view=current.findViewById(id);clicked[0]=view!=null&&view.isEnabled()&&view.performClick();});
        check(clicked[0],reason);waitForIdleSync();
    }

    private void awaitDialog(String reason) throws Exception {await(reason,this::hasDialog);}
    private boolean hasDialog() {return dialog(root->byDescription(root,"DeepSeek密钥")!=null);}
    private boolean dialog(WindowCondition condition) {
        return window(root->byDescription(root,"DeepSeek密钥")!=null&&condition.ready(root));
    }
    private boolean titledDialog(String title,WindowCondition condition) {
        return window(root->byText(root,title)!=null&&condition.ready(root));
    }
    private boolean window(WindowCondition condition) {
        if(Build.VERSION.SDK_INT<29)return nativeDialog(condition);
        boolean[] result=new boolean[1];
        runOnMainSync(()->{
            for(View root:WindowInspector.getGlobalWindowViews()) {
                if(root.isShown()&&condition.ready(root)){result[0]=true;return;}
            }
        });return result[0];
    }
    // QA runs on API 35. A lower API receives a precise unsupported-test failure, not a false pass.
    private boolean nativeDialog(WindowCondition condition) {throw new AssertionError("Agent dialog inspection requires the API 29+ QA emulator");}
    private void clickDialogText(String label) {
        clickDialogText(root->byDescription(root,"DeepSeek密钥")!=null,label,"Configuration dialog action: "+label);
    }
    private void clickDialogText(WindowCondition match,String label,String reason) {
        check(window(root->{
            if(!match.ready(root))return false;
            View action=byText(root,label);
            return action!=null&&action.isEnabled()&&action.performClick();
        }),reason);
        waitForIdleSync();
    }
    private View byDescription(View root,String description) {
        if(description.contentEquals(root.getContentDescription()==null?"":root.getContentDescription()))return root;
        if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++) {
            View match=byDescription(((ViewGroup)root).getChildAt(i),description);if(match!=null)return match;
        }return null;
    }
    private View byText(View root,String text) {
        if(root instanceof TextView&&text.contentEquals(((TextView)root).getText()))return root;
        if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++) {
            View match=byText(((ViewGroup)root).getChildAt(i),text);if(match!=null)return match;
        }return null;
    }
    private boolean containsText(View root,String text) {
        if(root instanceof TextView&&((TextView)root).getText().toString().contains(text))return true;
        if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++)
            if(containsText(((ViewGroup)root).getChildAt(i),text))return true;
        return false;
    }
}

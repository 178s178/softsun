package cn.qingri.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import java.time.format.DateTimeFormatter;
import java.util.function.Consumer;

final class AgentMemoryDialog {
    static final String DISCLOSURE="开启 Agent 记忆后，主动生成也会向 DeepSeek 发送长期安排偏好与最近采纳的安排参数。API 密钥由模型设置单独管理。";
    private static final DateTimeFormatter TIME=DateTimeFormatter.ofPattern("HH:mm");

    private AgentMemoryDialog() {}

    static String status(Context context) {
        return AgentMemory.load(context).enabled?"已开启":"已关闭";
    }

    static void show(Activity activity,Runnable onSaved,Consumer<AgentMemory.Defaults> onUse) {
        AgentMemory.Snapshot memory=AgentMemory.load(activity);
        ScrollView scroll=new ScrollView(activity);
        LinearLayout body=Ui.column(activity);
        body.setFocusableInTouchMode(true);
        Ui.pad(body,22);
        scroll.addView(body);

        TextView disclosure=Ui.text(activity,DISCLOSURE+"关闭后保留已保存的内容。",13,Ui.MUTED);
        disclosure.setLineSpacing(Ui.dp(activity,4),1);
        body.addView(disclosure);
        Ui.gap(body,10);

        Switch enabled=new Switch(activity);
        enabled.setId(411);
        enabled.setText("启用 Agent 记忆");
        enabled.setTextColor(Ui.INK);
        enabled.setTextSize(15);
        enabled.setContentDescription("启用 Agent 记忆");
        enabled.setChecked(memory.enabled);
        body.addView(enabled,Ui.lp(activity,-1,48));
        Ui.gap(body,12);

        body.addView(Ui.bold(activity,"长期安排偏好 · 最多 "+AgentMemory.MAX_NOTE_LENGTH+" 字",13,Ui.INK));
        Ui.gap(body,8);
        EditText note=new EditText(activity);
        note.setId(412);
        note.setTextSize(14);
        note.setTextColor(Ui.INK);
        note.setHintTextColor(Ui.MUTED);
        note.setHint("例如：上午优先安排高数，晚上留出运动时间。");
        note.setContentDescription("长期安排偏好");
        note.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        note.setImeOptions(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        note.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        note.setGravity(Gravity.TOP);
        note.setMinLines(3);
        note.setMaxLines(5);
        note.setFilters(new InputFilter[]{new InputFilter.LengthFilter(AgentMemory.MAX_NOTE_LENGTH)});
        note.setText(memory.note);
        note.setBackground(Ui.outline(Ui.BG,Ui.LINE,12,activity));
        Ui.pad(note,12);
        body.addView(note,Ui.lp(activity,-1,-2));
        Ui.gap(body,16);

        body.addView(Ui.bold(activity,"最近采纳的安排参数",13,Ui.INK));
        Ui.gap(body,8);
        TextView last=Ui.text(activity,describe(memory.lastAccepted),13,Ui.MUTED);
        last.setId(413);
        last.setLineSpacing(Ui.dp(activity,4),1);
        body.addView(last);

        AlertDialog dialog=new AlertDialog.Builder(activity).setTitle("Agent 记忆").setView(scroll)
                .setNegativeButton("取消",null).setNeutralButton("清除记忆",null).setPositiveButton("保存",null).create();
        if(onUse!=null&&memory.lastAccepted!=null) {
            Ui.gap(body,12);
            TextView use=Ui.button(activity,"使用上次参数",false,()->{
                dialog.dismiss();
                onUse.accept(memory.lastAccepted);
            });
            use.setId(414);
            body.addView(use,Ui.lp(activity,-1,-2));
            Ui.gap(body,8);
            TextView reuseHint=Ui.text(activity,"仅将上次参数填入本次草稿。开关和偏好的修改需要另点保存。",12,Ui.MUTED);
            reuseHint.setLineSpacing(Ui.dp(activity,3),1);
            body.addView(reuseHint);
        }
        dialog.setOnShowListener(ignored->{
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                    |WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
            body.requestFocus();
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view->{
                try {
                    AgentMemory.save(activity,enabled.isChecked(),note.getText().toString());
                } catch(IllegalStateException error) {
                    Ui.toast(activity,error.getMessage());
                    return;
                }
                dialog.dismiss();
                onSaved.run();
                Ui.toast(activity,"Agent 记忆设置已保存");
            });
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view->confirmClear(activity,dialog,onSaved));
        });
        dialog.show();
    }

    private static String describe(AgentMemory.Defaults defaults) {
        if(defaults==null)return "暂无。启用记忆并成功采纳日程后，会在这里显示。";
        return "可用时间 "+defaults.windowStart.format(TIME)+"–"+defaults.windowEnd.format(TIME)
                +"\n专注 "+defaults.focusMinutes+" 分钟 · 休息 "+defaults.breakMinutes+" 分钟";
    }

    private static void confirmClear(Activity activity,AlertDialog dialog,Runnable onSaved) {
        new AlertDialog.Builder(activity).setTitle("清除 Agent 记忆？")
                .setMessage("这会删除已保存的长期安排偏好和最近采纳参数，并关闭 Agent 记忆。")
                .setNegativeButton("取消",null).setPositiveButton("清除",(confirm,which)->{
                    try {
                        AgentMemory.clear(activity);
                    } catch(IllegalStateException error) {
                        Ui.toast(activity,error.getMessage());
                        return;
                    }
                    dialog.dismiss();
                    onSaved.run();
                    Ui.toast(activity,"Agent 记忆已清除");
                }).show();
    }
}

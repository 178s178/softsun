package cn.qingri.app.tests;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.TextView;
import cn.qingri.app.EventStore;
import cn.qingri.app.TimeRecord;
import java.time.LocalDate;
import java.time.ZoneId;

/** Real native-screen checks. Fixtures are created only in the disposable test emulator. */
public class RecordsUiInstrumentation extends Instrumentation {
    private static final long MINUTE = 60000L;
    private static final String DATE = "2026-11-03";
    private static final String MANUAL = "测试补记 · 阅读";
    private static final String RENAMED = "测试补记 · 已改名";
    private int assertions;

    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            runUiTests();
            result.putString("stream", "PASS: " + assertions + " time UI assertions\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable failure) {
            result.putString("stream", "FAIL: " + android.util.Log.getStackTraceString(failure));
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private void check(boolean passed, String reason) {
        assertions++;
        if (!passed) throw new AssertionError(reason);
        Bundle update = new Bundle();
        update.putString("stream", "ok " + assertions + ": " + reason + "\n");
        sendStatus(0, update);
    }

    private void runUiTests() throws Exception {
        Context context = getTargetContext();
        check("cn.qingri.app".equals(context.getPackageName()), "Uses the app target context");
        // Never allow this destructive fixture reset to run on a user's physical device.
        String device = (Build.FINGERPRINT + " " + Build.MODEL + " " + Build.PRODUCT).toLowerCase(java.util.Locale.ROOT);
        check(device.contains("generic") || device.contains("emulator") || device.contains("sdk_gphone") || device.contains("sdk_phone"),
                "Fixture database reset is restricted to the test emulator");
        context.deleteDatabase("qingri.db");

        try (EventStore store = new EventStore(context)) {
            TimeRecord math = fixture("records-ui-math", "高数复习", DATE, 9, 0, 60, 45);
            math.segments.add(new TimeRecord.Segment(math.start, math.start + 25 * MINUTE, 25 * MINUTE));
            math.segments.add(new TimeRecord.Segment(math.start + 40 * MINUTE, math.end, 20 * MINUTE));
            TimeRecord english = fixture("records-ui-english", "英语阅读", DATE, 11, 0, 15, 15);
            english.segments.add(new TimeRecord.Segment(english.start, english.end, english.duration));
            TimeRecord coding = fixture("records-ui-coding", "编程练习", "2026-11-02", 14, 0, 60, 60);
            coding.segments.add(new TimeRecord.Segment(coding.start, coding.end, coding.duration));
            store.saveTimeRecord(math);
            store.saveTimeRecord(english);
            store.saveTimeRecord(coding);
            check(store.studyRecords().size() == 3, "Three independent sessions are persisted");
            check(record(store, math.token).segments.size() == 2 && math.end - math.start > math.duration,
                    "Math fixture has real pause-separated effective segments");

            Activity activity = launch(DATE);
            awaitUi(activity, "Day records load", root -> row(root, "高数复习", "75.0%") != null
                    && row(root, "英语阅读", "25.0%") != null);
            assertDay(activity);
            clickSession(activity, "高数复习", "75.0%");
            awaitUi(activity, "Selected row highlights its own pie slice", root -> byDescription(root,
                    "已选中 高数复习，45分，占比75.0%") != null);
            check(ui(activity, root -> byDescription(root, "已选中 高数复习，45分，占比75.0%") != null),
                    "A session row selects the matching accessible pie slice");

            clickText(activity, "月统计");
            awaitUi(activity, "Month records load", root -> row(root, "编程练习", "50.0%") != null
                    && summary(root, 3, "2小时"));
            check(ui(activity, root -> row(root, "高数复习", "37.5%") != null
                    && row(root, "英语阅读", "12.5%") != null
                    && row(root, "编程练习", "50.0%") != null), "Month keeps all three sessions as separate slices");
            check(ui(activity, root -> summary(root, 3, "2小时")), "Month shows three sessions and two effective hours");
            clickText(activity, "日统计");
            awaitUi(activity, "Day filter restores its two sessions", root -> row(root, "高数复习", "75.0%") != null
                    && row(root, "编程练习", "50.0%") == null && summary(root, 2, "1小时"));
            assertDay(activity);

            Activity empty = launch("2026-11-05");
            awaitUi(empty, "A selected empty date finishes loading", root -> byText(root, "这一天还没有记录") != null);
            check(ui(empty, root -> byText(root, "这一天还没有记录") != null), "An empty selected date has an explicit empty state");
            check(ui(empty, root -> byText(root, "高数复习") == null && byText(root, "英语阅读") == null
                    && byText(root, "编程练习") == null), "Selecting an empty date excludes other dates' sessions");

            activity = launch(DATE);
            awaitUi(activity, "Fixture day reloads", root -> row(root, "高数复习", "75.0%") != null);
            clickText(activity, "月统计");
            awaitUi(activity, "Month reloads before manual entry", root -> summary(root, 3, "2小时"));
            clickDescription(activity, "补记时间");
            awaitNative("Manual entry opens", node -> editable(node) && "记录名称，选填".contentEquals(description(node)));
            setNativeInput("记录名称，选填", MANUAL);
            clickNativeText("保存记录");
            await("Manual record is saved by the worker", () -> store.studyRecords().size() == 4 && named(store, MANUAL) != null);
            TimeRecord manual = named(store, MANUAL);
            check(store.studyRecords().size() == 4 && manual != null, "Manual entry saves one additional named record");
            check(manual.duration == 30 * MINUTE && !manual.estimated && manual.segments.size() == 1
                    && manual.segments.get(0).duration == manual.duration,
                    "Manual entry saves actual start/end duration and its effective segment");
            awaitUi(activity, "New manual session appears in the month", root -> byText(root, MANUAL) != null);
            check(ui(activity, root -> byText(root, MANUAL) != null), "Saved manual session is visible immediately");

            clickDescription(activity, "查看" + MANUAL + "详情");
            clickNativeText("修改名称");
            awaitNative("Rename input opens", RecordsUiInstrumentation::editable);
            setNativeInput("修改记录名称", RENAMED);
            clickNativeText("保存");
            final String manualToken = manual.token;
            await("Rename is persisted", () -> record(store, manualToken) != null && RENAMED.equals(record(store, manualToken).label));
            check(record(store, manualToken).duration == 30 * MINUTE && store.studyRecords().size() == 4,
                    "Renaming preserves duration and session count");
            awaitUi(activity, "Renamed row refreshes", root -> byText(root, RENAMED) != null && byText(root, MANUAL) == null);
            check(ui(activity, root -> byText(root, RENAMED) != null), "Renamed label appears in the session list");

            clickDescription(activity, "查看" + RENAMED + "详情");
            clickNativeText("删除记录");
            awaitNative("Delete confirmation opens", node -> "删除这条记录？".contentEquals(text(node)));
            clickNativeText("保留记录");
            check(record(store, manualToken) != null && store.studyRecords().size() == 4,
                    "Keeping a record cancels deletion");
            clickDescription(activity, "查看" + RENAMED + "详情");
            clickNativeText("删除记录");
            clickNativeText("删除");
            await("Confirmed deletion is persisted", () -> record(store, manualToken) == null && store.studyRecords().size() == 3);
            check(store.studyRecords().size() == 3 && record(store, manualToken) == null,
                    "Confirmed delete removes only the chosen session");
            awaitUi(activity, "Month refreshes after deletion", root -> summary(root, 3, "2小时") && byText(root, RENAMED) == null);
            check(record(store, math.token) != null && record(store, english.token) != null && record(store, coding.token) != null,
                    "Original screenshot fixtures remain intact after editing another session");
            // Leave the real fixture data and its day screen ready for preview screenshots.
            clickText(activity, "日统计");
            awaitUi(activity, "Preview day is ready", root -> row(root, "高数复习", "75.0%") != null && summary(root, 2, "1小时"));
        }
    }

    private TimeRecord fixture(String token, String label, String date, int hour, int minute, long spanMinutes, long effectiveMinutes) {
        TimeRecord value = new TimeRecord();
        value.token = token; value.label = label;
        value.start = LocalDate.parse(date).atTime(hour, minute).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        value.end = value.start + spanMinutes * MINUTE;
        value.duration = effectiveMinutes * MINUTE;
        return value;
    }

    private void assertDay(Activity activity) {
        check(ui(activity, root -> row(root, "高数复习", "75.0%") != null
                && row(root, "英语阅读", "25.0%") != null), "Day shows two distinct entries with 75% and 25% shares");
        check(ui(activity, root -> byText(root, "编程练习") == null), "Day excludes the previous day's session");
        check(ui(activity, root -> summary(root, 2, "1小时")), "Day total uses effective time and excludes the fifteen-minute pause");
    }

    private Activity launch(String date) throws Exception {
        Class<?> type = Class.forName("cn.qingri.app.RecordsActivity");
        Activity activity = startActivitySync(new Intent(getTargetContext(), type).putExtra("date", date)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        waitForIdleSync();
        return activity;
    }

    private interface Condition { boolean ready() throws Exception; }
    private interface UiCondition { boolean ready(View root); }
    private interface NodeCondition { boolean matches(AccessibilityNodeInfo node); }

    private void await(String reason, Condition condition) throws Exception {
        long deadline = SystemClock.uptimeMillis() + 12000;
        do {
            waitForIdleSync();
            if (condition.ready()) return;
            SystemClock.sleep(50);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new AssertionError("Timed out: " + reason);
    }

    private void awaitUi(Activity activity, String reason, UiCondition condition) throws Exception {
        await(reason, () -> ui(activity, condition));
    }

    private boolean ui(Activity activity, UiCondition condition) {
        boolean[] result = new boolean[1];
        runOnMainSync(() -> result[0] = condition.ready(activity.getWindow().getDecorView()));
        return result[0];
    }

    private void clickText(Activity activity, String label) {
        click(activity, label, false);
    }

    private void clickDescription(Activity activity, String label) {
        click(activity, label, true);
    }

    private void clickSession(Activity activity, String label, String share) {
        boolean[] clicked = new boolean[1];
        runOnMainSync(() -> {
            ListView list = activity.findViewById(310);
            if (list == null) return;
            ListAdapter adapter = list.getAdapter();
            for (int position = list.getHeaderViewsCount(); position < adapter.getCount() - list.getFooterViewsCount(); position++) {
                View bound = adapter.getView(position, null, list);
                if (row(bound, label, share) != null) {
                    clicked[0] = list.performItemClick(bound, position, adapter.getItemId(position));
                    return;
                }
            }
        });
        check(clicked[0], "Session row action: " + label);
        waitForIdleSync();
    }

    private void click(Activity activity, String label, boolean description) {
        boolean[] clicked = new boolean[1];
        runOnMainSync(() -> {
            View root = activity.getWindow().getDecorView();
            View view = description ? byDescription(root, label) : byText(root, label);
            clicked[0] = view != null && view.isEnabled() && view.performClick();
        });
        check(clicked[0], "Visible action: " + label);
        waitForIdleSync();
    }

    private View byText(View root, String value) {
        if (root instanceof TextView && value.contentEquals(((TextView) root).getText())) return root;
        if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) {
            View found = byText(((ViewGroup) root).getChildAt(i), value); if (found != null) return found;
        }
        if (root instanceof ListView) return adapterRows((ListView) root, view -> byText(view, value));
        return null;
    }

    private View byDescription(View root, String value) {
        if (value.contentEquals(root.getContentDescription() == null ? "" : root.getContentDescription())) return root;
        if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) {
            View found = byDescription(((ViewGroup) root).getChildAt(i), value); if (found != null) return found;
        }
        if (root instanceof ListView) return adapterRows((ListView) root, view -> byDescription(view, value));
        return null;
    }

    private View row(View root, String label, String share) {
        String value = root.getContentDescription() == null ? "" : root.getContentDescription().toString();
        if (value.startsWith(label + "，") && (value.endsWith("占比" + share) || value.endsWith("占比" + share + "，已选中"))) return root;
        if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) {
            View found = row(((ViewGroup) root).getChildAt(i), label, share); if (found != null) return found;
        }
        if (root instanceof ListView) return adapterRows((ListView) root, view -> row(view, label, share));
        return null;
    }

    private interface RowSearch { View find(View row); }

    // Bind offscreen virtual rows through the same public adapter used by ListView.
    // This verifies every session on small screens without relying on scroll coordinates.
    private View adapterRows(ListView list, RowSearch search) {
        ListAdapter adapter = list.getAdapter();
        if (adapter == null) return null;
        for (int position = list.getHeaderViewsCount(); position < adapter.getCount() - list.getFooterViewsCount(); position++) {
            View found = search.find(adapter.getView(position, null, list));
            if (found != null) return found;
        }
        return null;
    }

    private boolean summary(View root, int sessions, String duration) {
        String value = root.getContentDescription() == null ? "" : root.getContentDescription().toString();
        if (value.startsWith("学习时长扇形图，共" + sessions + "次学习，总有效时长" + duration + "；")) return true;
        if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++)
            if (summary(((ViewGroup) root).getChildAt(i), sessions, duration)) return true;
        return false;
    }

    private TimeRecord record(EventStore store, String token) {
        for (TimeRecord record : store.studyRecords()) if (token.equals(record.token)) return record;
        return null;
    }

    private TimeRecord named(EventStore store, String label) {
        for (TimeRecord record : store.studyRecords()) if (label.equals(record.label)) return record;
        return null;
    }

    private static CharSequence text(AccessibilityNodeInfo node) { return node.getText() == null ? "" : node.getText(); }
    private static CharSequence description(AccessibilityNodeInfo node) { return node.getContentDescription() == null ? "" : node.getContentDescription(); }
    private static boolean editable(AccessibilityNodeInfo node) { return node.isEditable() && node.isEnabled(); }

    // Dialogs own a separate Window, so locate their public accessibility nodes instead of using Activity.findViewById.
    private AccessibilityNodeInfo nativeNode(NodeCondition condition) {
        AccessibilityNodeInfo root = getUiAutomation().getRootInActiveWindow();
        if (root == null) return null;
        try { return findNode(root, condition); } finally { root.recycle(); }
    }

    @SuppressWarnings("deprecation")
    private AccessibilityNodeInfo findNode(AccessibilityNodeInfo root, NodeCondition condition) {
        if (root.isVisibleToUser() && condition.matches(root)) return AccessibilityNodeInfo.obtain(root);
        for (int i = 0; i < root.getChildCount(); i++) {
            AccessibilityNodeInfo child = root.getChild(i);
            if (child == null) continue;
            try { AccessibilityNodeInfo found = findNode(child, condition); if (found != null) return found; }
            finally { child.recycle(); }
        }
        return null;
    }

    private void awaitNative(String reason, NodeCondition condition) throws Exception {
        await(reason, () -> {
            AccessibilityNodeInfo node = nativeNode(condition);
            if (node == null) return false;
            node.recycle(); return true;
        });
    }

    private void clickNativeText(String label) throws Exception {
        NodeCondition condition = node -> node.isEnabled() && node.isClickable() && label.contentEquals(text(node));
        awaitNative("Dialog action: " + label, condition);
        AccessibilityNodeInfo node = nativeNode(condition);
        check(node != null, "Native dialog action exists: " + label);
        try { check(node.performAction(AccessibilityNodeInfo.ACTION_CLICK), "Native dialog action: " + label); }
        finally { node.recycle(); }
        waitForIdleSync();
    }

    private void setNativeInput(String expectedDescription, String value) throws Exception {
        NodeCondition condition = node -> editable(node) && (expectedDescription == null || expectedDescription.contentEquals(description(node)));
        awaitNative("Native name input is ready", condition);
        AccessibilityNodeInfo node = nativeNode(condition);
        check(node != null, "Native name input exists");
        Bundle arguments = new Bundle();
        arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
        try { check(node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments), "Native name field accepts the entered text"); }
        finally { node.recycle(); }
        waitForIdleSync();
    }
}

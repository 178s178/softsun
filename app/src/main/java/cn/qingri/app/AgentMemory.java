package cn.qingri.app;

import android.content.Context;
import android.content.SharedPreferences;
import java.time.LocalTime;
import java.util.UUID;
import org.json.JSONException;
import org.json.JSONObject;

/** Optional planning preferences stored separately from credentials and schedules. */
public final class AgentMemory {
    public static final int MAX_NOTE_LENGTH = 500;
    private static final String PREFS = "agent_memory";

    private AgentMemory() {}

    public static final class Defaults {
        public final LocalTime windowStart, windowEnd;
        public final int focusMinutes, breakMinutes;

        private Defaults(LocalTime start, LocalTime end, int focus, int rest) {
            windowStart = start;
            windowEnd = end;
            focusMinutes = focus;
            breakMinutes = rest;
        }

        private JSONObject toJson() throws JSONException {
            return new JSONObject().put("window_start", windowStart.toString())
                .put("window_end", windowEnd.toString()).put("focus_minutes", focusMinutes)
                .put("break_minutes", breakMinutes);
        }
    }

    public static final class Snapshot {
        public final boolean enabled;
        public final String note;
        public final Defaults lastAccepted;
        private final String revision;

        private Snapshot(boolean enabled, String note, Defaults lastAccepted, String revision) {
            this.enabled = enabled;
            this.note = note;
            this.lastAccepted = lastAccepted;
            this.revision = revision;
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static synchronized Snapshot load(Context context) {
        SharedPreferences store = prefs(context);
        Defaults last = null;
        int start = store.getInt("start", -1), end = store.getInt("end", -1);
        int focus = store.getInt("focus", -1), rest = store.getInt("rest", -1);
        if (start >= 0 && end > start && end < 1440 && focus >= 15 && focus <= 120 && rest >= 0 && rest <= 60) {
            last = new Defaults(LocalTime.ofSecondOfDay(start * 60L), LocalTime.ofSecondOfDay(end * 60L), focus, rest);
        }
        return new Snapshot(store.getBoolean("enabled", false), store.getString("note", ""),
            last, store.getString("revision", ""));
    }

    public static synchronized void save(Context context, boolean enabled, String note) {
        String value = note == null ? "" : note.trim();
        if (value.length() > MAX_NOTE_LENGTH) throw new IllegalArgumentException("偏好最多500字");
        if (!prefs(context).edit().putBoolean("enabled", enabled).putString("note", value)
                .putString("revision", UUID.randomUUID().toString()).commit()) {
            throw new IllegalStateException("记忆设置保存失败，请重试");
        }
    }

    public static synchronized void clear(Context context) {
        if (!prefs(context).edit().clear().commit()) throw new IllegalStateException("记忆清除失败，请重试");
    }

    private static boolean isCurrent(SharedPreferences store, Snapshot snapshot) {
        return snapshot.enabled && store.getBoolean("enabled", false)
            && snapshot.revision.equals(store.getString("revision", ""));
    }

    static synchronized JSONObject modelContext(Context context, Snapshot snapshot) throws JSONException {
        JSONObject data = new JSONObject();
        if (isCurrent(prefs(context), snapshot)) {
            if (!snapshot.note.isEmpty()) data.put("preference_note", snapshot.note);
            if (snapshot.lastAccepted != null) data.put("last_accepted", snapshot.lastAccepted.toJson());
        }
        return data;
    }

    static synchronized void remember(Context context, Snapshot snapshot, AgentPlanner.Request accepted) {
        SharedPreferences store = prefs(context);
        if (!isCurrent(store, snapshot)) return;
        store.edit().putInt("start", accepted.windowStart.toSecondOfDay() / 60)
            .putInt("end", accepted.windowEnd.toSecondOfDay() / 60).putInt("focus", accepted.focusMinutes)
            .putInt("rest", accepted.breakMinutes).apply();
    }
}

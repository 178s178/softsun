package cn.qingri.app;
public class SystemReceiver extends android.content.BroadcastReceiver {
    public void onReceive(android.content.Context c, android.content.Intent i) { ReminderScheduler.reschedule(c,true); }
}

package cn.qingri.app;

import java.time.LocalDateTime;

public class Event {
    public long id;
    public String revision = "";
    public String title = "";
    public String note = "";
    public String location = "";
    public String category = "生活";
    public int priority = 1;
    public LocalDateTime start;
    public LocalDateTime end;
    public String repeat = "none";
    public int reminder = 10;
}

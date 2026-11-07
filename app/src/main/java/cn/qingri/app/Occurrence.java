package cn.qingri.app;

import java.time.LocalDateTime;

public class Occurrence {
    public final Event event;
    public final LocalDateTime start;
    public final LocalDateTime end;
    public final boolean completed;

    public Occurrence(Event event, LocalDateTime start, LocalDateTime end, boolean completed) {
        this.event = event; this.start = start; this.end = end; this.completed = completed;
    }
    public String key() { return event.id + "@" + start.toString(); }
}

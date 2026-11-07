package cn.qingri.app;

import java.time.*;
import java.util.*;

public class PlannerTests {
    private static int assertions, tests, failures;
    private static final LocalDate DAY = LocalDate.of(2026, 11, 5);

    private static void check(boolean value, String message) {
        assertions++;
        if (!value) throw new AssertionError(message);
    }
    private static void eq(Object actual, Object expected, String message) {
        check(Objects.equals(actual, expected), message + ": expected " + expected + ", got " + actual);
    }
    private static void test(String name, Runnable body) {
        tests++;
        try { body.run(); }
        catch (Throwable failure) { failures++; System.err.println("FAIL " + name + ": " + failure); }
    }
    private static AgentPlanner.Request request(String start, String end) {
        AgentPlanner.Request r = new AgentPlanner.Request();
        r.date = DAY; r.windowStart = LocalTime.parse(start); r.windowEnd = LocalTime.parse(end);
        r.now = DAY.minusDays(1).atTime(20, 0); r.breakMinutes = 0;
        return r;
    }
    private static AgentPlanner.Task task(String id, int minutes, int priority) {
        AgentPlanner.Task t = new AgentPlanner.Task();
        t.id = id; t.title = "任务" + id; t.minutes = minutes; t.priority = priority;
        return t;
    }
    private static AgentPlanner.Task move(String id, int minutes, long sourceId) {
        AgentPlanner.Task t = task(id, minutes, 1); t.sourceId = sourceId; t.sourceRevision = "revision";
        return t;
    }
    private static LocalDateTime at(String time) { return DAY.atTime(LocalTime.parse(time)); }
    private static AgentPlanner.Busy busy(String start, String end) {
        AgentPlanner.Busy b = new AgentPlanner.Busy(); b.start = at(start); b.end = at(end); return b;
    }
    private static AgentPlanner.Busy busy(LocalDateTime start, LocalDateTime end) {
        AgentPlanner.Busy b = new AgentPlanner.Busy(); b.start = start; b.end = end; return b;
    }
    private static AgentPlanner.Slot slot(AgentPlanner.Task task, String start, String end) {
        AgentPlanner.Slot s = new AgentPlanner.Slot(); s.task = task; s.start = at(start); s.end = at(end); return s;
    }
    private static void invalid(AgentPlanner.Request r, String why) {
        AgentPlanner.Plan p = AgentPlanner.build(r);
        eq(p.slots.size(), 0, why + " produces no proposal");
        check(!p.warnings.isEmpty(), why + " is explained");
    }
    private static void rejected(AgentPlanner.Request r, List<AgentPlanner.Slot> selected,
                                 List<AgentPlanner.Busy> currentBusy, String why) {
        check(!AgentPlanner.validateSelection(r, selected, currentBusy).isEmpty(), why);
    }

    public static void main(String[] args) {
        test("fixed intervals cannot be overwritten", () -> {
            AgentPlanner.Request r = request("08:00", "12:00");
            r.tasks.add(task("a", 45, 1)); r.busy.add(busy("08:00", "09:00"));
            AgentPlanner.Plan p = AgentPlanner.build(r);
            eq(p.slots.size(), 1, "One task is proposed"); eq(p.slots.get(0).start, at("09:00"), "Fixed interval remains occupied");
            check(!p.slots.get(0).reason.trim().isEmpty(), "Proposal explains placement");
            check(p.steps.size() >= 4, "Actual planning stages are described");
            eq(r.busy.get(0).end, at("09:00"), "Planning does not mutate busy intervals");
        });
        test("today begins at rounded future bound", () -> {
            AgentPlanner.Request r = request("08:00", "12:00"); r.now = at("09:01:02"); r.tasks.add(task("a", 20, 1));
            eq(AgentPlanner.build(r).slots.get(0).start, at("09:05"), "Round seconds up to the next five minutes");
            r.now = at("09:05"); eq(AgentPlanner.build(r).slots.get(0).start, at("09:05"), "Exact five-minute boundary is usable");
            r.now = at("09:05:00.000000001"); eq(AgentPlanner.build(r).slots.get(0).start, at("09:10"), "Nanoseconds cannot leak a past start");
        });
        test("past dates and past windows have no slots", () -> {
            AgentPlanner.Request r = request("08:00", "09:00"); r.tasks.add(task("a", 20, 1)); r.now = DAY.plusDays(1).atStartOfDay();
            invalid(r, "Past calendar day"); r.now = at("09:01"); invalid(r, "Window already ended");
            r.now = at("23:59:59"); invalid(r, "Rounding crosses into tomorrow");
        });
        test("overnight fixed intervals are clipped and unioned", () -> {
            AgentPlanner.Request r = request("08:00", "12:00"); r.tasks.add(task("a", 30, 1));
            r.busy.add(busy(DAY.minusDays(1).atTime(23, 0), at("08:15")));
            r.busy.add(busy("08:10", "08:45")); r.busy.add(busy("08:45", "09:00"));
            r.busy.add(busy(at("11:30"), DAY.plusDays(1).atTime(1, 0)));
            r.busy.add(busy(DAY.minusDays(2).atTime(8, 0), DAY.minusDays(2).atTime(9, 0)));
            eq(AgentPlanner.build(r).slots.get(0).start, at("09:00"), "Clipping and overlap union preserve the true first free point");
        });
        test("touching endpoints are allowed", () -> {
            AgentPlanner.Request r = request("08:00", "10:00"); r.tasks.add(task("a", 30, 1));
            r.busy.add(busy("08:00", "08:30")); r.busy.add(busy("09:00", "10:00"));
            AgentPlanner.Plan p = AgentPlanner.build(r); eq(p.slots.get(0).start, at("08:30"), "Exact gap is usable");
            eq(p.slots.get(0).end, at("09:00"), "End can touch another fixed start");
            eq(AgentPlanner.validateSelection(r, p.slots, r.busy).size(), 0, "Apply accepts half-open adjacency");
        });
        test("deadline then high priority then stable id determines order", () -> {
            AgentPlanner.Request r = request("08:00", "12:00");
            AgentPlanner.Task low = task("low", 20, 0), high = task("high", 20, 2), early = task("early", 20, 0);
            early.deadline = LocalTime.of(9, 0); r.tasks.add(low); r.tasks.add(high); r.tasks.add(early);
            AgentPlanner.Plan p = AgentPlanner.build(r);
            eq(p.slots.get(0).task.id, "early", "Earlier deadline precedes higher priority");
            eq(p.slots.get(1).task.id, "high", "Existing app uses priority 2 as high");
            AgentPlanner.Request ties = request("08:00", "12:00"); ties.tasks.add(task("z", 20, 1)); ties.tasks.add(task("a", 20, 1));
            eq(AgentPlanner.build(ties).slots.get(0).task.id, "a", "Stable tie-break does not depend on input order");
            eq(r.tasks.get(0), low, "Request task order is not mutated");
        });
        test("deadline is inclusive and never exceeded", () -> {
            AgentPlanner.Request r = request("08:00", "10:00"); AgentPlanner.Task t = task("a", 30, 1); t.deadline = LocalTime.of(8, 30); r.tasks.add(t);
            eq(AgentPlanner.build(r).slots.get(0).end, at("08:30"), "Ending exactly at deadline is legal");
            r.busy.add(busy("08:00", "08:05")); AgentPlanner.Plan p = AgentPlanner.build(r);
            eq(p.slots.size(), 0, "No deadline overrun"); eq(p.unscheduled.size(), 1, "Missed deadline reported");
        });
        test("split focus blocks reserve real rest gaps", () -> {
            AgentPlanner.Request r = request("08:00", "11:00"); r.focusMinutes = 50; r.breakMinutes = 10;
            r.tasks.add(task("a", 120, 2)); r.tasks.add(task("b", 10, 0)); AgentPlanner.Plan p = AgentPlanner.build(r);
            eq(p.slots.size(), 4, "Focus blocks and smaller task are proposed");
            eq(p.slots.get(0).end, at("08:50"), "First block uses focus duration");
            eq(p.slots.get(1).start, at("09:00"), "Rest is not filled by another task");
            eq(p.slots.get(2).start, at("10:00"), "Second rest is reserved");
            eq(p.slots.get(2).end, at("10:20"), "Final block preserves total duration");
            eq(p.slots.get(2).part, 3, "Block ordinal is useful in preview"); eq(p.slots.get(2).totalParts, 3, "Total block count is correct");
            eq(p.slots.get(3).start, at("10:20"), "Final task block has no trailing rest");
            eq(AgentPlanner.validateSelection(r, p.slots, r.busy).size(), 0, "Complete generated plan validates");
        });
        test("break itself must fit a free interval", () -> {
            AgentPlanner.Request r = request("08:00", "11:00"); r.focusMinutes = 50; r.breakMinutes = 10;
            r.tasks.add(task("a", 80, 1)); r.busy.add(busy("08:50", "09:20"));
            AgentPlanner.Plan p = AgentPlanner.build(r);
            eq(p.slots.get(0).start, at("09:20"), "A fixed meeting cannot be claimed as a rest break");
            eq(p.slots.get(1).start, at("10:20"), "Remaining block follows an available break");
        });
        test("new task placements are atomic", () -> {
            AgentPlanner.Request r = request("08:00", "09:00"); r.focusMinutes = 50; r.breakMinutes = 10;
            r.tasks.add(task("large", 80, 2)); r.tasks.add(task("small", 20, 0)); AgentPlanner.Plan p = AgentPlanner.build(r);
            eq(p.slots.size(), 1, "An incomplete large task contributes no blocks"); eq(p.slots.get(0).task.id, "small", "Rolled-back capacity remains usable");
            eq(p.slots.get(0).start, at("08:00"), "Failed task leaves no hidden reservation");
            eq(p.unscheduled.get(0).id, "large", "Full failed task is reported"); check(!p.warnings.isEmpty(), "Insufficient time is explained");
        });
        test("explicit unsplittable new task stays whole", () -> {
            AgentPlanner.Request r = request("08:00", "11:00"); AgentPlanner.Task t = task("a", 90, 1); t.splittable = false; r.tasks.add(t);
            AgentPlanner.Plan p = AgentPlanner.build(r); eq(p.slots.size(), 1, "Unsplittable task gets one interval");
            eq(p.slots.get(0).end, at("09:30"), "Whole duration is preserved");
        });
        test("movable existing schedules are never split", () -> {
            AgentPlanner.Request r = request("08:00", "11:00"); r.tasks.add(move("a", 90, 7));
            AgentPlanner.Plan p = AgentPlanner.build(r); eq(p.slots.size(), 1, "Source schedule remains a single interval");
            eq(p.slots.get(0).end, at("09:30"), "Movable schedule duration remains exact");
            eq(p.slots.get(0).part, 1, "Source ordinal is one"); eq(p.slots.get(0).totalParts, 1, "Source has no split metadata");
        });
        test("tied moves preserve the longer free interval for the longer task", () -> {
            AgentPlanner.Request r = request("08:00", "11:00");
            r.busy.add(busy("09:30", "10:00")); r.tasks.add(move("a", 60, 7)); r.tasks.add(move("b", 90, 8));
            AgentPlanner.Plan p = AgentPlanner.build(r);
            eq(p.slots.size(), 2, "Both complete moves fit around the fixed interval");
            eq(p.slots.get(0).task.id, "b", "Long move uses the only ninety-minute gap");
            eq(p.slots.get(0).start, at("08:00"), "Long move begins in the first gap");
            eq(p.slots.get(0).end, at("09:30"), "Long move ends at the fixed interval");
            eq(p.slots.get(1).task.id, "a", "Short move uses the remaining hour");
            eq(p.slots.get(1).start, at("10:00"), "Short move begins after the fixed interval");
            eq(p.slots.get(1).end, at("11:00"), "Short move retains its entire duration");
            eq(p.unscheduled.size(), 0, "Feasible complete movement is not cancelled");
            eq(AgentPlanner.validateSelection(r, p.slots, r.busy).size(), 0, "Every proposed move satisfies real constraints");
        });
        test("new tasks cannot consume capacity required by selected moves", () -> {
            AgentPlanner.Request r = request("08:00", "10:00");
            AgentPlanner.Task added = task("new", 60, 2), existing = move("existing", 90, 7);
            added.deadline = LocalTime.of(9, 0); existing.priority = 0; r.tasks.add(added); r.tasks.add(existing);
            AgentPlanner.Plan p = AgentPlanner.build(r);
            eq(p.slots.size(), 1, "Complete existing move survives an extra task that does not fit");
            eq(p.slots.get(0).task.id, "existing", "Selected movement receives capacity before a new high-priority task");
            eq(p.slots.get(0).start, at("08:00"), "Selected move gets an available continuous interval");
            eq(p.slots.get(0).end, at("09:30"), "Selected movement retains all ninety minutes");
            eq(p.unscheduled.size(), 1, "Only the extra task is left unproposed");
            eq(p.unscheduled.get(0).id, "new", "Unplaced new task is reported");
            eq(AgentPlanner.validateSelection(r, p.slots, r.busy).size(), 0, "Selected move is still valid");
        });
        test("one failed move cancels the entire optimization", () -> {
            AgentPlanner.Request r = request("08:00", "10:00"); r.tasks.add(move("a", 30, 7)); r.tasks.add(move("b", 100, 8)); r.tasks.add(task("new", 10, 0));
            AgentPlanner.Plan p = AgentPlanner.build(r); eq(p.slots.size(), 0, "No unsafe partial movement proposal");
            eq(p.unscheduled.size(), 3, "Cancelled optimization reports every unproposed task"); check(!p.warnings.isEmpty(), "Atomic move cancellation is explained");
        });
        test("request limits reject malformed user input safely", () -> {
            invalid(null, "Missing request"); AgentPlanner.Request r = request("08:00", "10:00"); r.tasks.add(task("a", 20, 1));
            r.date = null; invalid(r, "Missing date"); r.date = DAY; r.now = null; invalid(r, "Missing current time");
            r.now = at("07:00"); r.windowStart = null; invalid(r, "Missing window start"); r.windowStart = LocalTime.of(22, 0); r.windowEnd = LocalTime.of(8, 0); invalid(r, "Cross-day window");
            r.windowStart = LocalTime.of(8, 0); r.windowEnd = LocalTime.of(8, 0); invalid(r, "Empty window"); r.windowEnd = LocalTime.of(10, 0);
            r.focusMinutes = 14; invalid(r, "Focus too short"); r.focusMinutes = 121; invalid(r, "Focus too long"); r.focusMinutes = 50;
            r.breakMinutes = -1; invalid(r, "Negative rest"); r.breakMinutes = 61; invalid(r, "Rest too long");
            r.breakMinutes = 0; for (int i = 1; i <= 40; i++) r.tasks.add(task("task" + i, 1, 1)); invalid(r, "More than forty tasks");
        });
        test("task limits and identity are validated", () -> {
            AgentPlanner.Request r = request("08:00", "10:00"); AgentPlanner.Task t = task("a", 20, 1); r.tasks.add(t);
            t.title = " \t"; invalid(r, "Blank title");
            char[] longTitle = new char[121]; Arrays.fill(longTitle, 'a');
            t.title = new String(longTitle); invalid(r, "Overlong title"); t.title = "正常";
            t.minutes = 0; invalid(r, "Zero duration"); t.minutes = 721; invalid(r, "Excessive duration"); t.minutes = 20;
            t.priority = -1; invalid(r, "Negative priority"); t.priority = 3; invalid(r, "Unknown priority"); t.priority = 1;
            t.sourceId = -1; invalid(r, "Negative source id"); t.sourceId = 0; r.tasks.add(task("a", 10, 1)); invalid(r, "Duplicate task id");
            r.tasks.clear(); r.tasks.add(move("a", 20, 7)); r.tasks.add(move("b", 20, 7)); invalid(r, "Duplicate movable source");
        });
        test("malformed busy intervals cannot disappear silently", () -> {
            AgentPlanner.Request r = request("08:00", "10:00"); r.tasks.add(task("a", 20, 1)); r.busy.add(busy("09:00", "08:00")); invalid(r, "Reversed fixed interval");
            r.busy.clear(); r.busy.add(null); invalid(r, "Missing fixed interval");
        });
        test("selection rejects null malformed and unknown slots", () -> {
            AgentPlanner.Request r = request("08:00", "10:00"); AgentPlanner.Task t = task("a", 20, 1); r.tasks.add(t);
            rejected(r, null, r.busy, "Missing selection rejected"); rejected(r, Collections.emptyList(), r.busy, "Empty selection rejected");
            rejected(r, Arrays.asList((AgentPlanner.Slot)null), r.busy, "Null slot rejected");
            rejected(r, Arrays.asList(slot(task("unknown", 20, 1), "08:00", "08:20")), r.busy, "Unknown task rejected");
            AgentPlanner.Slot s = slot(t, "08:00", "08:20"); s.start = null; rejected(r, Arrays.asList(s), r.busy, "Missing time rejected");
            s = slot(t, "08:20", "08:00"); rejected(r, Arrays.asList(s), r.busy, "Reversed slot rejected");
            rejected(r, Arrays.asList(slot(t, "08:00", "08:20")), null, "Missing actual occupancy rejected");
        });
        test("selection must stay in current day window and deadline", () -> {
            AgentPlanner.Request r = request("08:00", "10:00"); r.now = at("08:01"); AgentPlanner.Task t = task("a", 20, 1); t.deadline = LocalTime.of(9, 0); r.tasks.add(t);
            rejected(r, Arrays.asList(slot(t, "07:50", "08:10")), r.busy, "Before window rejected");
            rejected(r, Arrays.asList(slot(t, "08:00", "08:20")), r.busy, "Before rounded now rejected");
            rejected(r, Arrays.asList(slot(t, "09:50", "10:10")), r.busy, "After window rejected");
            rejected(r, Arrays.asList(slot(t, "08:50", "09:10")), r.busy, "After deadline rejected");
            AgentPlanner.Slot otherDay = slot(t, "08:05", "08:25"); otherDay.start = otherDay.start.plusDays(1); otherDay.end = otherDay.end.plusDays(1);
            rejected(r, Arrays.asList(otherDay), r.busy, "Another day rejected");
            eq(AgentPlanner.validateSelection(r, Arrays.asList(slot(t, "08:05", "08:25")), r.busy).size(), 0, "Future same-day selection is valid");
        });
        test("selection rechecks real occupancy including unselected originals", () -> {
            AgentPlanner.Request r = request("08:00", "12:00"); AgentPlanner.Task a = move("a", 30, 7), b = move("b", 30, 8); r.tasks.add(a); r.tasks.add(b);
            AgentPlanner.Slot proposedA = slot(a, "09:00", "09:30"); AgentPlanner.Busy originalB = busy("09:00", "10:00"); originalB.sourceId = 8;
            rejected(r, Arrays.asList(proposedA), Arrays.asList(originalB), "Unselected original still blocks a selected movement");
            originalB.sourceId = 7; rejected(r, Arrays.asList(proposedA), Arrays.asList(originalB), "Caller supplied current busy is authoritative even for a matching source");
            eq(AgentPlanner.validateSelection(r, Arrays.asList(proposedA), Collections.emptyList()).size(), 0, "Chosen source can move after caller excludes its actual original");
        });
        test("selection detects overlaps but accepts adjacency", () -> {
            AgentPlanner.Request r = request("08:00", "10:00"); AgentPlanner.Task a = task("a", 30, 1), b = task("b", 30, 1); r.tasks.add(a); r.tasks.add(b);
            rejected(r, Arrays.asList(slot(a, "08:00", "08:30"), slot(b, "08:15", "08:45")), r.busy, "Overlapping selected slots rejected");
            eq(AgentPlanner.validateSelection(r, Arrays.asList(slot(a, "08:00", "08:30"), slot(b, "08:30", "09:00")), r.busy).size(), 0, "Adjacent selected slots accepted");
        });
        test("movement selection preserves exact source duration and uniqueness", () -> {
            AgentPlanner.Request r = request("08:00", "12:00"); AgentPlanner.Task t = move("a", 90, 7); r.tasks.add(t);
            rejected(r, Arrays.asList(slot(t, "08:00", "08:30")), r.busy, "Shortened original rejected");
            rejected(r, Arrays.asList(slot(t, "08:00", "09:30"), slot(t, "10:00", "11:30")), r.busy, "Duplicate movement rejected");
            AgentPlanner.Slot s = slot(t, "08:00", "09:30"); s.part = 1; s.totalParts = 2; rejected(r, Arrays.asList(s), r.busy, "Split original rejected");
            s = slot(t, "08:00", "09:30"); s.end = s.end.plusSeconds(1); rejected(r, Arrays.asList(s), r.busy, "Subminute duration tampering rejected");
        });
        test("individual generated split blocks can be accepted", () -> {
            AgentPlanner.Request r = request("08:00", "11:00"); r.focusMinutes = 50; r.breakMinutes = 10; AgentPlanner.Task t = task("a", 120, 1); r.tasks.add(t);
            AgentPlanner.Plan p = AgentPlanner.build(r);
            eq(AgentPlanner.validateSelection(r, Arrays.asList(p.slots.get(1)), r.busy).size(), 0, "One middle block remains selectable");
            AgentPlanner.Slot duplicate = p.slots.get(0); rejected(r, Arrays.asList(duplicate, duplicate), r.busy, "Duplicate block rejected");
            AgentPlanner.Slot bad = slot(t, "08:00", "08:49"); bad.part = 1; bad.totalParts = 3; rejected(r, Arrays.asList(bad), r.busy, "Wrong block duration rejected");
            bad = slot(t, "08:00", "08:50"); bad.part = 4; bad.totalParts = 3; rejected(r, Arrays.asList(bad), r.busy, "Invalid part ordinal rejected");
        });
        test("selection cannot forge task data or erase required breaks", () -> {
            AgentPlanner.Request r = request("08:00", "11:00"); r.focusMinutes = 50; r.breakMinutes = 10; AgentPlanner.Task t = task("a", 100, 1); r.tasks.add(t);
            AgentPlanner.Task forged = task("a", 100, 1); forged.title = "被替换的标题"; AgentPlanner.Slot s = slot(forged, "08:00", "08:50"); s.totalParts = 2;
            rejected(r, Arrays.asList(s), r.busy, "Matching id cannot change saved task content");
            AgentPlanner.Slot first = slot(t, "08:00", "08:50"), second = slot(t, "08:50", "09:40"); first.totalParts = second.totalParts = 2; second.part = 2;
            rejected(r, Arrays.asList(first, second), r.busy, "Focus parts require the requested break");
        });
        test("selection rechecks reserved rest against restored and selected schedules", () -> {
            AgentPlanner.Request r = request("08:00", "11:00"); r.focusMinutes = 50; r.breakMinutes = 10;
            AgentPlanner.Task focus = task("focus", 100, 2), other = task("other", 10, 0); r.tasks.add(focus); r.tasks.add(other);
            AgentPlanner.Slot first = slot(focus, "08:00", "08:50"), second = slot(focus, "09:00", "09:50");
            first.totalParts = second.totalParts = 2; second.part = 2;
            rejected(r, Arrays.asList(first, second), Arrays.asList(busy("08:50", "09:00")), "A restored original cannot occupy the required rest");
            rejected(r, Arrays.asList(first, second, slot(other, "08:50", "09:00")), r.busy, "Another selected task cannot occupy the required rest");
            eq(AgentPlanner.validateSelection(r, Arrays.asList(first), Arrays.asList(busy("08:50", "09:00"))).size(), 0, "One accepted block does not impose rest after the last accepted work");
        });
        test("Unicode whitespace title is not a real task title", () -> {
            AgentPlanner.Request r = request("08:00", "10:00"); AgentPlanner.Task t = task("a", 20, 1); t.title = "\u3000\u2003"; r.tasks.add(t);
            invalid(r, "Unicode blank title");
        });
        if (failures != 0) throw new AssertionError(failures + " of " + tests + " planner tests failed");
        System.out.println("PASS: " + tests + " planner tests, " + assertions + " assertions");
    }
}

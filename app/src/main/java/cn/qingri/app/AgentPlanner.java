package cn.qingri.app;

import java.time.*;
import java.util.*;

public final class AgentPlanner {
    public static class Task {
        public String id="",title="",category="学习",sourceRevision="";
        public int minutes,priority=1;
        public LocalTime deadline;
        public boolean splittable=true;
        public long sourceId;
    }
    public static class Busy {
        public long sourceId;
        public LocalDateTime start,end;
        public String title="";
    }
    public static class Request {
        public LocalDate date;
        public LocalTime windowStart=LocalTime.of(8,0),windowEnd=LocalTime.of(22,0);
        public LocalDateTime now;
        public int focusMinutes=50,breakMinutes=10;
        public final List<Task> tasks=new ArrayList<>();
        public final List<Busy> busy=new ArrayList<>();
    }
    public static class Slot {
        public Task task;
        public LocalDateTime start,end;
        public String reason="";
        public int part=1,totalParts=1;
    }
    public static class Plan {
        public final List<Slot> slots=new ArrayList<>();
        public final List<Task> unscheduled=new ArrayList<>();
        public final List<String> warnings=new ArrayList<>();
        public final List<String> steps=new ArrayList<>();
    }
    /** Deterministic feasibility tool used by the online Agent; this method never writes schedules. */
    public static Plan build(Request request) {
        Plan plan = new Plan();
        plan.steps.add("校验日期、当天窗口、任务身份、时长及专注和休息设置。");
        List<String> errors = validateRequest(request);
        if (!errors.isEmpty()) {
            plan.warnings.addAll(errors);
            if (request != null) for (Task task : request.tasks) if (task != null) plan.unscheduled.add(task);
            return plan;
        }

        Bounds bounds = bounds(request);
        List<Interval> occupied = clippedBusy(request.busy, bounds);
        plan.steps.add("将固定占用裁剪到可用窗口并合并为" + occupied.size() + "段；当天起点不早于当前时间向上取整的5分钟边界。");
        List<Task> ordered = new ArrayList<>(request.tasks);
        ordered.sort(Comparator.comparing((Task t) -> t.sourceId == 0)
                .thenComparing((Task t) -> t.deadline, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing((Task a, Task b) -> Integer.compare(b.priority, a.priority))
                .thenComparing((Task a, Task b) -> a.sourceId > 0 ? Integer.compare(b.minutes, a.minutes) : 0)
                .thenComparing(t -> t.id));
        plan.steps.add("先完整安排选中的原日程，再用剩余空闲安排新任务；各组按截止时间从早到晚、优先级从高到低排序，原日程同条件下先长后短，最后按任务标识排序，共" + ordered.size() + "项。");

        int scheduledTasks = 0;
        boolean failedMove = false;
        for (Task task : ordered) {
            LocalDateTime limit = bounds.end;
            if (task.deadline != null) limit = earlier(limit, request.date.atTime(task.deadline));
            int parts = partCount(request, task);
            List<Interval> trial = new ArrayList<>(occupied);
            List<Slot> proposed = new ArrayList<>();
            LocalDateTime nextStart = bounds.start;
            for (int part = 1; part <= parts; part++) {
                int minutes = partMinutes(request, task, part);
                int rest = part < parts ? request.breakMinutes : 0;
                LocalDateTime start = firstFit(trial, nextStart, limit, minutes + rest);
                if (start == null) break;
                Slot slot = new Slot();
                slot.task = task; slot.start = start; slot.end = start.plusMinutes(minutes);
                slot.part = part; slot.totalParts = parts;
                slot.reason = reason(task, part, parts);
                proposed.add(slot);
                trial.add(new Interval(start, slot.end.plusMinutes(rest)));
                trial = merge(trial);
                nextStart = slot.end.plusMinutes(rest);
            }
            if (proposed.size() != parts) {
                plan.unscheduled.add(task);
                plan.warnings.add("“" + task.title + "”在当前窗口、固定占用和截止条件下未找到完整时段，未保留部分时段。");
                if (task.sourceId > 0) { failedMove = true; break; }
            } else {
                occupied = trial;
                plan.slots.addAll(proposed);
                scheduledTasks++;
            }
        }
        if (failedMove) {
            plan.slots.clear();
            plan.unscheduled.clear();
            plan.unscheduled.addAll(ordered);
            plan.warnings.add("当前条件下未找到选中原日程的全部完整时段，本次不生成任何建议，原安排保持不变。");
            plan.steps.add("按上述顺序查找后未找到全部完整移动时段，撤销本次全部草拟时段。");
        } else {
            plan.slots.sort(Comparator.comparing((Slot s) -> s.start).thenComparing(s -> s.task.id).thenComparingInt(s -> s.part));
            plan.steps.add("逐项寻找完整空闲时段，专注块间预留" + request.breakMinutes + "分钟休息；已排入" + scheduledTasks + "项，未排入" + plan.unscheduled.size() + "项。");
        }
        return plan;
    }

    /** currentBusy must include every original schedule that is not actually selected for movement. */
    public static List<String> validateSelection(Request request, List<Slot> selected, List<Busy> currentBusy) {
        List<String> errors = validateRequest(request);
        if (!errors.isEmpty()) return errors;
        if (selected == null || selected.isEmpty()) { errors.add("请选择至少一条建议。"); return errors; }
        if (currentBusy == null) { errors.add("缺少当前真实日程占用，无法校验。"); return errors; }
        validateBusy(currentBusy, errors);
        if (!errors.isEmpty()) return errors;

        Bounds bounds = bounds(request);
        Map<String, Task> tasks = new HashMap<>();
        for (Task task : request.tasks) tasks.put(task.id, task);
        Map<String, Set<Integer>> chosenParts = new HashMap<>();
        Map<String, List<Slot>> groups = new HashMap<>();
        Set<Long> movedSources = new HashSet<>();
        List<Slot> timed = new ArrayList<>();
        for (Slot slot : selected) {
            if (slot == null || slot.task == null) { errors.add("建议缺少任务信息。"); continue; }
            Task task = tasks.get(slot.task.id);
            if (task == null) { errors.add("建议包含不属于本次请求的任务。"); continue; }
            if (!sameTask(task, slot.task)) errors.add("建议中的任务信息已改变，请重新生成。");
            int parts = partCount(request, task);
            if (slot.totalParts != parts || slot.part < 1 || slot.part > parts) {
                errors.add("“" + task.title + "”的分块编号或数量无效。");
            } else {
                Set<Integer> seen = chosenParts.computeIfAbsent(task.id, ignored -> new HashSet<>());
                if (!seen.add(slot.part)) errors.add("同一任务分块不能重复采纳。");
            }
            if (task.sourceId > 0 && !movedSources.add(task.sourceId)) errors.add("同一原日程只能采纳一个完整移动时段。");
            if (slot.start == null || slot.end == null || !slot.start.isBefore(slot.end)) {
                errors.add("“" + task.title + "”的起止时间无效。"); continue;
            }
            timed.add(slot);
            groups.computeIfAbsent(task.id, ignored -> new ArrayList<>()).add(slot);
            if (!slot.start.toLocalDate().equals(request.date) || !slot.end.toLocalDate().equals(request.date)
                    || slot.start.isBefore(bounds.start) || slot.end.isAfter(bounds.end)) {
                errors.add("“" + task.title + "”超出当天可用窗口或早于当前可安排时间。");
            }
            if (slot.start.isBefore(request.now)) errors.add("不能采纳已经开始或过去的时段。");
            if (task.deadline != null && slot.end.isAfter(request.date.atTime(task.deadline))) errors.add("“" + task.title + "”超过截止时间。");
            if (slot.part >= 1 && slot.part <= parts
                    && !Duration.between(slot.start, slot.end).equals(Duration.ofMinutes(partMinutes(request, task, slot.part)))) {
                errors.add(task.sourceId > 0 ? "移动原日程必须完整保留原时长。" : "任务分块的时长不符合本次专注设置。");
            }
            // Do not exclude busy entries by sourceId here: the caller has already excluded only chosen originals.
            for (Busy busy : currentBusy) {
                if (overlaps(slot.start, slot.end, busy.start, busy.end)) {
                    errors.add("“" + task.title + "”与当前真实日程冲突。"); break;
                }
            }
        }

        timed.sort(Comparator.comparing((Slot slot) -> slot.start).thenComparing(slot -> slot.end));
        LocalDateTime latestEnd = null;
        for (Slot slot : timed) {
            if (latestEnd != null && slot.start.isBefore(latestEnd)) {
                errors.add("所选建议之间存在时间重叠。"); break;
            }
            latestEnd = latestEnd == null ? slot.end : later(latestEnd, slot.end);
        }
        for (List<Slot> group : groups.values()) {
            group.sort(Comparator.comparingInt(slot -> slot.part));
            for (int i = 1; i < group.size(); i++) {
                Slot previous = group.get(i - 1), next = group.get(i);
                if (next.start.isBefore(previous.end)
                        || Duration.between(previous.end, next.start).compareTo(Duration.ofMinutes(request.breakMinutes)) < 0) {
                    errors.add("同一任务的专注块顺序无效或缺少休息间隔。"); break;
                }
                if (request.breakMinutes > 0 && previous.task.sourceId == 0 && previous.task.splittable) {
                    LocalDateTime restEnd = previous.end.plusMinutes(request.breakMinutes);
                    for (Busy busy : currentBusy) {
                        if (overlaps(previous.end, restEnd, busy.start, busy.end)) {
                            errors.add("专注块之间的休息与当前真实日程冲突。"); break;
                        }
                    }
                    for (Slot other : timed) {
                        if (overlaps(previous.end, restEnd, other.start, other.end)) {
                            errors.add("专注块之间的休息被其他所选建议占用。"); break;
                        }
                    }
                }
            }
        }
        return errors;
    }

    private static List<String> validateRequest(Request request) {
        List<String> errors = new ArrayList<>();
        if (request == null) { errors.add("缺少排程请求。"); return errors; }
        if (request.date == null || request.now == null) errors.add("日期和当前时间不能为空。");
        if (request.windowStart == null || request.windowEnd == null || !request.windowStart.isBefore(request.windowEnd)) errors.add("可用窗口必须是同一天内起点早于终点的时段。");
        if (request.focusMinutes < 15 || request.focusMinutes > 120) errors.add("专注时长须为15至120分钟。");
        if (request.breakMinutes < 0 || request.breakMinutes > 60) errors.add("休息时长须为0至60分钟。");
        if (request.tasks.size() > 40) errors.add("每次最多安排40项任务。");
        Set<String> ids = new HashSet<>();
        Set<Long> sourceIds = new HashSet<>();
        for (Task task : request.tasks) {
            if (task == null) { errors.add("任务不能为空。"); continue; }
            if (blank(task.id) || !ids.add(task.id)) errors.add("任务标识不能为空或重复。");
            if (blank(task.title) || task.title.length() > 120) errors.add("任务标题须为1至120个字符且不能全为空白。");
            if (task.minutes < 1 || task.minutes > 720) errors.add("任务时长须为1至720分钟。");
            if (task.priority < 0 || task.priority > 2) errors.add("任务优先级须为0、1或2。");
            if (task.sourceId < 0) errors.add("原日程标识不能为负数。");
            if (task.sourceId > 0 && !sourceIds.add(task.sourceId)) errors.add("同一原日程不能作为多个移动任务。");
        }
        validateBusy(request.busy, errors);
        return errors;
    }

    private static void validateBusy(List<Busy> busy, List<String> errors) {
        for (Busy item : busy) {
            if (item == null || item.start == null || item.end == null || !item.start.isBefore(item.end)) {
                errors.add("固定日程包含无效起止时间，无法安全排程。"); return;
            }
        }
    }

    private static Bounds bounds(Request request) {
        LocalDateTime start = request.date.atTime(request.windowStart), end = request.date.atTime(request.windowEnd);
        if (request.date.isBefore(request.now.toLocalDate())) start = end;
        else if (request.date.equals(request.now.toLocalDate())) {
            int minute = request.now.getHour() * 60 + request.now.getMinute();
            if (request.now.getSecond() != 0 || request.now.getNano() != 0) minute++;
            int rounded = ((minute + 4) / 5) * 5;
            start = rounded >= 1440 ? end : later(start, request.date.atStartOfDay().plusMinutes(rounded));
            if (start.isAfter(end)) start = end;
        }
        return new Bounds(start, end);
    }

    private static int partCount(Request request, Task task) {
        return task.sourceId > 0 || !task.splittable ? 1 : (task.minutes + request.focusMinutes - 1) / request.focusMinutes;
    }

    private static int partMinutes(Request request, Task task, int part) {
        return task.sourceId > 0 || !task.splittable ? task.minutes : Math.min(request.focusMinutes, task.minutes - (part - 1) * request.focusMinutes);
    }

    private static String reason(Task task, int part, int parts) {
        String priority = task.priority == 2 ? "高优先" : task.priority == 0 ? "低优先" : "普通优先";
        String result = "按" + priority + "顺序放入空闲时段";
        if (task.deadline != null) result += "，在" + task.deadline + "截止前完成";
        if (task.sourceId > 0) result += "；完整保留原时长";
        else if (parts > 1) result += "；专注块" + part + "/" + parts;
        return result + "。";
    }

    private static boolean sameTask(Task a, Task b) {
        return Objects.equals(a.id, b.id) && Objects.equals(a.title, b.title) && Objects.equals(a.category, b.category)
                && Objects.equals(a.sourceRevision, b.sourceRevision) && a.minutes == b.minutes && a.priority == b.priority
                && Objects.equals(a.deadline, b.deadline) && a.splittable == b.splittable && a.sourceId == b.sourceId;
    }

    private static List<Interval> clippedBusy(List<Busy> busy, Bounds bounds) {
        List<Interval> intervals = new ArrayList<>();
        for (Busy item : busy) {
            LocalDateTime start = later(item.start, bounds.start), end = earlier(item.end, bounds.end);
            if (start.isBefore(end)) intervals.add(new Interval(start, end));
        }
        return merge(intervals);
    }

    private static List<Interval> merge(List<Interval> intervals) {
        List<Interval> sorted = new ArrayList<>(intervals);
        sorted.sort(Comparator.comparing((Interval interval) -> interval.start).thenComparing(interval -> interval.end));
        List<Interval> merged = new ArrayList<>();
        for (Interval interval : sorted) {
            if (merged.isEmpty() || interval.start.isAfter(merged.get(merged.size() - 1).end)) merged.add(interval);
            else {
                Interval previous = merged.remove(merged.size() - 1);
                merged.add(new Interval(previous.start, later(previous.end, interval.end)));
            }
        }
        return merged;
    }

    private static LocalDateTime firstFit(List<Interval> occupied, LocalDateTime start, LocalDateTime limit, int minutes) {
        LocalDateTime cursor = start;
        Duration required = Duration.ofMinutes(minutes);
        for (Interval interval : occupied) {
            if (!interval.end.isAfter(cursor)) continue;
            LocalDateTime freeEnd = earlier(interval.start, limit);
            if (Duration.between(cursor, freeEnd).compareTo(required) >= 0) return cursor;
            cursor = later(cursor, interval.end);
            if (!cursor.isBefore(limit)) return null;
        }
        return Duration.between(cursor, limit).compareTo(required) >= 0 ? cursor : null;
    }

    private static boolean overlaps(LocalDateTime aStart, LocalDateTime aEnd, LocalDateTime bStart, LocalDateTime bEnd) {
        return aStart.isBefore(bEnd) && bStart.isBefore(aEnd);
    }
    private static boolean blank(String value) {
        if (value == null || value.isEmpty()) return true;
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (!Character.isWhitespace(character) && !Character.isSpaceChar(character)) return false;
        }
        return true;
    }
    private static LocalDateTime earlier(LocalDateTime a, LocalDateTime b) { return a.isBefore(b) ? a : b; }
    private static LocalDateTime later(LocalDateTime a, LocalDateTime b) { return a.isAfter(b) ? a : b; }
    private static final class Bounds {
        final LocalDateTime start, end;
        Bounds(LocalDateTime start, LocalDateTime end) { this.start = start; this.end = end; }
    }
    private static final class Interval {
        final LocalDateTime start, end;
        Interval(LocalDateTime start, LocalDateTime end) { this.start = start; this.end = end; }
    }
}

package cn.qingri.app;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.CancellationException;
import javax.net.ssl.HttpsURLConnection;

/** Bounded, read/draft-only Chat Completions driver. No credentials are logged. */
public class DeepSeekAgent {
    public interface Transport {
        JSONObject request(JSONObject body, String key) throws Exception;
        void cancel();
    }
    public interface ToolHandler { String handle(String name, JSONObject arguments) throws Exception; }
    public interface Progress { void update(String message); }

    private static final int MAX_ROUNDS = 4, MAX_CALLS = 8;
    private static final int MAX_REQUEST = 128 * 1024, MAX_RESPONSE = 1024 * 1024;
    private static final int MAX_TOOL_RESULT = 32 * 1024;
    private static final long TOTAL_MILLIS = 120000L;
    private static final String TOOL_ERROR = "{\"error\":\"本地工具未能完成，请调整要求后重试。\"}";
    private final Transport transport;
    private volatile boolean cancelled, timedOut;
    private boolean running;

    public DeepSeekAgent() { this(new HttpTransport()); }
    public DeepSeekAgent(Transport transport) {
        if (transport == null) throw new IllegalArgumentException("缺少模型传输配置。");
        this.transport = transport;
    }

    /** Cancellation is terminal for this instance. Create a new agent for a new run. */
    public void cancel() {
        cancelled = true;
        disconnect();
    }
    private void disconnect() {
        try { transport.cancel(); } catch (RuntimeException ignored) { /* Never expose transport details. */ }
    }

    /** Returns model text only after draft_plan completed successfully; never creates a fallback plan. */
    public String run(String key, String model, JSONArray messages, JSONArray tools,
                      ToolHandler handler, Progress progress) {
        synchronized (this) {
            if (running) throw failure("已有生成任务正在运行。");
            running = true;
        }
        Timer watchdog = null;
        final long started = System.nanoTime();
        try {
            guard(started);
            String cleanKey = cleanKey(key);
            String cleanModel = model == null || model.trim().isEmpty() ? "deepseek-flash" : model.trim();
            if (!cleanModel.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,79}"))
                throw failure("模型名称无效，请检查设置。");
            if (messages == null || messages.length() == 0 || tools == null || handler == null)
                throw failure("生成请求缺少必要信息。");
            JSONArray history = new JSONArray(messages.toString());
            JSONArray offered = new JSONArray(tools.toString());
            Set<String> names = toolNames(offered);
            if (!names.contains("draft_plan")) throw failure("缺少草拟日程工具。");
            Set<String> seen = new HashSet<>();
            int usedCalls = 0;
            boolean drafted = false;
            watchdog = new Timer("qingri-model-timeout", true);
            watchdog.schedule(new TimerTask() {
                @Override public void run() { timedOut = true; disconnect(); }
            }, TOTAL_MILLIS);
            for (int round = 0; round < MAX_ROUNDS; round++) {
                guard(started);
                JSONObject body = new JSONObject().put("model", cleanModel).put("messages", history)
                        .put("tools", offered).put("tool_choice", "auto").put("stream", false)
                        .put("thinking", new JSONObject().put("type", "disabled")).put("max_tokens", 4096);
                if (bytes(body.toString()) > MAX_REQUEST) throw failure("生成请求过大，请缩短目标或减少任务。");
                report(progress, "正在连接 DeepSeek（第" + (round + 1) + "轮）…");
                guard(started);
                JSONObject response = transport.request(body, cleanKey);
                guard(started);
                if (response == null || bytes(response.toString()) > MAX_RESPONSE)
                    throw failure("模型返回内容过大或为空，请重试。");
                if (response.has("error")) throw failure("DeepSeek 未能完成请求，请检查配置后重试。");
                JSONArray choices = response.optJSONArray("choices");
                if (choices == null || choices.length() != 1) throw protocol();
                JSONObject choice = choices.optJSONObject(0);
                if (choice == null) throw protocol();
                JSONObject assistant = choice.optJSONObject("message");
                if (assistant == null || !"assistant".equals(assistant.opt("role"))) throw protocol();
                String finish = string(choice, "finish_reason");
                Object callValue = assistant.opt("tool_calls");
                if (callValue != null && callValue != JSONObject.NULL && !(callValue instanceof JSONArray)) throw protocol();
                JSONArray calls = assistant.optJSONArray("tool_calls");
                if (calls == null || calls.length() == 0) {
                    if (!"stop".equals(finish)) throw failure("模型回答未完整结束，请重试。");
                    String content = string(assistant, "content");
                    if (!drafted) throw failure("模型尚未生成有效日程草案，请重试。");
                    if (content.trim().isEmpty()) throw failure("模型未返回建议说明，请重试。");
                    return content;
                }
                if (!"tool_calls".equals(finish)) throw protocol();
                if (usedCalls + calls.length() > MAX_CALLS) throw failure("模型工具调用过多，请简化目标后重试。");
                // Validate the entire batch before invoking even a read-only handler.
                List<Invocation> batch = new ArrayList<>();
                for (int i = 0; i < calls.length(); i++) {
                    JSONObject call = calls.optJSONObject(i);
                    if (call == null || !"function".equals(call.opt("type"))) throw protocol();
                    String id = string(call, "id");
                    if (!id.matches("[A-Za-z0-9_-]{1,128}") || !seen.add(id)) throw protocol();
                    JSONObject function = call.optJSONObject("function");
                    if (function == null) throw protocol();
                    String name = string(function, "name");
                    if (!names.contains(name)) throw failure("模型请求了未授权工具，已停止生成。");
                    String rawArgs = string(function, "arguments");
                    if (bytes(rawArgs) > MAX_TOOL_RESULT || !StrictJson.object(rawArgs))
                        throw failure("模型工具参数无效，请重试。");
                    batch.add(new Invocation(id, name, new JSONObject(rawArgs)));
                }
                usedCalls += batch.size();
                // Retain every field, including null content and any reasoning/tool call fields.
                history.put(new JSONObject(assistant.toString()));
                for (Invocation invocation : batch) {
                    guard(started);
                    report(progress, "draft_plan".equals(invocation.name) ? "正在草拟日程…" : "正在读取本地时间信息…");
                    String result;
                    boolean successful = false;
                    try {
                        result = handler.handle(invocation.name, invocation.arguments);
                        guard(started);
                        if (result == null || bytes(result) > MAX_TOOL_RESULT || !StrictJson.object(result)) {
                            result = TOOL_ERROR;
                        } else {
                            JSONObject parsed = new JSONObject(result);
                            if (parsed.has("error")) result = TOOL_ERROR;
                            else successful = true;
                        }
                    } catch (CancellationException ex) { throw cancellation(); }
                    catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw cancellation(); }
                    catch (SafeFailure ex) { throw ex; }
                    catch (Exception ex) { guard(started); result = TOOL_ERROR; }
                    if ("draft_plan".equals(invocation.name) && successful) drafted = true;
                    history.put(new JSONObject().put("role", "tool").put("tool_call_id", invocation.id).put("content", result));
                }
            }
            throw failure("模型生成轮次已达上限，请简化目标后重试。");
        } catch (CancellationException ex) { throw cancellation(); }
        catch (SafeFailure ex) { throw ex; }
        catch (Exception ex) {
            guard(started);
            throw failure("无法连接 DeepSeek，请检查网络或稍后重试。");
        } finally {
            if (watchdog != null) watchdog.cancel();
            synchronized (this) { running = false; }
        }
    }

    private void guard(long started) {
        if (cancelled || Thread.currentThread().isInterrupted()) throw cancellation();
        if (timedOut || (System.nanoTime() - started) / 1000000L >= TOTAL_MILLIS)
            throw failure("生成超时，请稍后重试。");
    }
    private static void report(Progress progress, String message) {
        if (progress != null) progress.update(message);
    }
    private static String cleanKey(String key) {
        if (key == null) throw failure("尚未配置 DeepSeek API 密钥，请先在设置中填写。");
        for (int i = 0; i < key.length(); i++) if (Character.isISOControl(key.charAt(i)))
            throw failure("API 密钥格式无效，请重新填写。");
        String cleaned = key.trim();
        if (cleaned.isEmpty()) throw failure("尚未配置 DeepSeek API 密钥，请先在设置中填写。");
        if (cleaned.length() > 4096) throw failure("API 密钥格式无效，请重新填写。");
        for (int i = 0; i < cleaned.length(); i++) {
            char ch = cleaned.charAt(i);
            if (ch <= 32 || ch >= 127) throw failure("API 密钥格式无效，请重新填写。");
        }
        return cleaned;
    }
    private static Set<String> toolNames(JSONArray tools) throws Exception {
        Set<String> names = new HashSet<>();
        for (int i = 0; i < tools.length(); i++) {
            JSONObject tool = tools.optJSONObject(i);
            if (tool == null || !"function".equals(tool.opt("type"))) throw failure("本地工具配置无效。");
            JSONObject function = tool.optJSONObject("function");
            if (function == null) throw failure("本地工具配置无效。");
            String name = string(function, "name");
            if (!name.matches("[A-Za-z0-9_-]{1,64}") || !names.add(name)) throw failure("本地工具配置无效。");
        }
        return names;
    }
    private static int bytes(String value) { return value.getBytes(StandardCharsets.UTF_8).length; }
    private static String string(JSONObject object, String key) {
        Object value = object.opt(key);
        if (!(value instanceof String)) throw protocol();
        return (String) value;
    }
    private static SafeFailure protocol() { return failure("模型返回的工具协议无效，请重试。"); }
    private static SafeFailure failure(String safeMessage) { return new SafeFailure(safeMessage); }
    private static CancellationException cancellation() { return new CancellationException("已取消生成。"); }
    private static final class SafeFailure extends IllegalStateException {
        SafeFailure(String message) { super(message); }
    }
    private static final class Invocation {
        final String id, name;
        final JSONObject arguments;
        Invocation(String id, String name, JSONObject arguments) { this.id = id; this.name = name; this.arguments = arguments; }
    }

    private static final class HttpTransport implements Transport {
        private volatile HttpsURLConnection active;
        private volatile boolean cancelled;
        @Override public void cancel() {
            cancelled = true;
            HttpsURLConnection connection = active;
            if (connection != null) connection.disconnect();
        }
        @Override public JSONObject request(JSONObject body, String key) throws Exception {
            if (cancelled || Thread.currentThread().isInterrupted()) throw cancellation();
            byte[] request = body.toString().getBytes(StandardCharsets.UTF_8);
            if (request.length > MAX_REQUEST) throw failure("生成请求过大，请减少任务。");
            HttpsURLConnection connection = (HttpsURLConnection)
                    new URL("https://api.deepseek.com/chat/completions").openConnection();
            active = connection;
            try {
                if (cancelled) throw cancellation();
                // Never forward Authorization to a Location supplied by a server.
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(45000);
                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                connection.setUseCaches(false);
                connection.setRequestProperty("Authorization", "Bearer " + key);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                connection.setRequestProperty("Accept", "application/json");
                connection.setFixedLengthStreamingMode(request.length);
                try (OutputStream out = connection.getOutputStream()) { out.write(request); }
                if (cancelled) throw cancellation();
                int status = connection.getResponseCode();
                if (status == 401) throw failure("DeepSeek 密钥无效，请检查设置。");
                if (status == 403) throw failure("DeepSeek 拒绝访问，请检查密钥权限。");
                if (status == 429) throw failure("DeepSeek 请求过于频繁或额度不足，请稍后重试。");
                if (status >= 500) throw failure("DeepSeek 服务暂时不可用，请稍后重试。");
                if (status >= 300 && status < 400) throw failure("DeepSeek 返回异常重定向，已停止请求。");
                if (status < 200 || status >= 300) throw failure("DeepSeek 未能接受请求，请检查配置后重试。");
                long declared = connection.getContentLengthLong();
                if (declared > MAX_RESPONSE) throw failure("模型返回内容过大，请重试。");
                ByteArrayOutputStream response = new ByteArrayOutputStream();
                try (InputStream in = connection.getInputStream()) {
                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = in.read(buffer)) != -1) {
                        if (cancelled || Thread.currentThread().isInterrupted()) throw cancellation();
                        if (response.size() + count > MAX_RESPONSE) throw failure("模型返回内容过大，请重试。");
                        response.write(buffer, 0, count);
                    }
                }
                if (cancelled) throw cancellation();
                String json = new String(response.toByteArray(), StandardCharsets.UTF_8);
                if (!StrictJson.object(json)) throw protocol();
                return new JSONObject(json);
            } finally {
                if (active == connection) active = null;
                connection.disconnect();
            }
        }
    }

    /** Android's JSON parser accepts non-JSON syntax; validate strictly before trusting tool input. */
    private static final class StrictJson {
        private final String text;
        private int at;
        private StrictJson(String text) { this.text = text; }
        static boolean object(String text) {
            if (text == null) return false;
            try {
                StrictJson parser = new StrictJson(text);
                parser.ws();
                if (parser.at >= text.length() || text.charAt(parser.at) != '{') return false;
                parser.value(0); parser.ws();
                return parser.at == text.length();
            } catch (IllegalArgumentException ex) { return false; }
        }
        private void ws() { while (at < text.length() && " \n\r\t".indexOf(text.charAt(at)) >= 0) at++; }
        private void need(char expected) {
            ws(); if (at >= text.length() || text.charAt(at++) != expected) throw new IllegalArgumentException();
        }
        private boolean take(char expected) {
            ws(); if (at < text.length() && text.charAt(at) == expected) { at++; return true; } return false;
        }
        private void value(int depth) {
            if (depth > 32) throw new IllegalArgumentException();
            ws(); if (at >= text.length()) throw new IllegalArgumentException();
            char ch = text.charAt(at);
            if (ch == '{') {
                at++; if (take('}')) return;
                do { ws(); quoted(); need(':'); value(depth + 1); } while (take(','));
                need('}');
            } else if (ch == '[') {
                at++; if (take(']')) return;
                do { value(depth + 1); } while (take(',')); need(']');
            } else if (ch == '"') quoted();
            else if (ch == 't') literal("true");
            else if (ch == 'f') literal("false");
            else if (ch == 'n') literal("null");
            else number();
        }
        private void quoted() {
            if (at >= text.length() || text.charAt(at++) != '"') throw new IllegalArgumentException();
            while (at < text.length()) {
                char ch = text.charAt(at++);
                if (ch == '"') return;
                if (ch < 32) throw new IllegalArgumentException();
                if (ch == '\\') {
                    if (at >= text.length()) throw new IllegalArgumentException();
                    char escaped = text.charAt(at++);
                    if (escaped == 'u') {
                        for (int i = 0; i < 4; i++)
                            if (at >= text.length() || Character.digit(text.charAt(at++), 16) < 0) throw new IllegalArgumentException();
                    } else if ("\"\\/bfnrt".indexOf(escaped) < 0) throw new IllegalArgumentException();
                }
            }
            throw new IllegalArgumentException();
        }
        private void literal(String expected) {
            if (!text.startsWith(expected, at)) throw new IllegalArgumentException(); at += expected.length();
        }
        private void number() {
            int start = at;
            if (at < text.length() && text.charAt(at) == '-') at++;
            if (at < text.length() && text.charAt(at) == '0') at++;
            else {
                if (at >= text.length() || text.charAt(at) < '1' || text.charAt(at) > '9') throw new IllegalArgumentException();
                while (at < text.length() && text.charAt(at) >= '0' && text.charAt(at) <= '9') at++;
            }
            if (at < text.length() && text.charAt(at) == '.') { at++; digits(); }
            if (at < text.length() && (text.charAt(at) == 'e' || text.charAt(at) == 'E')) {
                at++; if (at < text.length() && (text.charAt(at) == '+' || text.charAt(at) == '-')) at++;
                digits();
            }
            if (at == start) throw new IllegalArgumentException();
        }
        private void digits() {
            int start = at;
            while (at < text.length() && text.charAt(at) >= '0' && text.charAt(at) <= '9') at++;
            if (start == at) throw new IllegalArgumentException();
        }
    }
}

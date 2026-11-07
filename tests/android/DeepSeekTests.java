package cn.qingri.app.tests;

import cn.qingri.app.DeepSeekAgent;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;

/** Offline protocol tests. Never sends an API key or connects to DeepSeek. */
public final class DeepSeekTests {
    private static int assertions;
    private interface Case { void run() throws Exception; }
    private static void check(boolean ok, String why) {
        assertions++;
        if (!ok) throw new AssertionError(why);
    }
    private static void fails(Case action, String why) throws Exception {
        try { action.run(); throw new AssertionError(why); }
        catch (IllegalStateException expected) {
            check(expected.getMessage() != null && !expected.getMessage().contains("sk-private")
                    && !expected.getMessage().contains("raw-private") && expected.getCause() == null,
                    why + " has a safe, cause-free error");
        }
    }
    private static JSONArray messages() throws Exception {
        return new JSONArray().put(new JSONObject().put("role", "user").put("content", "安排学习"));
    }
    private static JSONArray tools() throws Exception {
        JSONArray a = new JSONArray();
        for (String name : new String[]{"get_day_context", "draft_plan"}) {
            a.put(new JSONObject().put("type", "function").put("function", new JSONObject()
                    .put("name", name).put("parameters", new JSONObject().put("type", "object"))));
        }
        return a;
    }
    private static JSONObject call(String id, String name, String args) throws Exception {
        return new JSONObject().put("id", id).put("type", "function").put("function",
                new JSONObject().put("name", name).put("arguments", args));
    }
    private static JSONObject response(JSONArray calls, String content) throws Exception {
        JSONObject m = new JSONObject().put("role", "assistant")
                .put("content", content == null ? JSONObject.NULL : content);
        if (calls != null) m.put("tool_calls", calls);
        return new JSONObject().put("id", "chat-test").put("object", "chat.completion")
                .put("choices", new JSONArray().put(new JSONObject().put("index", 0)
                .put("message", m).put("finish_reason", calls == null ? "stop" : "tool_calls")));
    }
    private static final class Fake implements DeepSeekAgent.Transport {
        final List<JSONObject> requests = new ArrayList<>();
        final List<String> keys = new ArrayList<>();
        final List<JSONObject> replies = new ArrayList<>();
        int cancels;
        Exception error;
        Runnable duringRequest;
        Fake add(JSONObject response) { replies.add(response); return this; }
        @Override public JSONObject request(JSONObject body, String key) throws Exception {
            requests.add(new JSONObject(body.toString())); keys.add(key);
            if (duringRequest != null) duringRequest.run();
            if (error != null) throw error;
            if (requests.size() > replies.size()) throw new AssertionError("Unexpected extra request");
            return replies.get(requests.size() - 1);
        }
        @Override public void cancel() { cancels++; }
    }
    private static String run(Fake fake, DeepSeekAgent.ToolHandler handler) throws Exception {
        return new DeepSeekAgent(fake).run("sk-private", "", messages(), tools(), handler, null);
    }
    public static int run() throws Exception {
        assertions = 0;
        protocolRoundTrip();
        validationBeforeNetwork();
        malformedProtocol();
        budgets();
        safeErrorsAndCancellation();
        return assertions;
    }
    private static void protocolRoundTrip() throws Exception {
        JSONObject first = response(new JSONArray()
                .put(call("ctx_1", "get_day_context", "{\"date\":\"2026-11-05\"}"))
                .put(call("plan_1", "draft_plan", "{\"tasks\":[]}")), null);
        first.getJSONArray("choices").getJSONObject(0).getJSONObject("message")
                .put("reasoning_content", "preserve assistant fields");
        Fake f = new Fake().add(first).add(response(null, "已生成可预览建议"));
        final List<String> invoked = new ArrayList<>();
        JSONArray original = messages();
        String result = new DeepSeekAgent(f).run("  sk-private  ", "", original, tools(), (name, args) -> {
            invoked.add(name);
            if (name.equals("get_day_context")) {
                check("2026-11-05".equals(args.getString("date")), "Tool arguments are parsed objects");
                return "{\"busy\":[[\"09:00\",\"10:00\"]]}";
            }
            return "{\"slots\":[]}";
        }, message -> check(!message.contains("sk-private"), "Progress never contains the API key"));
        check("已生成可预览建议".equals(result), "Returns real final assistant content");
        check(invoked.size() == 2 && "draft_plan".equals(invoked.get(1)), "Executes offered tools in order");
        check(f.requests.size() == 2 && "sk-private".equals(f.keys.get(0)), "One key-normalized request per round");
        JSONObject body = f.requests.get(0);
        check("deepseek-flash".equals(body.getString("model")), "Default model is supplied to API");
        check(!body.getBoolean("stream") && "disabled".equals(body.getJSONObject("thinking").getString("type")), "Disables streaming and thinking");
        check(body.getJSONArray("tools").length() == 2, "Forwards actual tool definitions");
        JSONArray sent = f.requests.get(1).getJSONArray("messages");
        check(sent.length() == 4 && original.length() == 1, "Conversation history is copied without mutating caller");
        check(first.getJSONArray("choices").getJSONObject(0).getJSONObject("message").toString()
                .equals(sent.getJSONObject(1).toString()), "Preserves the complete assistant message");
        check("tool".equals(sent.getJSONObject(2).getString("role"))
                && "ctx_1".equals(sent.getJSONObject(2).getString("tool_call_id"))
                && "{\"busy\":[[\"09:00\",\"10:00\"]]}".equals(sent.getJSONObject(2).getString("content")), "Second round associates context result with its exact call id");
        check("plan_1".equals(sent.getJSONObject(3).getString("tool_call_id")), "Second tool result retains separate call id");
    }
    private static void validationBeforeNetwork() throws Exception {
        for (String key : new String[]{null, "", "   ", "sk-private\r\nInjected: x", "sk private"}) {
            Fake f = new Fake();
            fails(() -> new DeepSeekAgent(f).run(key, "", messages(), tools(), (n, a) -> "{}", null), "Invalid key rejected");
            check(f.requests.isEmpty(), "Invalid key sends zero requests");
        }
        Fake invalidModel = new Fake();
        fails(() -> new DeepSeekAgent(invalidModel).run("sk-private", "../unsafe model", messages(), tools(), (n, a) -> "{}", null), "Invalid model rejected");
        check(invalidModel.requests.isEmpty(), "Invalid model sends zero requests");
        Fake large = new Fake();
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 140000; i++) text.append('x');
        JSONArray oversized = new JSONArray().put(new JSONObject().put("role", "user").put("content", text.toString()));
        fails(() -> new DeepSeekAgent(large).run("sk-private", "", oversized, tools(), (n, a) -> "{}", null), "Request byte budget enforced before transport");
        check(large.requests.isEmpty(), "Oversized request sends zero requests");
    }
    private static void malformedProtocol() throws Exception {
        final int[] calls = {0};
        DeepSeekAgent.ToolHandler handler = (n, a) -> { calls[0]++; return "{}"; };
        fails(() -> run(new Fake().add(response(null, "模型未草拟")), handler), "No draft cannot be reported as a generated plan");
        check(calls[0] == 0, "Missing draft never triggers synthetic tool work");
        for (String args : new String[]{"[]", "null", "{bad", "{} trailing", "{'tasks':[]}", "{tasks:[]}", "{\"tasks\":[] ,}"}) {
            fails(() -> run(new Fake().add(response(new JSONArray().put(call("a", "draft_plan", args)), null)), handler), "Malformed/nonobject arguments rejected");
        }
        fails(() -> run(new Fake().add(new JSONObject().put("error", new JSONObject().put("message", "raw-private sk-private"))), handler), "API error payload is sanitized");
        fails(() -> run(new Fake().add(new JSONObject().put("choices", new JSONArray())), handler), "Missing choice rejected");
        fails(() -> run(new Fake().add(response(new JSONArray().put(call("a", "delete_events", "{}")), null)), handler), "Unadvertised tool rejected");
        fails(() -> run(new Fake().add(response(new JSONArray().put(call("same", "draft_plan", "{}"))
                .put(call("same", "draft_plan", "{}")), null)), handler), "Duplicate call ids in one round rejected");
        check(calls[0] == 0, "Entire malformed batch is validated before tool side effects");
        Fake duplicate = new Fake().add(response(new JSONArray().put(call("same", "get_day_context", "{}")), null))
                .add(response(new JSONArray().put(call("same", "draft_plan", "{}")), null));
        fails(() -> run(duplicate, handler), "Duplicate call ids across rounds rejected");
        check(calls[0] == 1, "Duplicate id is rejected before its handler");
        JSONObject truncated = response(null, "不完整");
        truncated.getJSONArray("choices").getJSONObject(0).put("finish_reason", "length");
        fails(() -> run(new Fake().add(response(new JSONArray().put(call("valid", "draft_plan", "{}")), null)).add(truncated), handler), "Truncated answer is not accepted as final");
    }
    private static void budgets() throws Exception {
        JSONArray tooMany = new JSONArray();
        for (int i = 0; i < 9; i++) tooMany.put(call("c" + i, "get_day_context", "{}"));
        final int[] called = {0};
        fails(() -> run(new Fake().add(response(tooMany, null)), (n, a) -> { called[0]++; return "{}"; }), "Tool call budget rejects a ninth call");
        check(called[0] == 0, "Oversized batch executes no tools");
        Fake rounds = new Fake();
        for (int i = 0; i < 4; i++) rounds.add(response(new JSONArray().put(call("r" + i, "get_day_context", "{}")), null));
        fails(() -> run(rounds, (n, a) -> "{}"), "Model loop terminates after four rounds");
        check(rounds.requests.size() == 4, "No fifth API round is sent");
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 1048600; i++) big.append('x');
        fails(() -> run(new Fake().add(response(null, big.toString())), (n, a) -> "{}"), "Response byte budget also applies to injected transport");
    }
    private static void safeErrorsAndCancellation() throws Exception {
        Fake network = new Fake(); network.error = new Exception("raw-private sk-private");
        fails(() -> run(network, (n, a) -> "{}"), "Network exception details are never shown");
        Fake errors = new Fake().add(response(new JSONArray().put(call("bad", "get_day_context", "{}"))
                .put(call("plan", "draft_plan", "{}")), null)).add(response(null, "可以预览"));
        run(errors, (n, a) -> { if (n.equals("get_day_context")) throw new Exception("raw-private sk-private"); return "{}"; });
        String error = errors.requests.get(1).getJSONArray("messages").getJSONObject(2).getString("content");
        check(new JSONObject(error).has("error") && !error.contains("private"), "Handler exceptions become safe JSON tool results");
        Fake badDraft = new Fake().add(response(new JSONArray().put(call("bad", "draft_plan", "{}")), null)).add(response(null, "伪造成功"));
        fails(() -> run(badDraft, (n, a) -> "{\"error\":\"raw-private sk-private\"}"), "Error draft does not count as a successful plan");
        check(!badDraft.requests.get(1).toString().contains("raw-private"), "Tool error JSON does not expose raw details");
        Fake cancelled = new Fake().add(response(new JSONArray().put(call("p", "draft_plan", "{}")), null));
        DeepSeekAgent agent = new DeepSeekAgent(cancelled);
        cancelled.duringRequest = agent::cancel;
        try { agent.run("sk-private", "", messages(), tools(), (n, a) -> "{}", null); throw new AssertionError("Cancelled run accepted"); }
        catch (CancellationException expected) { check(cancelled.cancels > 0, "Cancelling calls transport cancellation"); }
        Fake handlerCancel = new Fake().add(response(new JSONArray().put(call("p", "draft_plan", "{}")), null));
        try { run(handlerCancel, (n, a) -> { throw new CancellationException("raw-private"); }); throw new AssertionError("Handler cancellation swallowed"); }
        catch (CancellationException expected) { check(!expected.getMessage().contains("private"), "Handler cancellation remains cancellation and is sanitized"); }
        Fake pre = new Fake(); DeepSeekAgent preAgent = new DeepSeekAgent(pre); preAgent.cancel();
        try { preAgent.run("sk-private", "", messages(), tools(), (n, a) -> "{}", null); throw new AssertionError("Pre-cancel ignored"); }
        catch (CancellationException expected) { check(pre.requests.isEmpty(), "Pre-cancel sends zero requests"); }
    }
}

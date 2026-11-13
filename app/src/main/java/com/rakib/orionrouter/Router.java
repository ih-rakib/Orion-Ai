package com.rakib.orionrouter;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Sequential fallback router over OpenAI-compatible /chat/completions.
 * Every provider in the chain speaks the same request/response shape, so
 * routing is just: try endpoint, on any failure log it and try the next.
 *
 * One attempt per model, plus a single retry when a 429 carries a short
 * Retry-After (15s or less): a brief per-minute dip is worth one wait,
 * a long daily exhaustion is not — the chain moves on instead. 429 / 5xx /
 * timeout / 404 (dead model id) / 401 (bad key) / 400 (bad params) /
 * empty reply / network error all fall through to the next endpoint.
 * Nothing in the log ever contains an API key.
 *
 * One request runs at a time. {@link #requestCancel()} disconnects the
 * in-flight HTTP call and makes {@link #chat} return a stopped result;
 * the caller must not append anything to history for a stopped run.
 */
public class Router {

    public static final int CONNECT_TIMEOUT_MS = 15000;
    public static final int READ_TIMEOUT_MS = 90000;
    public static final long BETWEEN_ATTEMPTS_MS = 250;
    /** Only honour Retry-After waits this short; longer waits fall through to the next model. */
    public static final int MAX_RETRY_AFTER_SECONDS = 15;

    private static volatile boolean cancelled = false;
    private static volatile HttpURLConnection currentConn = null;

    /** Stops the in-flight request, if any. Safe to call from the UI thread. */
    public static void requestCancel() {
        cancelled = true;
        HttpURLConnection c = currentConn;
        if (c != null) {
            try { c.disconnect(); } catch (Exception ignored) {}
        }
    }

    private static void clearCurrent() {
        currentConn = null;
    }

    public interface Logger {
        void log(String line);
        /** Called on the worker thread just before each endpoint is tried. */
        void starting(int index, int total, String label);
    }

    /** One chat message: role is "system", "user", or "assistant". */
    public static class Message {
        public final String role;
        public final String content;
        /** For assistant messages: the "provider / model" label that answered, or null. */
        public final String by;

        public Message(String role, String content) {
            this(role, content, null);
        }

        public Message(String role, String content, String by) {
            this.role = role;
            this.content = content;
            this.by = by;
        }
    }

    /** One provider + one model. A provider with several models expands to several endpoints. */
    public static class Endpoint {
        public final String provider;
        public final String baseUrl;
        public final String key;
        public final String model;

        public Endpoint(String provider, String baseUrl, String key, String model) {
            this.provider = provider;
            this.baseUrl = baseUrl;
            this.key = key;
            this.model = model;
        }

        public String label() { return provider + " / " + model; }
    }

    public static class Result {
        public boolean ok = false;
        public boolean stopped = false;
        public String text = "";
        public String answeredBy = "";
        public int attempts = 0;
        /** One outcome per endpoint tried: provider -> "ok" / "limit" / "fail". */
        public final List<String[]> outcomes = new ArrayList<>();
    }

    public static Result chat(List<Endpoint> chain, List<Message> messages, int maxTokens,
                              double temperature, Logger logger) {
        cancelled = false;
        Result result = new Result();
        if (chain == null || chain.isEmpty()) {
            logger.log("No provider ready: enable at least one provider and paste its key, then Save.");
            return result;
        }
        if (messages == null || messages.isEmpty()) {
            logger.log("Nothing to send.");
            return result;
        }
        for (int i = 0; i < chain.size(); i++) {
            if (cancelled) return stopped(result, logger);
            Endpoint ep = chain.get(i);
            if (i > 0) {
                try { Thread.sleep(BETWEEN_ATTEMPTS_MS); } catch (InterruptedException ignored) {}
                if (cancelled) return stopped(result, logger);
            }
            logger.starting(i + 1, chain.size(), ep.label());
            boolean retried = false;
            long endpointStart = System.currentTimeMillis();
            while (true) {
                result.attempts++;
                Attempt attempt;
                try {
                    attempt = doOne(ep, messages, maxTokens, temperature);
                    clearCurrent();
                } catch (Exception e) {
                    clearCurrent();
                    if (cancelled) return stopped(result, logger);
                    attempt = new Attempt();
                    attempt.error = shortError(e);
                }
                long ms = System.currentTimeMillis() - endpointStart;
                if (cancelled) return stopped(result, logger);
                if (attempt.ok) {
                    logger.log("OK  " + ep.label() + "  (" + ms + " ms)");
                    result.outcomes.add(new String[]{ep.provider, "ok"});
                    result.ok = true;
                    result.text = attempt.text;
                    result.answeredBy = ep.label();
                    return result;
                }
                if (attempt.rateLimited && !retried
                        && attempt.retryAfterSeconds >= 1
                        && attempt.retryAfterSeconds <= MAX_RETRY_AFTER_SECONDS) {
                    retried = true;
                    logger.log("WAIT " + ep.label() + "  rate-limited, retrying once in "
                            + attempt.retryAfterSeconds + "s (provider asked)...");
                    if (!sleepCancellable(attempt.retryAfterSeconds * 1000L)) {
                        return stopped(result, logger);
                    }
                    endpointStart = System.currentTimeMillis();
                    continue;
                }
                logger.log("FAIL " + ep.label() + "  " + attempt.error + "  (" + ms + " ms)");
                result.outcomes.add(new String[]{ep.provider, attempt.rateLimited ? "limit" : "fail"});
                break;
            }
        }
        logger.log("All " + chain.size() + " endpoint(s) failed (" + result.attempts
                + " HTTP attempt(s)). Check keys, model names, and network, then try again.");
        return result;
    }

    private static Result stopped(Result result, Logger logger) {
        result.stopped = true;
        logger.log("Stopped.");
        return result;
    }

    /** Sleeps in small slices so Stop works even during a Retry-After wait. */
    private static boolean sleepCancellable(long ms) {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            if (cancelled) return false;
            try { Thread.sleep(250); } catch (InterruptedException ignored) {}
        }
        return !cancelled;
    }

    private static class Attempt {
        boolean ok = false;
        boolean rateLimited = false;
        int retryAfterSeconds = 0;
        String text = "";
        String error = "";
    }

    private static Attempt doOne(Endpoint ep, List<Message> messages, int maxTokens,
                                 double temperature) throws Exception {
        Attempt attempt = new Attempt();
        String base = ep.baseUrl.trim();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        URL url = new URL(base + "/chat/completions");

        JSONObject body = new JSONObject();
        body.put("model", ep.model);
        JSONArray arr = new JSONArray();
        for (Message m : messages) {
            JSONObject o = new JSONObject();
            o.put("role", m.role);
            o.put("content", m.content);
            arr.put(o);
        }
        body.put("messages", arr);
        body.put("max_tokens", maxTokens);
        body.put("temperature", temperature);
        body.put("stream", false);
        byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);

        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        currentConn = conn;
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("Authorization", "Bearer " + ep.key);
        // OpenRouter recommends these; other providers ignore them.
        conn.setRequestProperty("HTTP-Referer", "https://orion.local/");
        conn.setRequestProperty("X-Title", "Orion");
        conn.setRequestProperty("Content-Length", Integer.toString(payload.length));
        try {
            OutputStream out = conn.getOutputStream();
            out.write(payload);
            out.flush();
            out.close();
        } catch (SocketTimeoutException e) {
            attempt.error = "timeout sending request";
            return attempt;
        }

        int code = conn.getResponseCode();
        String response = readBody(conn, code);
        if (code == 429) {
            attempt.rateLimited = true;
            attempt.retryAfterSeconds = parseRetryAfter(conn.getHeaderField("Retry-After"));
            attempt.error = "HTTP 429 rate-limited"
                    + (attempt.retryAfterSeconds > 0 ? " (retry after " + attempt.retryAfterSeconds + "s)" : "")
                    + snippet(response);
            return attempt;
        }
        if (code == 401) {
            attempt.error = "HTTP 401 key rejected" + snippet(response);
            return attempt;
        }
        if (code == 404) {
            attempt.error = "HTTP 404 model or endpoint not found" + snippet(response);
            return attempt;
        }
        if (code < 200 || code >= 300) {
            attempt.error = "HTTP " + code + snippet(response);
            return attempt;
        }
        String content = extractContent(response);
        if (content == null || content.trim().isEmpty()) {
            attempt.error = "HTTP 200 but empty reply" + snippet(response);
            return attempt;
        }
        attempt.ok = true;
        attempt.text = content.trim();
        return attempt;
    }

    /** Parses a Retry-After header in seconds; HTTP-date forms return 0 (not waited on). */
    private static int parseRetryAfter(String header) {
        if (header == null) return 0;
        try {
            int v = Integer.parseInt(header.trim());
            return v < 0 ? 0 : v;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Parses choices[0].message.content, handling both string and parts-array shapes. */
    private static String extractContent(String response) {
        try {
            JSONObject root = new JSONObject(response);
            JSONArray choices = root.optJSONArray("choices");
            if (choices == null || choices.length() == 0) return null;
            JSONObject message = choices.optJSONObject(0).optJSONObject("message");
            if (message == null) return null;
            Object content = message.opt("content");
            if (content instanceof String) return (String) content;
            if (content instanceof JSONArray) {
                StringBuilder sb = new StringBuilder();
                JSONArray parts = (JSONArray) content;
                for (int i = 0; i < parts.length(); i++) {
                    JSONObject part = parts.optJSONObject(i);
                    if (part != null) {
                        String text = part.optString("text", "");
                        if (!text.isEmpty()) {
                            if (sb.length() > 0) sb.append("\n");
                            sb.append(text);
                        }
                    }
                }
                return sb.toString();
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Fetches OpenRouter's public model list and returns the current free ids. */
    public static List<String> openRouterFreeModels(String keyOrNull) throws Exception {
        URL url = new URL("https://openrouter.ai/api/v1/models");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Accept", "application/json");
        if (keyOrNull != null && !keyOrNull.trim().isEmpty()) {
            conn.setRequestProperty("Authorization", "Bearer " + keyOrNull.trim());
        }
        int code = conn.getResponseCode();
        String response = readBody(conn, code);
        if (code < 200 || code >= 300) {
            throw new Exception("HTTP " + code + snippet(response));
        }
        List<String> out = new ArrayList<>();
        JSONObject root = new JSONObject(response);
        JSONArray data = root.optJSONArray("data");
        if (data != null) {
            for (int i = 0; i < data.length(); i++) {
                JSONObject m = data.optJSONObject(i);
                if (m == null) continue;
                String id = m.optString("id", "");
                JSONObject pricing = m.optJSONObject("pricing");
                boolean zero = false;
                if (pricing != null) {
                    zero = "0".equals(pricing.optString("prompt", ""))
                            && "0".equals(pricing.optString("completion", ""));
                }
                if (zero && (id.endsWith(":free") || "openrouter/free".equals(id))) {
                    long ctx = m.optLong("context_length", 0);
                    out.add(id + (ctx > 0 ? "  (" + ctx + " ctx)" : ""));
                }
            }
        }
        return out;
    }

    private static String readBody(HttpURLConnection conn, int code) {
        try {
            InputStream in = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            if (in == null) return "";
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int n;
            int total = 0;
            while ((n = in.read(chunk)) != -1) {
                // Cap what we keep: error bodies can be huge.
                if (total < 65536) buf.write(chunk, 0, n);
                total += n;
            }
            in.close();
            return new String(buf.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    /** Short, single-line error detail. Never includes headers or keys. */
    private static String snippet(String response) {
        if (response == null || response.isEmpty()) return "";
        // Prefer the provider's own error message when present.
        try {
            JSONObject root = new JSONObject(response);
            JSONObject err = root.optJSONObject("error");
            if (err != null) {
                String msg = err.optString("message", "");
                if (!msg.isEmpty()) return " - " + oneLine(msg, 200);
            }
        } catch (Exception ignored) {}
        return " - " + oneLine(response, 200);
    }

    private static String oneLine(String s, int max) {
        String t = s.replace('\n', ' ').replace('\r', ' ').trim();
        if (t.length() > max) t = t.substring(0, max) + "...";
        return t;
    }

    private static String shortError(Exception e) {
        if (e instanceof SocketTimeoutException) return "timeout (no reply in 90s)";
        if (e instanceof UnknownHostException) return "cannot reach host (no network or bad base URL?)";
        if (e instanceof java.net.MalformedURLException) return "bad base URL";
        String msg = e.getMessage();
        if (msg == null || msg.isEmpty()) return e.getClass().getSimpleName();
        return oneLine(msg, 160);
    }
}

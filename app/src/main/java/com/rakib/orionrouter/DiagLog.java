package com.rakib.orionrouter;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * In-memory ring buffer of recent attempt-log lines, shared by the chat
 * screen (which records them) and the settings screen (which shows them).
 * Never contains API keys: Router log lines carry only provider/model
 * names, HTTP codes, and provider error messages.
 */
public class DiagLog {

    private static final int MAX_LINES = 200;
    private static final List<String> LINES = new ArrayList<>();

    public static synchronized void add(String line) {
        String time = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        LINES.add(time + "  " + line);
        while (LINES.size() > MAX_LINES) LINES.remove(0);
    }

    public static synchronized List<String> all() {
        return new ArrayList<>(LINES);
    }

    public static synchronized void clear() {
        LINES.clear();
    }
}

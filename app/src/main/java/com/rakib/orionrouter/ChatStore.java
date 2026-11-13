package com.rakib.orionrouter;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Stores multiple chats (like a chatbot app's chat list) in
 * SharedPreferences as JSON. Each chat has an id, a title taken from
 * its first message, an update time, and its messages. History per chat
 * is capped so storage stays small; the per-request cap lives in the
 * chat screen and only trims what is sent.
 *
 * v1.1 kept a single conversation in the settings prefs; the first v2.0
 * launch imports it as one chat so nothing the user said is lost.
 */
public class ChatStore {

    private static final String PREFS = "orion_chats_v1";
    private static final String KEY_CHATS = "chats";
    private static final String KEY_CURRENT = "current_id";
    private static final String KEY_MIGRATED = "migrated_v1";
    private static final int MAX_CHATS = 30;
    private static final int MAX_MESSAGES = 60;
    private static final int MAX_STORED_CHARS = 20000;

    public static class Chat {
        public String id;
        public String title;
        public long updatedAt;
        public final List<Router.Message> messages = new ArrayList<>();
    }

    public static synchronized List<Chat> loadAll(Context ctx) {
        List<Chat> out = new ArrayList<>();
        String raw = prefs(ctx).getString(KEY_CHATS, "");
        if (raw == null || raw.isEmpty()) return out;
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                Chat c = new Chat();
                c.id = o.optString("id", "");
                c.title = o.optString("title", "Chat");
                c.updatedAt = o.optLong("updated", 0);
                if (c.id.isEmpty()) continue;
                JSONArray msgs = o.optJSONArray("messages");
                if (msgs != null) {
                    for (int j = 0; j < msgs.length(); j++) {
                        JSONObject m = msgs.optJSONObject(j);
                        if (m == null) continue;
                        String role = m.optString("role", "");
                        String content = m.optString("content", "");
                        if (("user".equals(role) || "assistant".equals(role)) && !content.isEmpty()) {
                            String by = m.optString("by", null);
                            if (by != null && by.isEmpty()) by = null;
                            c.messages.add(new Router.Message(role, content, by));
                        }
                    }
                }
                out.add(c);
            }
        } catch (Exception ignored) {
            // Corrupt storage reads as empty; saving overwrites it.
            out.clear();
        }
        sortByUpdated(out);
        return out;
    }

    public static synchronized void saveAll(Context ctx, List<Chat> chats) {
        sortByUpdated(chats);
        while (chats.size() > MAX_CHATS) chats.remove(chats.size() - 1);
        try {
            JSONArray arr = new JSONArray();
            for (Chat c : chats) {
                capChat(c);
                JSONObject o = new JSONObject();
                o.put("id", c.id);
                o.put("title", c.title);
                o.put("updated", c.updatedAt);
                JSONArray msgs = new JSONArray();
                for (Router.Message m : c.messages) {
                    JSONObject mo = new JSONObject();
                    mo.put("role", m.role);
                    mo.put("content", m.content);
                    if (m.by != null) mo.put("by", m.by);
                    msgs.put(mo);
                }
                o.put("messages", msgs);
                arr.put(o);
            }
            prefs(ctx).edit().putString(KEY_CHATS, arr.toString()).apply();
        } catch (Exception ignored) {}
    }

    /** Saves one chat (insert or replace) and stamps its update time. */
    public static synchronized void saveChat(Context ctx, Chat chat) {
        chat.updatedAt = System.currentTimeMillis();
        List<Chat> all = loadAll(ctx);
        boolean replaced = false;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).id.equals(chat.id)) {
                all.set(i, chat);
                replaced = true;
                break;
            }
        }
        if (!replaced) all.add(0, chat);
        saveAll(ctx, all);
    }

    public static synchronized void deleteChat(Context ctx, String id) {
        List<Chat> all = loadAll(ctx);
        for (int i = all.size() - 1; i >= 0; i--) {
            if (all.get(i).id.equals(id)) all.remove(i);
        }
        saveAll(ctx, all);
        if (id.equals(getCurrentId(ctx))) setCurrentId(ctx, "");
    }

    public static synchronized Chat find(Context ctx, String id) {
        if (id == null || id.isEmpty()) return null;
        for (Chat c : loadAll(ctx)) {
            if (c.id.equals(id)) return c;
        }
        return null;
    }

    public static synchronized Chat createNew(Context ctx) {
        Chat c = new Chat();
        c.id = "c" + System.currentTimeMillis() + "-" + (int) (Math.random() * 1000);
        c.title = "New chat";
        c.updatedAt = System.currentTimeMillis();
        setCurrentId(ctx, c.id);
        return c;
    }

    /** Returns the current chat, creating one (unsaved until first message) if needed. */
    public static synchronized Chat getOrCreateCurrent(Context ctx) {
        migrateFromV1(ctx);
        Chat current = find(ctx, getCurrentId(ctx));
        if (current != null) return current;
        List<Chat> all = loadAll(ctx);
        if (!all.isEmpty()) {
            setCurrentId(ctx, all.get(0).id);
            return all.get(0);
        }
        return createNew(ctx);
    }

    public static String getCurrentId(Context ctx) {
        return prefs(ctx).getString(KEY_CURRENT, "");
    }

    public static void setCurrentId(Context ctx, String id) {
        prefs(ctx).edit().putString(KEY_CURRENT, id == null ? "" : id).apply();
    }

    /** Title from the first user message: one line, at most 40 chars. */
    public static String makeTitle(String firstUserContent) {
        String t = firstUserContent.replace('\n', ' ').replace('\r', ' ').trim();
        if (t.isEmpty()) return "New chat";
        if (t.length() > 40) t = t.substring(0, 40).trim() + "…";
        return t;
    }

    private static void capChat(Chat c) {
        while (c.messages.size() > MAX_MESSAGES) c.messages.remove(0);
        int total = 0;
        for (Router.Message m : c.messages) total += m.content.length();
        while (c.messages.size() > 2 && total > MAX_STORED_CHARS) {
            total -= c.messages.remove(0).content.length();
            if (!c.messages.isEmpty()) total -= c.messages.remove(0).content.length();
        }
    }

    private static void sortByUpdated(List<Chat> chats) {
        Collections.sort(chats, new Comparator<Chat>() {
            @Override public int compare(Chat a, Chat b) {
                return Long.compare(b.updatedAt, a.updatedAt);
            }
        });
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** One-time import of the v1.1 single conversation, if it had anything in it. */
    private static void migrateFromV1(Context ctx) {
        SharedPreferences p = prefs(ctx);
        if (p.getBoolean(KEY_MIGRATED, false)) return;
        p.edit().putBoolean(KEY_MIGRATED, true).apply();
        String raw = ctx.getSharedPreferences(Config.SETTINGS, Context.MODE_PRIVATE)
                .getString("chat_history_v1", "");
        if (raw == null || raw.isEmpty()) return;
        try {
            JSONArray arr = new JSONArray(raw);
            if (arr.length() == 0) return;
            Chat c = new Chat();
            c.id = "c" + System.currentTimeMillis() + "-migrated";
            c.title = "New chat";
            c.updatedAt = System.currentTimeMillis();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String role = o.optString("role", "");
                String content = o.optString("content", "");
                if (("user".equals(role) || "assistant".equals(role)) && !content.isEmpty()) {
                    if ("user".equals(role) && c.messages.isEmpty()) c.title = makeTitle(content);
                    c.messages.add(new Router.Message(role, content));
                }
            }
            if (!c.messages.isEmpty()) {
                saveChat(ctx, c);
                setCurrentId(ctx, c.id);
            }
        } catch (Exception ignored) {}
    }
}

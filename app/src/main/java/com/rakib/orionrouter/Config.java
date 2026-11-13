package com.rakib.orionrouter;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Shared configuration: the provider chain, chat settings, and daily
 * attempt counters. Used by both the chat screen and the settings
 * screen so the chain definition lives in exactly one place.
 *
 * There are NO fixed provider slots (v2.2): the provider list is
 * stored as JSON and the user can add, rename, reorder, and delete
 * providers in Settings. The seven providers below are only the
 * starting set for a fresh install. Chain order is list order.
 *
 * Migration: v1.0–v2.1 kept one fixed field per provider id in the
 * settings/vault prefs. The first v2.2 load converts those fields
 * (including any keys and customised models/base URLs the user saved)
 * into the stored list, keeping the same ids, so saved keys survive
 * the upgrade. Preference names and the KeyVault alias are unchanged
 * from v1.0 on purpose: renaming them would forget saved keys.
 */
public class Config {

    public static final String SETTINGS = "orion_router_settings";
    public static final String VAULT = "orion_router_vault";
    public static final String COUNTS = "orion_router_counts";
    private static final String KEY_PROVIDERS_JSON = "providers_v2_json";
    public static final int DEFAULT_MAX_TOKENS = 800;
    public static final double DEFAULT_TEMPERATURE = 0.7;

    /** Starting set only — see the class comment. */
    private static class Seed {
        final String id, name, baseUrl, models;
        final boolean enabledByDefault;

        Seed(String id, String name, String baseUrl, String models, boolean enabledByDefault) {
            this.id = id; this.name = name; this.baseUrl = baseUrl;
            this.models = models; this.enabledByDefault = enabledByDefault;
        }
    }

    private static final Seed[] SEEDS = {
            new Seed("groq", "Groq — fast, 1,000/day per model",
                    "https://api.groq.com/openai/v1",
                    "openai/gpt-oss-120b, openai/gpt-oss-20b", true),
            new Seed("gemini", "Google Gemini — AI Studio key",
                    "https://generativelanguage.googleapis.com/v1beta/openai",
                    "gemini-2.5-flash, gemini-2.5-flash-lite", true),
            new Seed("openrouter", "OpenRouter — free models only (:free)",
                    "https://openrouter.ai/api/v1",
                    "nvidia/nemotron-3-ultra-550b-a55b:free, openrouter/free, google/gemma-4-31b-it:free", true),
            new Seed("sambanova", "SambaNova — 20/day per model",
                    "https://api.sambanova.ai/v1",
                    "gpt-oss-120b, Meta-Llama-3.3-70B-Instruct", false),
            new Seed("cloudflare", "Cloudflare Workers AI — 10,000 neurons/day",
                    "https://api.cloudflare.com/client/v4/accounts/PASTE_ACCOUNT_ID/ai/v1",
                    "@cf/openai/gpt-oss-120b", false),
            new Seed("agentrouter", "AgentRouter — free credits, burns on premium models",
                    "https://agentrouter.org/v1",
                    "deepseek-v4-flash, glm-5.3, gpt-5.6-sol", true),
            new Seed("custom", "Custom / Gwarden — last resort, can be very slow",
                    "https://gwarden.su/v1",
                    "deepseek-v4.1-flash-1017", false),
    };

    public static class ProviderConfig {
        public String id = "";
        public String name = "";
        public boolean enabled = true;
        public String key = "";
        public String models = "";
        public String baseUrl = "";
    }

    public static class LoadResult {
        public final List<ProviderConfig> configs = new ArrayList<>();
        /** True when stored keys exist but the Keystore cannot read them. */
        public boolean vaultBroken = false;
    }

    public static class ChatSettings {
        public String system = "";
        public double temperature = DEFAULT_TEMPERATURE;
        public int maxTokens = DEFAULT_MAX_TOKENS;
    }

    /** Fresh id for a provider the user adds. Callers ensure uniqueness in the list. */
    public static String newProviderId() {
        return "p" + System.currentTimeMillis() + "-" + (int) (Math.random() * 1000);
    }

    public static LoadResult loadProviders(Context ctx) {
        LoadResult result = new LoadResult();
        SharedPreferences settings = ctx.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE);
        SharedPreferences vault = ctx.getSharedPreferences(VAULT, Context.MODE_PRIVATE);
        List<ProviderConfig> list = readList(settings);
        if (list == null) {
            // First v2.2 load: migrate the old fixed fields, then persist the list.
            list = migrateFromFixedFields(settings);
            writeList(settings, list);
        }
        for (ProviderConfig c : list) {
            String enc = vault.getString(c.id + "_key", "");
            if (enc != null && !enc.isEmpty()) {
                try {
                    c.key = KeyVault.decrypt(enc);
                } catch (KeyVault.VaultResetException e) {
                    result.vaultBroken = true;
                    c.key = "";
                }
            }
            result.configs.add(c);
        }
        return result;
    }

    /** Reads the stored list, or null when it has never been written. */
    private static List<ProviderConfig> readList(SharedPreferences settings) {
        String raw = settings.getString(KEY_PROVIDERS_JSON, null);
        if (raw == null) return null;
        List<ProviderConfig> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(raw);
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                ProviderConfig c = new ProviderConfig();
                c.id = o.optString("id", "");
                c.name = o.optString("name", "");
                c.enabled = o.optBoolean("enabled", true);
                c.models = o.optString("models", "");
                c.baseUrl = o.optString("base", "");
                if (c.id.isEmpty() || !seen.add(c.id)) continue;
                out.add(c);
            }
        } catch (Exception ignored) {
            out.clear();
        }
        return out;
    }

    private static void writeList(SharedPreferences settings, List<ProviderConfig> configs) {
        try {
            JSONArray arr = new JSONArray();
            for (ProviderConfig c : configs) {
                JSONObject o = new JSONObject();
                o.put("id", c.id);
                o.put("name", c.name == null ? "" : c.name);
                o.put("enabled", c.enabled);
                o.put("models", c.models == null ? "" : c.models);
                o.put("base", c.baseUrl == null ? "" : c.baseUrl);
                arr.put(o);
            }
            settings.edit().putString(KEY_PROVIDERS_JSON, arr.toString()).apply();
        } catch (Exception ignored) {}
    }

    /**
     * Builds the starting list from the old fixed per-id fields,
     * preserving anything the user saved under v1.0–v2.1.
     */
    private static List<ProviderConfig> migrateFromFixedFields(SharedPreferences settings) {
        List<ProviderConfig> out = new ArrayList<>();
        for (Seed seed : SEEDS) {
            ProviderConfig c = new ProviderConfig();
            c.id = seed.id;
            c.name = seed.name;
            c.enabled = settings.getBoolean(seed.id + "_enabled", seed.enabledByDefault);
            c.models = settings.getString(seed.id + "_models", seed.models);
            c.baseUrl = settings.getString(seed.id + "_base", seed.baseUrl);
            out.add(c);
        }
        return out;
    }

    /**
     * Saves the provider list (keys encrypted). Throws before writing
     * anything if a key cannot be encrypted; never saves plaintext.
     * Vault entries for removed providers are deleted with the list.
     */
    public static void saveProviders(Context ctx, List<ProviderConfig> configs) throws Exception {
        SharedPreferences settings = ctx.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE);
        SharedPreferences vault = ctx.getSharedPreferences(VAULT, Context.MODE_PRIVATE);
        // Encrypt everything first so a failure leaves old values untouched.
        List<String> encrypted = new ArrayList<>();
        for (ProviderConfig c : configs) {
            String key = c.key == null ? "" : c.key.trim();
            encrypted.add(key.isEmpty() ? null : KeyVault.encrypt(key));
        }
        Set<String> keepIds = new HashSet<>();
        for (ProviderConfig c : configs) keepIds.add(c.id);
        SharedPreferences.Editor ve = vault.edit();
        for (Map.Entry<String, ?> entry : vault.getAll().entrySet()) {
            String k = entry.getKey();
            if (k.endsWith("_key") && !keepIds.contains(k.substring(0, k.length() - 4))) {
                ve.remove(k);
            }
        }
        for (int i = 0; i < configs.size(); i++) {
            if (encrypted.get(i) == null) {
                ve.remove(configs.get(i).id + "_key");
            } else {
                ve.putString(configs.get(i).id + "_key", encrypted.get(i));
            }
        }
        ve.apply();
        // Trim stored values on the way in so the list stays clean.
        for (ProviderConfig c : configs) {
            c.name = c.name == null ? "" : c.name.trim();
            c.models = c.models == null ? "" : c.models.trim();
            c.baseUrl = c.baseUrl == null ? "" : c.baseUrl.trim();
        }
        writeList(settings, configs);
    }

    public static ChatSettings getChatSettings(Context ctx) {
        SharedPreferences settings = ctx.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE);
        ChatSettings s = new ChatSettings();
        s.system = settings.getString("system_prompt", "");
        s.maxTokens = settings.getInt("max_tokens", DEFAULT_MAX_TOKENS);
        try {
            s.temperature = Double.parseDouble(
                    settings.getString("temperature", String.valueOf(DEFAULT_TEMPERATURE)));
            if (Double.isNaN(s.temperature) || Double.isInfinite(s.temperature)) {
                s.temperature = DEFAULT_TEMPERATURE;
            }
        } catch (Exception e) {
            s.temperature = DEFAULT_TEMPERATURE;
        }
        if (s.temperature < 0) s.temperature = 0;
        if (s.temperature > 2) s.temperature = 2;
        if (s.maxTokens < 50) s.maxTokens = 50;
        if (s.maxTokens > 4000) s.maxTokens = 4000;
        return s;
    }

    public static void saveChatSettings(Context ctx, ChatSettings s) {
        ctx.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE).edit()
                .putString("system_prompt", s.system == null ? "" : s.system.trim())
                .putString("temperature", String.valueOf(s.temperature))
                .putInt("max_tokens", s.maxTokens)
                .apply();
    }

    /** Clears stored keys after a vault failure; the user re-enters them in Settings. */
    public static void clearVault(Context ctx) {
        KeyVault.reset();
        ctx.getSharedPreferences(VAULT, Context.MODE_PRIVATE).edit().clear().apply();
    }

    /** Display name for a provider: its name, or a fallback when blank. */
    public static String displayName(ProviderConfig c) {
        String name = c.name == null ? "" : c.name.trim();
        return name.isEmpty() ? "Provider" : name;
    }

    /** Short name for counters: the part before " — ", or the full name. */
    public static String shortDisplayName(ProviderConfig c) {
        String name = displayName(c);
        int dash = name.indexOf(" — ");
        if (dash > 0) name = name.substring(0, dash);
        return name;
    }

    public static List<Router.Endpoint> buildChain(List<ProviderConfig> configs, boolean firstModelOnly) {
        List<Router.Endpoint> chain = new ArrayList<>();
        for (ProviderConfig c : configs) {
            if (!c.enabled) continue;
            String key = c.key == null ? "" : c.key.trim();
            if (key.isEmpty()) continue;
            String base = c.baseUrl == null ? "" : c.baseUrl.trim();
            if (base.isEmpty()) continue;
            if (base.contains("PASTE_ACCOUNT_ID")) continue;
            String[] models = (c.models == null ? "" : c.models).split(",");
            for (String m : models) {
                String model = m.trim();
                if (model.isEmpty()) continue;
                chain.add(new Router.Endpoint(c.id, displayName(c), base, key, model));
                if (firstModelOnly) break;
            }
        }
        return chain;
    }

    // ---------- Daily counters (keyed by stable provider id) ----------

    public static void recordOutcomes(Context ctx, Router.Result result) {
        SharedPreferences prefs = ctx.getSharedPreferences(COUNTS, Context.MODE_PRIVATE);
        ensureCountDay(prefs);
        SharedPreferences.Editor e = prefs.edit();
        for (String[] outcome : result.outcomes) {
            String field = outcome[0] + "_" + outcome[1];
            e.putInt(field, prefs.getInt(field, 0) + 1);
        }
        e.apply();
    }

    public static String countersText(Context ctx, List<ProviderConfig> configs) {
        SharedPreferences prefs = ctx.getSharedPreferences(COUNTS, Context.MODE_PRIVATE);
        ensureCountDay(prefs);
        StringBuilder sb = new StringBuilder("Today's attempts in this app: ");
        boolean any = false;
        for (ProviderConfig c : configs) {
            int ok = prefs.getInt(c.id + "_ok", 0);
            int limit = prefs.getInt(c.id + "_limit", 0);
            int fail = prefs.getInt(c.id + "_fail", 0);
            if (ok + limit + fail > 0) {
                if (any) sb.append("  |  ");
                sb.append(shortDisplayName(c)).append(": ").append(ok).append(" ok, ")
                        .append(limit).append(" limited, ").append(fail).append(" failed");
                any = true;
            }
        }
        if (!any) sb.append("none yet.");
        return sb.toString();
    }

    private static void ensureCountDay(SharedPreferences prefs) {
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        if (!today.equals(prefs.getString("date", ""))) {
            prefs.edit().clear().putString("date", today).apply();
        }
    }
}

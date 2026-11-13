package com.rakib.orionrouter;

import android.content.Context;
import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Shared configuration: provider chain, chat settings, and daily attempt
 * counters. Used by both the chat screen and the settings screen so the
 * chain definition lives in exactly one place.
 *
 * Preference names and the KeyVault alias are intentionally unchanged
 * from v1.0/v1.1 (they still say "orion_router"): renaming them would
 * make an upgrade forget the keys the user already saved. The powerful
 * default chain is unchanged in v2.0: Groq first (biggest free cap),
 * then Gemini, then OpenRouter's free models, then SambaNova,
 * Cloudflare, and the custom slot.
 */
public class Config {

    public static final String SETTINGS = "orion_router_settings";
    public static final String VAULT = "orion_router_vault";
    public static final String COUNTS = "orion_router_counts";
    public static final int DEFAULT_MAX_TOKENS = 800;
    public static final double DEFAULT_TEMPERATURE = 0.7;

    public static class ProviderDef {
        public final String id, label, baseUrl, models;
        public final boolean enabledByDefault;

        ProviderDef(String id, String label, String baseUrl, String models, boolean enabledByDefault) {
            this.id = id; this.label = label; this.baseUrl = baseUrl;
            this.models = models; this.enabledByDefault = enabledByDefault;
        }
    }

    // Chain order = array order. Disable the top providers to force a
    // harder task onto OpenRouter first.
    public static final ProviderDef[] PROVIDERS = {
            new ProviderDef("groq", "1. Groq — fast, 1,000/day per model",
                    "https://api.groq.com/openai/v1",
                    "openai/gpt-oss-120b, openai/gpt-oss-20b", true),
            new ProviderDef("gemini", "2. Google Gemini — AI Studio key",
                    "https://generativelanguage.googleapis.com/v1beta/openai",
                    "gemini-2.5-flash, gemini-2.5-flash-lite", true),
            new ProviderDef("openrouter", "3. OpenRouter — free models only (:free)",
                    "https://openrouter.ai/api/v1",
                    "nvidia/nemotron-3-ultra-550b-a55b:free, openrouter/free, google/gemma-4-31b-it:free", true),
            new ProviderDef("sambanova", "4. SambaNova — 20/day per model",
                    "https://api.sambanova.ai/v1",
                    "gpt-oss-120b, Meta-Llama-3.3-70B-Instruct", false),
            new ProviderDef("cloudflare", "5. Cloudflare Workers AI — 10,000 neurons/day",
                    "https://api.cloudflare.com/client/v4/accounts/PASTE_ACCOUNT_ID/ai/v1",
                    "@cf/openai/gpt-oss-120b", false),
            new ProviderDef("agentrouter", "6. AgentRouter — free credits, burns on premium models",
                    "https://agentrouter.org/v1",
                    "deepseek-v4-flash, glm-5.3, gpt-5.6-sol", true),
            new ProviderDef("custom", "7. Custom / Gwarden — last resort, can be very slow",
                    "https://gwarden.su/v1",
                    "deepseek-v4.1-flash-1017", false),
    };

    public static class ProviderConfig {
        public ProviderDef def;
        public boolean enabled;
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

    public static LoadResult loadProviders(Context ctx) {
        LoadResult result = new LoadResult();
        SharedPreferences settings = ctx.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE);
        SharedPreferences vault = ctx.getSharedPreferences(VAULT, Context.MODE_PRIVATE);
        for (ProviderDef def : PROVIDERS) {
            ProviderConfig c = new ProviderConfig();
            c.def = def;
            c.enabled = settings.getBoolean(def.id + "_enabled", def.enabledByDefault);
            c.models = settings.getString(def.id + "_models", def.models);
            c.baseUrl = settings.getString(def.id + "_base", def.baseUrl);
            String enc = vault.getString(def.id + "_key", "");
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

    /**
     * Saves provider configs (keys encrypted). Throws before writing
     * anything if a key cannot be encrypted; never saves plaintext.
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
        SharedPreferences.Editor se = settings.edit();
        SharedPreferences.Editor ve = vault.edit();
        for (int i = 0; i < configs.size(); i++) {
            ProviderConfig c = configs.get(i);
            se.putBoolean(c.def.id + "_enabled", c.enabled);
            se.putString(c.def.id + "_models", c.models == null ? "" : c.models.trim());
            se.putString(c.def.id + "_base", c.baseUrl == null ? "" : c.baseUrl.trim());
            if (encrypted.get(i) == null) {
                ve.remove(c.def.id + "_key");
            } else {
                ve.putString(c.def.id + "_key", encrypted.get(i));
            }
        }
        se.apply();
        ve.apply();
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
                chain.add(new Router.Endpoint(c.def.label, base, key, model));
                if (firstModelOnly) break;
            }
        }
        return chain;
    }

    public static String shortName(String id) {
        if ("groq".equals(id)) return "Groq";
        if ("gemini".equals(id)) return "Gemini";
        if ("openrouter".equals(id)) return "OpenRouter";
        if ("sambanova".equals(id)) return "SambaNova";
        if ("cloudflare".equals(id)) return "Cloudflare";
        if ("agentrouter".equals(id)) return "AgentRouter";
        return "Custom";
    }

    public static String providerIdForLabel(String label) {
        for (ProviderDef def : PROVIDERS) {
            if (label.equals(def.label)) return def.id;
        }
        return "unknown";
    }

    // ---------- Daily counters ----------

    public static void recordOutcomes(Context ctx, Router.Result result) {
        SharedPreferences prefs = ctx.getSharedPreferences(COUNTS, Context.MODE_PRIVATE);
        ensureCountDay(prefs);
        SharedPreferences.Editor e = prefs.edit();
        for (String[] outcome : result.outcomes) {
            String field = providerIdForLabel(outcome[0]) + "_" + outcome[1];
            e.putInt(field, prefs.getInt(field, 0) + 1);
        }
        e.apply();
    }

    public static String countersText(Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences(COUNTS, Context.MODE_PRIVATE);
        ensureCountDay(prefs);
        StringBuilder sb = new StringBuilder("Today's attempts in this app: ");
        boolean any = false;
        for (ProviderDef def : PROVIDERS) {
            int ok = prefs.getInt(def.id + "_ok", 0);
            int limit = prefs.getInt(def.id + "_limit", 0);
            int fail = prefs.getInt(def.id + "_fail", 0);
            if (ok + limit + fail > 0) {
                if (any) sb.append("  |  ");
                sb.append(shortName(def.id)).append(": ").append(ok).append(" ok, ")
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

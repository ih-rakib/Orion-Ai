package com.rakib.orionrouter;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.text.InputType;
import android.text.method.ScrollingMovementMethod;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Settings screen: the provider chain (add, rename, reorder, delete —
 * there are no fixed slots), chat settings (system prompt, creativity,
 * max tokens), provider health checks, the live OpenRouter free-model
 * list, today's counters, and the attempt log (shared with the chat
 * screen via DiagLog).
 *
 * Leaving this screen saves: Back (top button or system back) writes
 * everything first. Keys are encrypted with Android Keystore before
 * storage and are never shown unless "Show keys" is on.
 */
public class SettingsActivity extends Activity {

    private static class ProviderFields {
        EditText name;
        CheckBox enabled;
        EditText key;
        EditText models;
        EditText base;
    }

    private List<Config.ProviderConfig> workingList = new ArrayList<>();
    private final List<ProviderFields> fields = new ArrayList<>();
    private LinearLayout providerContainer;
    private CheckBox showKeysBox;
    private EditText systemEdit;
    private EditText tempEdit;
    private EditText maxTokensEdit;
    private TextView countersText;
    private TextView logText;
    private TextView statusText;
    private Button saveBtn;
    private Button testBtn;
    private Button listBtn;
    private Button addBtn;
    private boolean running = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        loadAll();
        refreshLogView();
        countersText.setText(Config.countersText(this, workingList));
    }

    @Override
    public void onBackPressed() {
        if (saveAll()) {
            super.onBackPressed();
        }
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dp(12), dp(8), dp(12), dp(8));
        Button backBtn = new Button(this);
        backBtn.setText("← Back");
        backBtn.setMinimumWidth(0);
        backBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { onBackPressed(); }
        });
        TextView title = new TextView(this);
        title.setText("Settings");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setPadding(dp(12), 0, 0, 0);
        topBar.addView(backBtn);
        topBar.addView(title);
        root.addView(topBar);

        ScrollView scroll = new ScrollView(this);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(16), dp(8), dp(16), dp(16));
        scroll.addView(body);
        root.addView(scroll);

        TextView intro = new TextView(this);
        intro.setText("Providers are tried top to bottom. There are no fixed slots: "
                + "tap ＋ Add provider whenever you get a new key, use ↑ ↓ to change "
                + "the order, and delete providers you no longer use. "
                + "Keys are encrypted on this phone and never leave it except to their own provider.");
        intro.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        body.addView(intro);
        body.addView(spacer(dp(8)));

        showKeysBox = new CheckBox(this);
        showKeysBox.setText("Show keys (keep OFF for screenshots)");
        showKeysBox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                applyShowKeys(isChecked);
            }
        });
        body.addView(showKeysBox);
        body.addView(spacer(dp(4)));

        providerContainer = new LinearLayout(this);
        providerContainer.setOrientation(LinearLayout.VERTICAL);
        body.addView(providerContainer);

        addBtn = new Button(this);
        addBtn.setText("＋ Add provider");
        addBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { addProvider(); }
        });
        body.addView(addBtn);
        body.addView(spacer(dp(14)));

        body.addView(heading("Chat settings"));
        systemEdit = new EditText(this);
        systemEdit.setHint("Instructions for every model (optional)");
        systemEdit.setMinLines(2);
        systemEdit.setMaxLines(4);
        systemEdit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        body.addView(systemEdit);
        body.addView(spacer(dp(6)));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        TextView tempLabel = new TextView(this);
        tempLabel.setText("Creativity (0–2): ");
        row.addView(tempLabel);
        tempEdit = new EditText(this);
        tempEdit.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        tempEdit.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(tempEdit);
        TextView maxLabel = new TextView(this);
        maxLabel.setText("  Max tokens: ");
        row.addView(maxLabel);
        maxTokensEdit = new EditText(this);
        maxTokensEdit.setInputType(InputType.TYPE_CLASS_NUMBER);
        maxTokensEdit.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(maxTokensEdit);
        body.addView(row);
        body.addView(spacer(dp(10)));

        saveBtn = new Button(this);
        saveBtn.setText("Save settings");
        saveBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (saveAll()) Toast.makeText(SettingsActivity.this, "Saved.", Toast.LENGTH_SHORT).show();
            }
        });
        body.addView(saveBtn);
        body.addView(spacer(dp(10)));

        testBtn = new Button(this);
        testBtn.setText("Test all providers");
        testBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { runTestAll(); }
        });
        body.addView(testBtn);
        body.addView(spacer(dp(6)));

        listBtn = new Button(this);
        listBtn.setText("Show current OpenRouter free models");
        listBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { runOpenRouterList(); }
        });
        body.addView(listBtn);
        body.addView(spacer(dp(10)));

        statusText = new TextView(this);
        statusText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        body.addView(statusText);
        body.addView(spacer(dp(6)));

        countersText = new TextView(this);
        countersText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        body.addView(countersText);
        body.addView(spacer(dp(10)));

        body.addView(heading("Attempt log"));
        Button clearLogBtn = new Button(this);
        clearLogBtn.setText("Clear log");
        clearLogBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                DiagLog.clear();
                refreshLogView();
            }
        });
        body.addView(clearLogBtn);
        logText = new TextView(this);
        logText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        logText.setTextIsSelectable(true);
        logText.setMovementMethod(new ScrollingMovementMethod());
        logText.setMinHeight(dp(180));
        body.addView(logText);
        body.addView(spacer(dp(10)));

        TextView footer = new TextView(this);
        footer.setText("Keys live only in this app's encrypted storage and are sent only to "
                + "their own provider. Free-tier prompts may be used for training by some "
                + "providers — avoid passwords, private factory data, or anything sensitive.\n\n"
                + "Any OpenAI-compatible gateway works: add it with ＋ Add provider, paste its "
                + "base URL (ending in /v1), key, and model names.\n\n"
                + "Get keys: Groq console.groq.com • Gemini aistudio.google.com/apikey • "
                + "OpenRouter openrouter.ai/keys • SambaNova cloud.sambanova.ai/apis • "
                + "Cloudflare dash (Workers AI) • AgentRouter agentrouter.org/console/token • "
                + "Gwarden gwarden.su");
        footer.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        body.addView(footer);

        setContentView(root);
    }

    private TextView heading(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        return t;
    }

    private View spacer(int px) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, px));
        return v;
    }

    // ---------- Provider list UI ----------

    private void renderProviders() {
        providerContainer.removeAllViews();
        fields.clear();
        for (int i = 0; i < workingList.size(); i++) {
            final Config.ProviderConfig c = workingList.get(i);
            final int position = i;
            final ProviderFields f = new ProviderFields();

            LinearLayout header = new LinearLayout(this);
            header.setOrientation(LinearLayout.HORIZONTAL);
            header.setGravity(Gravity.CENTER_VERTICAL);
            TextView headerText = new TextView(this);
            headerText.setText((i + 1) + ". " + Config.displayName(c));
            headerText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            headerText.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            headerText.setLayoutParams(new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            Button upBtn = smallButton("↑", new View.OnClickListener() {
                @Override public void onClick(View v) { moveProvider(position, -1); }
            });
            upBtn.setEnabled(position > 0);
            Button downBtn = smallButton("↓", new View.OnClickListener() {
                @Override public void onClick(View v) { moveProvider(position, 1); }
            });
            downBtn.setEnabled(position < workingList.size() - 1);
            Button delBtn = smallButton("Delete", new View.OnClickListener() {
                @Override public void onClick(View v) { confirmDelete(c.id); }
            });
            header.addView(headerText);
            header.addView(upBtn);
            header.addView(downBtn);
            header.addView(delBtn);
            providerContainer.addView(header);

            f.name = new EditText(this);
            f.name.setHint("Provider name, e.g. My Gateway");
            f.name.setText(c.name);
            f.name.setSingleLine(true);
            providerContainer.addView(f.name);

            f.enabled = new CheckBox(this);
            f.enabled.setText("Enabled (tried in the chain)");
            f.enabled.setChecked(c.enabled);
            providerContainer.addView(f.enabled);

            f.key = new EditText(this);
            f.key.setHint("API key");
            f.key.setText(c.key);
            f.key.setSingleLine(true);
            providerContainer.addView(f.key);

            f.models = new EditText(this);
            f.models.setHint("Models, comma-separated");
            f.models.setText(c.models);
            f.models.setSingleLine(true);
            providerContainer.addView(f.models);

            f.base = new EditText(this);
            f.base.setHint("Base URL, ending in /v1");
            f.base.setText(c.baseUrl);
            f.base.setSingleLine(true);
            providerContainer.addView(f.base);

            providerContainer.addView(spacer(dp(12)));
            fields.add(f);
        }
        applyShowKeys(showKeysBox.isChecked());
        if (workingList.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("No providers yet. Tap ＋ Add provider below to add your first key.");
            empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            providerContainer.addView(empty);
            providerContainer.addView(spacer(dp(12)));
        }
    }

    private Button smallButton(String text, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        b.setMinimumWidth(0);
        b.setPadding(dp(10), dp(4), dp(10), dp(4));
        b.setOnClickListener(listener);
        return b;
    }

    private void applyShowKeys(boolean show) {
        int type = show
                ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD;
        for (ProviderFields f : fields) {
            int pos = f.key.getSelectionStart();
            f.key.setInputType(type);
            f.key.setSelection(Math.max(0, Math.min(pos, f.key.getText().length())));
        }
    }

    /** Copies the current on-screen values back into the working list. */
    private void readUiIntoWorkingList() {
        if (fields.size() != workingList.size()) return;
        for (int i = 0; i < fields.size(); i++) {
            ProviderFields f = fields.get(i);
            Config.ProviderConfig c = workingList.get(i);
            c.name = f.name.getText().toString().trim();
            c.enabled = f.enabled.isChecked();
            c.key = f.key.getText().toString().trim();
            c.models = f.models.getText().toString().trim();
            c.baseUrl = f.base.getText().toString().trim();
        }
    }

    private void addProvider() {
        readUiIntoWorkingList();
        Config.ProviderConfig c = new Config.ProviderConfig();
        c.id = uniqueId();
        c.name = "";
        c.enabled = true;
        c.key = "";
        c.models = "";
        c.baseUrl = "";
        workingList.add(c);
        renderProviders();
        if (!fields.isEmpty()) fields.get(fields.size() - 1).name.requestFocus();
        Toast.makeText(this, "Fill in the new provider, then Save.", Toast.LENGTH_SHORT).show();
    }

    private void moveProvider(int position, int delta) {
        readUiIntoWorkingList();
        int target = position + delta;
        if (position < 0 || position >= workingList.size()
                || target < 0 || target >= workingList.size()) return;
        Config.ProviderConfig c = workingList.remove(position);
        workingList.add(target, c);
        renderProviders();
    }

    private void confirmDelete(final String id) {
        readUiIntoWorkingList();
        String name = "this provider";
        for (Config.ProviderConfig c : workingList) {
            if (c.id.equals(id)) name = Config.displayName(c);
        }
        final String label = name;
        new AlertDialog.Builder(this)
                .setTitle("Delete this provider?")
                .setMessage("\"" + label + "\" and its saved key will be removed when you Save. "
                        + "This cannot be undone.")
                .setPositiveButton("Delete", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        for (int i = workingList.size() - 1; i >= 0; i--) {
                            if (workingList.get(i).id.equals(id)) workingList.remove(i);
                        }
                        renderProviders();
                        Toast.makeText(SettingsActivity.this,
                                "Deleted. Save to keep this change.", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private String uniqueId() {
        Set<String> ids = new HashSet<>();
        for (Config.ProviderConfig c : workingList) ids.add(c.id);
        String id = Config.newProviderId();
        while (ids.contains(id)) id = Config.newProviderId();
        return id;
    }

    private void loadAll() {
        Config.LoadResult loaded = Config.loadProviders(this);
        if (loaded.vaultBroken) {
            Config.clearVault(this);
            Toast.makeText(this,
                    "Stored keys could not be read (device security changed). Paste them again.",
                    Toast.LENGTH_LONG).show();
        }
        workingList = new ArrayList<>(loaded.configs);
        renderProviders();
        Config.ChatSettings s = Config.getChatSettings(this);
        systemEdit.setText(s.system);
        tempEdit.setText(String.valueOf(s.temperature));
        maxTokensEdit.setText(String.valueOf(s.maxTokens));
    }

    /** Parses chat settings from the UI, clamping and warning on bad input. */
    private Config.ChatSettings readUiChatSettings() {
        Config.ChatSettings s = new Config.ChatSettings();
        s.system = systemEdit.getText().toString().trim();
        try {
            s.temperature = Double.parseDouble(tempEdit.getText().toString().trim());
            if (Double.isNaN(s.temperature) || Double.isInfinite(s.temperature)) throw new NumberFormatException();
        } catch (Exception e) {
            s.temperature = Config.DEFAULT_TEMPERATURE;
            Toast.makeText(this, "Creativity not understood — using 0.7.", Toast.LENGTH_SHORT).show();
        }
        if (s.temperature < 0) s.temperature = 0;
        if (s.temperature > 2) s.temperature = 2;
        try {
            s.maxTokens = Integer.parseInt(maxTokensEdit.getText().toString().trim());
        } catch (Exception e) {
            s.maxTokens = Config.DEFAULT_MAX_TOKENS;
            Toast.makeText(this, "Max tokens not understood — using 800.", Toast.LENGTH_SHORT).show();
        }
        if (s.maxTokens < 50) s.maxTokens = 50;
        if (s.maxTokens > 4000) s.maxTokens = 4000;
        return s;
    }

    private boolean saveAll() {
        readUiIntoWorkingList();
        // Blank names get a position-based name; duplicate ids are regenerated.
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < workingList.size(); i++) {
            Config.ProviderConfig c = workingList.get(i);
            if (c.name == null || c.name.trim().isEmpty()) {
                c.name = "Provider " + (i + 1);
            }
            if (c.id == null || c.id.isEmpty() || !seen.add(c.id)) {
                c.id = uniqueId();
                seen.add(c.id);
            }
        }
        Config.ChatSettings s = readUiChatSettings();
        Config.saveChatSettings(this, s);
        tempEdit.setText(String.valueOf(s.temperature));
        maxTokensEdit.setText(String.valueOf(s.maxTokens));
        try {
            Config.saveProviders(this, workingList);
        } catch (Exception e) {
            Toast.makeText(this, "Could not encrypt keys — nothing was saved. Try again.",
                    Toast.LENGTH_LONG).show();
            return false;
        }
        renderProviders();
        countersText.setText(Config.countersText(this, workingList));
        return true;
    }

    private void setRunning(boolean value) {
        running = value;
        saveBtn.setEnabled(!value);
        testBtn.setEnabled(!value);
        listBtn.setEnabled(!value);
        addBtn.setEnabled(!value);
    }

    private Router.Logger uiLogger() {
        return new Router.Logger() {
            @Override public void log(final String line) {
                DiagLog.add(line);
                runOnUiThread(new Runnable() {
                    @Override public void run() { appendLog(line); }
                });
            }
            @Override public void starting(final int index, final int total, final String label) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        statusText.setText("Trying " + index + "/" + total + ": " + label);
                    }
                });
            }
        };
    }

    private void appendLog(String line) {
        String existing = logText.getText().toString();
        String next = existing.isEmpty() ? line : existing + "\n" + line;
        logText.setText(next);
    }

    private void refreshLogView() {
        StringBuilder sb = new StringBuilder();
        for (String line : DiagLog.all()) {
            if (sb.length() > 0) sb.append("\n");
            sb.append(line);
        }
        logText.setText(sb.toString());
    }

    private void runTestAll() {
        readUiIntoWorkingList();
        final List<Router.Endpoint> chain = Config.buildChain(workingList, true);
        if (chain.isEmpty()) {
            statusText.setText("Nothing to test: add a provider and paste its key.");
            return;
        }
        setRunning(true);
        statusText.setText("Testing " + chain.size() + " provider(s)...");
        final Router.Logger logger = uiLogger();
        new Thread(new Runnable() {
            @Override public void run() {
                List<Router.Message> test = new ArrayList<>();
                test.add(new Router.Message("user", "Reply with exactly: OK"));
                final Router.Result result = Router.chat(chain, test, 20, 0, logger);
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        Config.recordOutcomes(SettingsActivity.this, result);
                        countersText.setText(Config.countersText(SettingsActivity.this, workingList));
                        statusText.setText(result.ok
                                ? "Test done. First answer came from " + result.answeredBy + "."
                                : "Test done. No provider answered — see the log above.");
                        setRunning(false);
                    }
                });
            }
        }).start();
    }

    private void runOpenRouterList() {
        setRunning(true);
        statusText.setText("Loading OpenRouter's current model list...");
        readUiIntoWorkingList();
        String key = null;
        for (Config.ProviderConfig c : workingList) {
            String base = c.baseUrl == null ? "" : c.baseUrl;
            String name = c.name == null ? "" : c.name.toLowerCase(Locale.US);
            if (base.contains("openrouter.ai") || name.contains("openrouter")) {
                key = c.key;
                break;
            }
        }
        final String openRouterKey = key;
        final Router.Logger logger = uiLogger();
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    final List<String> free = Router.openRouterFreeModels(openRouterKey);
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            if (free.isEmpty()) {
                                logger.log("OpenRouter free list: none found right now.");
                            } else {
                                logger.log("OpenRouter free models (" + free.size() + "):");
                                for (String id : free) logger.log("  " + id);
                                logger.log("Paste any of these into the OpenRouter models box.");
                            }
                            statusText.setText("Free-model list loaded: " + free.size() + " model(s).");
                            setRunning(false);
                        }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            logger.log("OpenRouter list failed: " + e.getMessage());
                            statusText.setText("Could not load the free-model list. See the log.");
                            setRunning(false);
                        }
                    });
                }
            }
        }).start();
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics()));
    }
}

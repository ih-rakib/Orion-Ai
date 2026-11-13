package com.rakib.orionrouter;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.ContextMenu;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * Chat screen (v2.0): message bubbles like a chatbot app, a bottom
 * input bar, and a chats list behind the Chats button. Keys, models,
 * and the chain live in Settings. The chain itself is unchanged from
 * v1.1: Groq -> Gemini -> OpenRouter free -> SambaNova -> Cloudflare
 * -> Custom, one attempt per model with a short Retry-After retry.
 */
public class MainActivity extends Activity {

    private static final int HISTORY_MAX_CHARS = 12000;
    private static final String CONTINUE_PROMPT =
            "Continue exactly where your last answer stopped. Do not repeat any of it.";

    private ChatStore.Chat currentChat;
    private boolean running = false;

    private TextView subtitleText;
    private TextView statusText;
    private LinearLayout messagesLayout;
    private ScrollView scrollView;
    private EditText promptEdit;
    private Button sendBtn;
    private Button stopBtn;
    private Button continueBtn;
    private Button regenBtn;
    private Button chatsBtn;
    private Button newBtn;
    private Button settingsBtn;
    private View thinkingRow = null;
    private int maxBubblePx;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        maxBubblePx = (int) (getResources().getDisplayMetrics().widthPixels * 0.82);
        buildUi();
        currentChat = ChatStore.getOrCreateCurrent(this);
        renderAll();
        updateButtons();
        statusText.setText("Pick a task below. Add your keys in Settings first.");
    }

    @Override
    protected void onResume() {
        super.onResume();
        String currentId = ChatStore.getCurrentId(this);
        if (currentChat == null || !currentChat.id.equals(currentId)) {
            ChatStore.Chat found = ChatStore.find(this, currentId);
            currentChat = (found != null) ? found : ChatStore.getOrCreateCurrent(this);
            renderAll();
            updateButtons();
        }
    }

    // ---------- UI construction ----------

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);

        // Top bar: title + chat title, Chats / New / Settings.
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dp(12), dp(8), dp(8), dp(8));
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView titleText = new TextView(this);
        titleText.setText("Orion");
        titleText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        titleText.setTypeface(Typeface.DEFAULT_BOLD);
        titleText.setTextColor(Color.parseColor("#1A1A1A"));
        titles.addView(titleText);
        subtitleText = new TextView(this);
        subtitleText.setText("New chat");
        subtitleText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        subtitleText.setTextColor(Color.parseColor("#8A8A8E"));
        subtitleText.setMaxLines(1);
        titles.addView(subtitleText);
        topBar.addView(titles);
        chatsBtn = smallButton("Chats", new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, ChatsActivity.class));
            }
        });
        newBtn = smallButton("New", new View.OnClickListener() {
            @Override public void onClick(View v) { newChat(); }
        });
        settingsBtn = smallButton("Settings", new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, SettingsActivity.class));
            }
        });
        topBar.addView(chatsBtn);
        topBar.addView(newBtn);
        topBar.addView(settingsBtn);
        root.addView(topBar);
        root.addView(divider());

        statusText = new TextView(this);
        statusText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        statusText.setTextColor(Color.parseColor("#666666"));
        statusText.setPadding(dp(12), dp(6), dp(12), dp(2));
        root.addView(statusText);

        scrollView = new ScrollView(this);
        scrollView.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        scrollView.setFillViewport(true);
        messagesLayout = new LinearLayout(this);
        messagesLayout.setOrientation(LinearLayout.VERTICAL);
        messagesLayout.setPadding(dp(12), dp(8), dp(12), dp(8));
        scrollView.addView(messagesLayout);
        root.addView(scrollView);
        root.addView(divider());

        // Action row: Continue / Regenerate.
        LinearLayout actionRow = new LinearLayout(this);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionRow.setPadding(dp(12), dp(6), dp(12), 0);
        continueBtn = new Button(this);
        continueBtn.setText("Continue last answer");
        continueBtn.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        continueBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { runContinue(); }
        });
        regenBtn = new Button(this);
        regenBtn.setText("Regenerate");
        regenBtn.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        regenBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { runRegenerate(); }
        });
        actionRow.addView(continueBtn);
        actionRow.addView(regenBtn);
        root.addView(actionRow);

        // Input row: text field + Send / Stop.
        LinearLayout inputRow = new LinearLayout(this);
        inputRow.setOrientation(LinearLayout.HORIZONTAL);
        inputRow.setGravity(Gravity.BOTTOM);
        inputRow.setPadding(dp(12), dp(6), dp(12), dp(10));
        promptEdit = new EditText(this);
        promptEdit.setHint("Message Orion...");
        promptEdit.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        promptEdit.setBackgroundResource(R.drawable.input_bg);
        promptEdit.setPadding(dp(14), dp(10), dp(14), dp(10));
        promptEdit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        promptEdit.setMinLines(1);
        promptEdit.setMaxLines(5);
        promptEdit.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        sendBtn = new Button(this);
        sendBtn.setText("Send");
        sendBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { runSend(); }
        });
        stopBtn = new Button(this);
        stopBtn.setText("Stop");
        stopBtn.setVisibility(View.GONE);
        stopBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Router.requestCancel();
                statusText.setText("Stopping...");
            }
        });
        inputRow.addView(promptEdit);
        inputRow.addView(sendBtn);
        inputRow.addView(stopBtn);
        root.addView(inputRow);

        setContentView(root);
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

    private View divider() {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        v.setBackgroundColor(Color.parseColor("#E5E5E5"));
        return v;
    }

    // ---------- Rendering ----------

    private void renderAll() {
        messagesLayout.removeAllViews();
        thinkingRow = null;
        subtitleText.setText(currentChat.title == null ? "New chat" : currentChat.title);
        if (currentChat.messages.isEmpty()) {
            TextView hint = new TextView(this);
            hint.setText("Ask anything.\n\nOrion tries Groq, Gemini, OpenRouter free models, "
                    + "and more, in order, until one answers. Add your keys in Settings first.\n\n"
                    + "Long-press any answer to copy it.");
            hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            hint.setTextColor(Color.parseColor("#8A8A8E"));
            hint.setPadding(dp(8), dp(24), dp(8), 0);
            messagesLayout.addView(hint);
        } else {
            for (Router.Message m : currentChat.messages) {
                addBubble(m);
            }
        }
        scrollToBottom();
    }

    /** Adds one message row and returns it (used for the thinking placeholder). */
    private View addBubble(Router.Message m) {
        boolean isUser = "user".equals(m.role);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowParams.setMargins(0, 0, 0, dp(10));
        row.setLayoutParams(rowParams);

        LinearLayout holder = new LinearLayout(this);
        holder.setOrientation(LinearLayout.VERTICAL);
        holder.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView meta = new TextView(this);
        meta.setText(isUser ? "You" : shortBy(m.by));
        meta.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        meta.setTextColor(Color.parseColor("#8A8A8E"));
        meta.setPadding(dp(4), 0, dp(4), dp(2));
        LinearLayout.LayoutParams metaParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        metaParams.gravity = isUser ? Gravity.END : Gravity.START;
        meta.setLayoutParams(metaParams);
        holder.addView(meta);

        final TextView bubble = new TextView(this);
        bubble.setText(m.content);
        bubble.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        bubble.setTextColor(Color.parseColor("#1A1A1A"));
        bubble.setTextIsSelectable(true);
        bubble.setMaxWidth(maxBubblePx);
        bubble.setPadding(dp(12), dp(9), dp(12), dp(9));
        bubble.setBackgroundResource(isUser ? R.drawable.bubble_user : R.drawable.bubble_assistant);
        final String copyText = m.content;
        bubble.setOnCreateContextMenuListener(new View.OnCreateContextMenuListener() {
            @Override public void onCreateContextMenu(ContextMenu menu, View v, ContextMenu.ContextMenuInfo info) {
                menu.add("Copy").setOnMenuItemClickListener(new MenuItem.OnMenuItemClickListener() {
                    @Override public boolean onMenuItemClick(MenuItem item) {
                        copyToClipboard(copyText);
                        return true;
                    }
                });
            }
        });
        holder.addView(bubble);

        View spacer = new View(this);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f));
        if (isUser) {
            row.addView(spacer);
            row.addView(holder);
        } else {
            row.addView(holder);
            row.addView(spacer);
        }
        messagesLayout.addView(row);
        return row;
    }

    private void showThinking() {
        thinkingRow = addBubble(new Router.Message("assistant", "Thinking..."));
        scrollToBottom();
    }

    private void removeThinking() {
        if (thinkingRow != null) {
            messagesLayout.removeView(thinkingRow);
            thinkingRow = null;
        }
    }

    private void scrollToBottom() {
        scrollView.post(new Runnable() {
            @Override public void run() {
                scrollView.fullScroll(View.FOCUS_DOWN);
            }
        });
    }

    /** "1. Groq — fast, 1,000/day per model / openai/gpt-oss-120b" -> "Orion • Groq • openai/gpt-oss-120b". */
    private static String shortBy(String by) {
        if (by == null || by.isEmpty()) return "Orion";
        try {
            int slash = by.indexOf(" / ");
            String providerPart = slash >= 0 ? by.substring(0, slash) : by;
            String model = slash >= 0 ? by.substring(slash + 3) : "";
            int dot = providerPart.indexOf(". ");
            if (dot >= 0) providerPart = providerPart.substring(dot + 2);
            int dash = providerPart.indexOf(" — ");
            if (dash >= 0) providerPart = providerPart.substring(0, dash);
            if (model.isEmpty()) return "Orion • " + providerPart;
            return "Orion • " + providerPart + " • " + model;
        } catch (Exception e) {
            return "Orion";
        }
    }

    // ---------- Running the chain ----------

    private interface DoneHandler {
        void onDone(Router.Result result);
    }

    private void executeChain(final List<Router.Message> messages, final List<Router.Endpoint> chain,
                              final int maxTokens, final double temp, final DoneHandler handler) {
        setRunning(true);
        showThinking();
        final Router.Logger logger = new Router.Logger() {
            @Override public void log(final String line) {
                DiagLog.add(line);
            }
            @Override public void starting(final int index, final int total, final String label) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        statusText.setText("Trying " + index + "/" + total + ": " + shortBy(label));
                    }
                });
            }
        };
        new Thread(new Runnable() {
            @Override public void run() {
                final Router.Result result = Router.chat(chain, messages, maxTokens, temp, logger);
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        removeThinking();
                        Config.recordOutcomes(MainActivity.this, result);
                        setRunning(false);
                        handler.onDone(result);
                        updateButtons();
                    }
                });
            }
        }).start();
    }

    private void runSend() {
        final String prompt = promptEdit.getText().toString().trim();
        if (prompt.isEmpty()) return;
        Config.LoadResult loaded = Config.loadProviders(this);
        if (loaded.vaultBroken) {
            statusText.setText("Stored keys could not be read. Open Settings and paste them again.");
            Toast.makeText(this, "Keys unreadable — re-enter them in Settings.", Toast.LENGTH_LONG).show();
            return;
        }
        final List<Router.Endpoint> chain = Config.buildChain(loaded.configs, false);
        if (chain.isEmpty()) {
            statusText.setText("No provider ready. Add a key in Settings first.");
            Toast.makeText(this, "Add a key in Settings first.", Toast.LENGTH_LONG).show();
            return;
        }
        Config.ChatSettings settings = Config.getChatSettings(this);
        final boolean wasEmpty = currentChat.messages.isEmpty();
        TrimmedMessages built = buildMessages(currentChat.messages, prompt, settings.system);
        if (built.trimmed) {
            Toast.makeText(this, "Older turns were trimmed for this send.", Toast.LENGTH_SHORT).show();
        }
        promptEdit.setText("");
        statusText.setText("Sending...");
        executeChain(built.messages, chain, settings.maxTokens, settings.temperature, new DoneHandler() {
            @Override public void onDone(Router.Result result) {
                if (result.ok) {
                    currentChat.messages.add(new Router.Message("user", prompt));
                    currentChat.messages.add(new Router.Message("assistant", result.text, result.answeredBy));
                    if (wasEmpty) currentChat.title = ChatStore.makeTitle(prompt);
                    ChatStore.saveChat(MainActivity.this, currentChat);
                    renderAll();
                    statusText.setText("Answered by " + shortBy(result.answeredBy) + ".");
                } else if (result.stopped) {
                    statusText.setText("Stopped. Nothing was saved for this send.");
                } else {
                    statusText.setText("No model answered. Open Settings to see the attempt log.");
                }
            }
        });
    }

    private void runContinue() {
        if (running || !endsWithAssistant()) return;
        Config.LoadResult loaded = Config.loadProviders(this);
        if (loaded.vaultBroken) {
            Toast.makeText(this, "Keys unreadable — re-enter them in Settings.", Toast.LENGTH_LONG).show();
            return;
        }
        final List<Router.Endpoint> chain = Config.buildChain(loaded.configs, false);
        if (chain.isEmpty()) {
            Toast.makeText(this, "Add a key in Settings first.", Toast.LENGTH_LONG).show();
            return;
        }
        Config.ChatSettings settings = Config.getChatSettings(this);
        TrimmedMessages built = buildMessages(currentChat.messages, CONTINUE_PROMPT, settings.system);
        final String previous = currentChat.messages.get(currentChat.messages.size() - 1).content;
        statusText.setText("Continuing...");
        executeChain(built.messages, chain, settings.maxTokens, settings.temperature, new DoneHandler() {
            @Override public void onDone(Router.Result result) {
                if (result.ok) {
                    String merged = previous + "\n" + result.text + "\n(continued)";
                    int last = currentChat.messages.size() - 1;
                    currentChat.messages.set(last, new Router.Message("assistant", merged, result.answeredBy));
                    ChatStore.saveChat(MainActivity.this, currentChat);
                    renderAll();
                    statusText.setText("Continued by " + shortBy(result.answeredBy) + ".");
                } else if (result.stopped) {
                    statusText.setText("Stopped. The previous answer is unchanged.");
                } else {
                    statusText.setText("Continue failed — no model answered. See Settings for the log.");
                }
            }
        });
    }

    private void runRegenerate() {
        if (running || !endsWithAssistant() || currentChat.messages.size() < 2) return;
        Config.LoadResult loaded = Config.loadProviders(this);
        if (loaded.vaultBroken) {
            Toast.makeText(this, "Keys unreadable — re-enter them in Settings.", Toast.LENGTH_LONG).show();
            return;
        }
        final List<Router.Endpoint> chain = Config.buildChain(loaded.configs, false);
        if (chain.isEmpty()) {
            Toast.makeText(this, "Add a key in Settings first.", Toast.LENGTH_LONG).show();
            return;
        }
        Config.ChatSettings settings = Config.getChatSettings(this);
        final Router.Message popped = currentChat.messages.remove(currentChat.messages.size() - 1);
        TrimmedMessages built = buildMessages(currentChat.messages, null, settings.system);
        statusText.setText("Regenerating...");
        executeChain(built.messages, chain, settings.maxTokens, settings.temperature, new DoneHandler() {
            @Override public void onDone(Router.Result result) {
                if (result.ok) {
                    currentChat.messages.add(new Router.Message("assistant", result.text, result.answeredBy));
                    ChatStore.saveChat(MainActivity.this, currentChat);
                    renderAll();
                    statusText.setText("Answered by " + shortBy(result.answeredBy) + ".");
                } else {
                    // Restore the previous answer so a failed regenerate loses nothing.
                    currentChat.messages.add(popped);
                    ChatStore.saveChat(MainActivity.this, currentChat);
                    renderAll();
                    statusText.setText(result.stopped
                            ? "Stopped. The previous answer is back."
                            : "Regenerate failed — the previous answer is back. See Settings for the log.");
                }
            }
        });
    }

    private boolean endsWithAssistant() {
        return !currentChat.messages.isEmpty()
                && "assistant".equals(currentChat.messages.get(currentChat.messages.size() - 1).role);
    }

    private void newChat() {
        if (running) return;
        currentChat = ChatStore.createNew(this);
        renderAll();
        updateButtons();
        statusText.setText("New chat. Ask anything.");
    }

    private void setRunning(boolean value) {
        running = value;
        sendBtn.setVisibility(value ? View.GONE : View.VISIBLE);
        stopBtn.setVisibility(value ? View.VISIBLE : View.GONE);
        promptEdit.setEnabled(!value);
        chatsBtn.setEnabled(!value);
        newBtn.setEnabled(!value);
        settingsBtn.setEnabled(!value);
        updateButtons();
    }

    private void updateButtons() {
        boolean canFollowUp = !running && endsWithAssistant();
        continueBtn.setEnabled(canFollowUp);
        regenBtn.setEnabled(canFollowUp && currentChat.messages.size() >= 2);
        sendBtn.setEnabled(!running);
    }

    // ---------- Message building ----------

    private static class TrimmedMessages {
        List<Router.Message> messages;
        boolean trimmed;
    }

    /**
     * Builds the sent copy: system prompt + most recent history + the new
     * user turn (null for regenerate, where history already ends with the
     * user). Oldest turns drop from the sent copy only; stored history
     * is untouched.
     */
    private static TrimmedMessages buildMessages(List<Router.Message> history, String extraUser, String system) {
        TrimmedMessages out = new TrimmedMessages();
        List<Router.Message> recent = new ArrayList<>(history);
        int total = extraUser == null ? 0 : extraUser.length();
        for (Router.Message m : recent) total += m.content.length();
        boolean trimmed = false;
        while (recent.size() > 2 && total > HISTORY_MAX_CHARS) {
            total -= recent.remove(0).content.length();
            if (!recent.isEmpty()) total -= recent.remove(0).content.length();
            trimmed = true;
        }
        List<Router.Message> messages = new ArrayList<>();
        if (system != null && !system.trim().isEmpty()) {
            messages.add(new Router.Message("system", system.trim()));
        }
        messages.addAll(recent);
        if (extraUser != null) messages.add(new Router.Message("user", extraUser));
        out.messages = messages;
        out.trimmed = trimmed;
        return out;
    }

    private void copyToClipboard(String text) {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("Orion answer", text));
            Toast.makeText(this, "Copied.", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Copy failed.", Toast.LENGTH_SHORT).show();
        }
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics()));
    }
}

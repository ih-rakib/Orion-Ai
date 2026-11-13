package com.rakib.orionrouter;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Chat list: tap a chat to open it, long-press to delete it.
 * The current chat is chosen here; the chat screen picks it up
 * when it resumes.
 */
public class ChatsActivity extends Activity {

    private ListView listView;
    private TextView emptyText;
    private List<ChatStore.Chat> chats;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
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
            @Override public void onClick(View v) { finish(); }
        });
        TextView title = new TextView(this);
        title.setText("Chats");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setPadding(dp(12), 0, 0, 0);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        title.setLayoutParams(titleParams);
        Button newBtn = new Button(this);
        newBtn.setText("＋ New chat");
        newBtn.setMinimumWidth(0);
        newBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                ChatStore.createNew(ChatsActivity.this);
                finish();
            }
        });
        topBar.addView(backBtn);
        topBar.addView(title);
        topBar.addView(newBtn);
        root.addView(topBar);

        listView = new ListView(this);
        listView.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        emptyText = new TextView(this);
        emptyText.setText("No chats yet. Start one from the chat screen.");
        emptyText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        emptyText.setPadding(dp(16), dp(24), dp(16), 0);
        root.addView(listView);
        root.addView(emptyText);
        listView.setEmptyView(emptyText);

        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                ChatStore.Chat chat = chats.get(position);
                ChatStore.setCurrentId(ChatsActivity.this, chat.id);
                finish();
            }
        });
        listView.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                final ChatStore.Chat chat = chats.get(position);
                new AlertDialog.Builder(ChatsActivity.this)
                        .setTitle("Delete this chat?")
                        .setMessage("\"" + chat.title + "\" will be deleted. This cannot be undone.")
                        .setPositiveButton("Delete", new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface dialog, int which) {
                                boolean wasCurrent = chat.id.equals(ChatStore.getCurrentId(ChatsActivity.this));
                                ChatStore.deleteChat(ChatsActivity.this, chat.id);
                                if (wasCurrent) ChatStore.createNew(ChatsActivity.this);
                                refresh();
                            }
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
                return true;
            }
        });

        setContentView(root);
    }

    private void refresh() {
        chats = ChatStore.loadAll(this);
        ArrayAdapter<ChatStore.Chat> adapter = new ArrayAdapter<ChatStore.Chat>(
                this, android.R.layout.simple_list_item_2, chats) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                ChatStore.Chat chat = getItem(position);
                TextView line1 = view.findViewById(android.R.id.text1);
                TextView line2 = view.findViewById(android.R.id.text2);
                line1.setText(chat.title);
                String when = new SimpleDateFormat("d MMM, HH:mm", Locale.US)
                        .format(new Date(chat.updatedAt));
                line2.setText(chat.messages.size() + " messages  •  " + when);
                return view;
            }
        };
        listView.setAdapter(adapter);
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics()));
    }
}

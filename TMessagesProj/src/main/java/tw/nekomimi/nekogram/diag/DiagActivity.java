package tw.nekomimi.nekogram.diag;

import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildConfig;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;

/**
 * Diagnostics for "you have reached the limit of 500 groups and channels" (diagnostic build only).
 * Every step writes what it sent and exactly what the server answered.
 */
public class DiagActivity extends BaseFragment {

    /** Plus Messenger and other clients use an older protocol layer than Parvagram. */
    private static final int OLD_LAYER = 198;

    private TextView output;
    private EditText input;
    private ScrollView scroll;
    private final StringBuilder report = new StringBuilder();

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(org.telegram.messenger.R.drawable.ic_ab_back);
        actionBar.setTitle("Parvagram diagnostics");
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        scroll = new ScrollView(context);
        scroll.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        final LinearLayout col = new LinearLayout(context);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(12), AndroidUtilities.dp(16), AndroidUtilities.dp(24));
        scroll.addView(col);

        col.addView(text(context, "1) Environment, 2) all groups and channels from the server, 3) inactive channels, "
                + "4) join test with the group's username or t.me link. Then Copy report and send it.", 14, false));

        col.addView(button(context, "1. Environment", v -> environment()));
        col.addView(button(context, "2. Count my groups & channels (server)", v -> census()));
        col.addView(button(context, "3. Inactive channels (server)", v -> inactive()));

        input = new EditText(context);
        input.setHint("username or https://t.me/... of the group");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        input.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
        col.addView(input, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 8, 0, 0));
        col.addView(button(context, "4. Join test (new layer, then old layer " + OLD_LAYER + ")", v -> joinTest()));
        col.addView(button(context, "5. Show error log", v -> {
            final String log = Diag.dump();
            out("---- error log ----\n" + (TextUtils.isEmpty(log) ? "(empty)" : log));
        }));
        col.addView(button(context, "Copy report", v -> {
            AndroidUtilities.addToClipboard(report.toString() + "\n---- error log ----\n" + Diag.dump());
            out("(report copied to clipboard)");
        }));
        col.addView(button(context, "Share report", v -> {
            final Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("text/plain");
            intent.putExtra(Intent.EXTRA_TEXT, report.toString() + "\n---- error log ----\n" + Diag.dump());
            try {
                getParentActivity().startActivity(Intent.createChooser(intent, "Share report"));
            } catch (Exception ignore) {
            }
        }));

        output = text(context, "", 12, true);
        output.setTextIsSelectable(true);
        col.addView(output, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 12, 0, 0));

        fragmentView = scroll;
        return fragmentView;
    }

    private TextView text(Context context, String s, int size, boolean mono) {
        final TextView t = new TextView(context);
        t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, size);
        t.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        if (mono) {
            t.setTypeface(Typeface.MONOSPACE);
        }
        t.setGravity(Gravity.LEFT);
        t.setText(s);
        return t;
    }

    private TextView button(Context context, String s, View.OnClickListener l) {
        final TextView b = new TextView(context);
        b.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        b.setTextColor(Theme.getColor(Theme.key_featuredStickers_buttonText));
        b.setBackground(Theme.AdaptiveRipple.filledRectByKey(Theme.key_featuredStickers_addButton, 8));
        b.setGravity(Gravity.CENTER);
        b.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(10), AndroidUtilities.dp(12), AndroidUtilities.dp(10));
        b.setText(s);
        b.setOnClickListener(l);
        b.setLayoutParams(LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 8, 0, 0));
        return b;
    }

    private void out(String s) {
        AndroidUtilities.runOnUIThread(() -> {
            final String line = Diag.time() + "  " + s + "\n";
            report.append(line);
            if (output != null) {
                output.append(line);
                scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
            }
        });
    }

    private static String err(TLRPC.TL_error e) {
        return e == null ? "null" : "code=" + e.code + " text=" + e.text;
    }

    // 1 -------------------------------------------------------------------------------------
    private void environment() {
        final MessagesController mc = getMessagesController();
        final TLRPC.User self = getUserConfig().getCurrentUser();
        out("==== Environment ====");
        out("package=" + BuildConfig.APPLICATION_ID + " version=" + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")");
        out("layer=" + TLRPC.LAYER + " api_id=" + BuildVars.APP_ID + " official_api_id=" + BuildConfig.APP_ID);
        out("account=" + currentAccount + " user_id=" + getUserConfig().getClientUserId()
                + " premium(server)=" + (self != null && self.premium) + " premium(app)=" + getUserConfig().isPremium()
                + " localPremium(Nagram)=" + tw.nekomimi.nekogram.NekoConfig.localPremium.Bool());
        out("limits(app config): channelsLimitDefault=" + mc.channelsLimitDefault + " channelsLimitPremium=" + mc.channelsLimitPremium);
        out("dc=" + ConnectionsManager.getInstance(currentAccount).getCurrentDatacenterId()
                + " connectionState=" + ConnectionsManager.getInstance(currentAccount).getConnectionState()
                + " proxy=" + SharedConfig.isProxyEnabled() + (SharedConfig.currentProxy != null ? " type=" + SharedConfig.currentProxy.settings.getType() : ""));
        out("ghostMode=" + tw.nekomimi.nekogram.NekoConfig.isGhostModeActive());
        int channels = 0, groups = 0, basic = 0;
        for (TLRPC.Dialog d : mc.getAllDialogs()) {
            if (DialogObject.isChatDialog(d.id)) {
                final TLRPC.Chat c = mc.getChat(-d.id);
                if (c == null) {
                    continue;
                }
                if (c.megagroup) {
                    groups++;
                } else if (c instanceof TLRPC.TL_channel) {
                    channels++;
                } else {
                    basic++;
                }
            }
        }
        out("chat list loaded in the app: supergroups=" + groups + " channels=" + channels + " basic groups=" + basic
                + " (all folders incl. archive, as far as the list has loaded)");
    }

    // 2 -------------------------------------------------------------------------------------
    private void census() {
        out("==== Server census (messages.getDialogs, main list + archive) ====");
        final LinkedHashMap<Long, TLRPC.Chat> chats = new LinkedHashMap<>();
        final HashMap<Long, Integer> folderOf = new HashMap<>();
        censusPage(0, 0, 0, new TLRPC.TL_inputPeerEmpty(), chats, folderOf, 0);
    }

    private void censusPage(int folder, int offsetDate, int offsetId, TLRPC.InputPeer offsetPeer,
                            LinkedHashMap<Long, TLRPC.Chat> chats, HashMap<Long, Integer> folderOf, int page) {
        final TLRPC.TL_messages_getDialogs req = new TLRPC.TL_messages_getDialogs();
        req.flags |= TLObject.FLAG_1;
        req.folder_id = folder;
        req.offset_date = offsetDate;
        req.offset_id = offsetId;
        req.offset_peer = offsetPeer;
        req.limit = 100;
        getConnectionsManager().sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (error != null) {
                out("getDialogs folder=" + folder + " page=" + page + " ERROR " + err(error));
                if (error.text != null && error.text.startsWith("FLOOD_WAIT")) {
                    return;
                }
                finishCensus(folder, chats, folderOf);
                return;
            }
            final TLRPC.messages_Dialogs res = (TLRPC.messages_Dialogs) response;
            getMessagesController().putUsers(res.users, false);
            getMessagesController().putChats(res.chats, false);
            for (TLRPC.Chat c : res.chats) {
                if (!chats.containsKey(c.id)) {
                    chats.put(c.id, c);
                    folderOf.put(c.id, folder);
                }
            }
            out("getDialogs folder=" + folder + " page=" + page + ": dialogs=" + res.dialogs.size() + " chats=" + res.chats.size()
                    + (res instanceof TLRPC.TL_messages_dialogsSlice ? " total(server count)=" + res.count : " (complete)"));
            if (res.dialogs.size() < 100 || res instanceof TLRPC.TL_messages_dialogs || page > 60) {
                finishCensus(folder, chats, folderOf);
                return;
            }
            final TLRPC.Dialog last = res.dialogs.get(res.dialogs.size() - 1);
            int date = 0;
            for (TLRPC.Message m : res.messages) {
                if (m.id == last.top_message && DialogObject.getPeerDialogId(m.peer_id) == DialogObject.getPeerDialogId(last.peer)) {
                    date = m.date;
                }
            }
            final TLRPC.InputPeer peer = getMessagesController().getInputPeer(DialogObject.getPeerDialogId(last.peer));
            censusPage(folder, date, last.top_message, peer, chats, folderOf, page + 1);
        }));
    }

    private void finishCensus(int folder, LinkedHashMap<Long, TLRPC.Chat> chats, HashMap<Long, Integer> folderOf) {
        if (folder == 0) {
            censusPage(1, 0, 0, new TLRPC.TL_inputPeerEmpty(), chats, folderOf, 0);
            return;
        }
        int channels = 0, supergroups = 0, basic = 0, left = 0, forbidden = 0;
        final ArrayList<String> lines = new ArrayList<>();
        final MessagesController mc = getMessagesController();
        for (TLRPC.Chat c : chats.values()) {
            String kind;
            if (c instanceof TLRPC.TL_channelForbidden || c instanceof TLRPC.TL_chatForbidden) {
                forbidden++;
                kind = "FORBIDDEN";
            } else if (c.left) {
                left++;
                kind = "left";
            } else if (c.megagroup) {
                supergroups++;
                kind = "supergroup";
            } else if (c instanceof TLRPC.TL_channel) {
                channels++;
                kind = "channel";
            } else {
                basic++;
                kind = "basic group";
            }
            final boolean inLocalList = mc.dialogs_dict.get(-c.id) != null;
            lines.add(kind + " | " + (folderOf.get(c.id) != null && folderOf.get(c.id) == 1 ? "ARCHIVE" : "main") + " | id=" + c.id
                    + " | @" + (c.username == null ? "-" : c.username) + " | " + c.title + (inLocalList ? "" : " | NOT IN APP LIST"));
        }
        out("RESULT: channels=" + channels + " supergroups=" + supergroups + " basic groups=" + basic
                + " (counted toward the limit: " + (channels + supergroups) + ")  left=" + left + " forbidden=" + forbidden
                + " total chats seen=" + chats.size());
        for (String l : lines) {
            out("  " + l);
        }
    }

    // 3 -------------------------------------------------------------------------------------
    private void inactive() {
        out("==== channels.getInactiveChannels ====");
        getConnectionsManager().sendRequest(new TLRPC.TL_channels_getInactiveChannels(), (response, error) -> {
            if (error != null) {
                out("ERROR " + err(error));
                return;
            }
            final TLRPC.TL_messages_inactiveChats res = (TLRPC.TL_messages_inactiveChats) response;
            out("inactive chats returned=" + res.chats.size());
            for (int i = 0; i < res.chats.size(); i++) {
                final TLRPC.Chat c = res.chats.get(i);
                final int date = i < res.dates.size() ? res.dates.get(i) : 0;
                out("  id=" + c.id + " | " + c.title + " | @" + (c.username == null ? "-" : c.username)
                        + " | left=" + c.left + " | last activity=" + (date == 0 ? "?" : new java.util.Date(date * 1000L)));
            }
        });
    }

    // 4 -------------------------------------------------------------------------------------
    private void joinTest() {
        String u = input.getText().toString().trim();
        u = u.replaceFirst("^https?://", "").replaceFirst("^(t\\.me|telegram\\.me)/", "").replaceFirst("^@", "");
        if (u.contains("/")) {
            u = u.substring(0, u.indexOf('/'));
        }
        if (TextUtils.isEmpty(u) || u.startsWith("+") || u.startsWith("joinchat")) {
            out("Enter a public username (private invite links aren't supported by this test).");
            return;
        }
        final String username = u;
        out("==== Join test @" + username + " ====");
        final TLRPC.TL_contacts_resolveUsername req = new TLRPC.TL_contacts_resolveUsername();
        req.username = username;
        getConnectionsManager().sendRequest(req, (response, error) -> {
            if (error != null) {
                out("resolveUsername ERROR " + err(error));
                return;
            }
            final TLRPC.TL_contacts_resolvedPeer res = (TLRPC.TL_contacts_resolvedPeer) response;
            AndroidUtilities.runOnUIThread(() -> {
                getMessagesController().putUsers(res.users, false);
                getMessagesController().putChats(res.chats, false);
                TLRPC.Chat chat = null;
                for (TLRPC.Chat c : res.chats) {
                    if (res.peer instanceof TLRPC.TL_peerChannel && c.id == res.peer.channel_id) {
                        chat = c;
                    }
                }
                if (chat == null) {
                    out("not a channel/supergroup: peer=" + res.peer);
                    return;
                }
                out("resolved: id=" + chat.id + " title=" + chat.title + " megagroup=" + chat.megagroup + " left=" + chat.left
                        + " join_request=" + chat.join_request + " join_to_send=" + chat.join_to_send);
                final TLRPC.TL_channels_joinChannel join = new TLRPC.TL_channels_joinChannel();
                join.channel = MessagesController.getInputChannel(chat);
                final TLRPC.Chat target = chat;
                out("sending channels.joinChannel with layer " + TLRPC.LAYER + " ...");
                getConnectionsManager().sendRequest(join, (r1, e1) -> {
                    if (e1 == null) {
                        out("NEW LAYER: SUCCESS -> " + (r1 == null ? "null" : r1.getClass().getSimpleName()));
                        Diag.log("join test new layer OK @" + username);
                        return;
                    }
                    out("NEW LAYER: ERROR " + err(e1));
                    Diag.log("join test new layer ERROR " + err(e1) + " @" + username);
                    final TLRPC.TL_channels_joinChannel join2 = new TLRPC.TL_channels_joinChannel();
                    join2.channel = MessagesController.getInputChannel(target);
                    out("sending the same request through invokeWithLayer(" + OLD_LAYER + ") ...");
                    getConnectionsManager().sendRequest(new InvokeWithLayer(OLD_LAYER, join2), (r2, e2) -> {
                        if (e2 == null) {
                            out("OLD LAYER " + OLD_LAYER + ": SUCCESS -> " + (r2 == null ? "null" : r2.getClass().getSimpleName())
                                    + "  => the server treats the new layer differently.");
                            Diag.log("join test old layer OK @" + username);
                            if (r2 instanceof TLRPC.Updates) {
                                getMessagesController().processUpdates((TLRPC.Updates) r2, false);
                            }
                        } else {
                            out("OLD LAYER " + OLD_LAYER + ": ERROR " + err(e2) + "  => the account itself is at the limit on the server.");
                            Diag.log("join test old layer ERROR " + err(e2) + " @" + username);
                        }
                    });
                });
            });
        });
    }
}

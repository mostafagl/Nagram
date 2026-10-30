package tw.nekomimi.nekogram.helpers;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.ContactsController;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.HashMap;

/**
 * A log of changes to your contacts' profiles, like Telegraph's "contact changes": new or removed
 * profile photo, phone number, name and username, and when a contact probably blocked or unblocked
 * you (their photo and last seen disappear or come back together). It compares each contact with
 * the last copy kept on this device whenever Telegram updates them, so only changes seen while
 * the app runs are logged.
 */
public final class ContactChanges implements NotificationCenter.NotificationCenterDelegate {

    public static final int TYPE_PHOTO = 1;
    public static final int TYPE_PHOTO_REMOVED = 2;
    public static final int TYPE_PHONE = 4;
    public static final int TYPE_NAME = 8;
    public static final int TYPE_USERNAME = 16;
    public static final int TYPE_BLOCKED = 32;
    public static final int TYPE_UNBLOCKED = 64;
    public static final int TYPES_ALL = 127;
    public static final int[] TYPES = {TYPE_PHOTO, TYPE_PHOTO_REMOVED, TYPE_PHONE, TYPE_NAME, TYPE_USERNAME, TYPE_BLOCKED, TYPE_UNBLOCKED};

    private static final int MAX_EVENTS = 1000;

    public static final class Event {
        public long userId;
        public int type;
        public String oldValue;
        public String newValue;
        public long time;
        public boolean seen;
    }

    private static final class Snapshot {
        long photoId;
        String phone;
        String username;
        String name;
        boolean hidden; // photo and last seen hidden: probably blocked
        boolean statusKnown;

        String encode() {
            return photoId + "\u0001" + nz(phone) + "\u0001" + nz(username) + "\u0001" + nz(name) + "\u0001" + (hidden ? 1 : 0) + "\u0001" + (statusKnown ? 1 : 0);
        }

        static Snapshot decode(String s) {
            final String[] p = s.split("\u0001", -1);
            if (p.length < 6) {
                return null;
            }
            final Snapshot snap = new Snapshot();
            try {
                snap.photoId = Long.parseLong(p[0]);
            } catch (NumberFormatException e) {
                return null;
            }
            snap.phone = p[1];
            snap.username = p[2];
            snap.name = p[3];
            snap.hidden = "1".equals(p[4]);
            snap.statusKnown = "1".equals(p[5]);
            return snap;
        }
    }

    private static final ContactChanges[] instances = new ContactChanges[UserConfig.MAX_ACCOUNT_COUNT];

    private final int account;
    private HashMap<Long, Snapshot> snapshots;
    private ArrayList<Event> events;
    private boolean scanQueued;

    private ContactChanges(int account) {
        this.account = account;
    }

    public static synchronized ContactChanges getInstance(int account) {
        if (instances[account] == null) {
            instances[account] = new ContactChanges(account);
        }
        return instances[account];
    }

    /** Starts watching {@code account}'s contacts (safe to call repeatedly). */
    public static void start(int account) {
        final ContactChanges c = getInstance(account);
        if (c.snapshots != null) {
            return;
        }
        c.load();
        NotificationCenter.getInstance(account).addObserver(c, NotificationCenter.updateInterfaces);
        NotificationCenter.getInstance(account).addObserver(c, NotificationCenter.contactsDidLoad);
        c.queueScan();
    }

    private static SharedPreferences settings() {
        return ApplicationLoader.applicationContext.getSharedPreferences("parva_contact_changes", Context.MODE_PRIVATE);
    }

    private SharedPreferences data() {
        return ApplicationLoader.applicationContext.getSharedPreferences("parva_contact_changes_" + account, Context.MODE_PRIVATE);
    }

    public static boolean isEnabled() {
        return settings().getBoolean("enabled", true);
    }

    public static void setEnabled(boolean enabled) {
        settings().edit().putBoolean("enabled", enabled).apply();
    }

    public static int getTypes() {
        return settings().getInt("types", TYPES_ALL);
    }

    public static void setTypes(int types) {
        settings().edit().putInt("types", types).apply();
    }

    public static boolean unseenFirst() {
        return settings().getBoolean("unseenFirst", true);
    }

    public static void setUnseenFirst(boolean value) {
        settings().edit().putBoolean("unseenFirst", value).apply();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private void load() {
        snapshots = new HashMap<>();
        events = new ArrayList<>();
        final SharedPreferences p = data();
        try {
            final JSONObject snaps = new JSONObject(p.getString("snapshots", "{}"));
            final java.util.Iterator<String> keys = snaps.keys();
            while (keys.hasNext()) {
                final String k = keys.next();
                final Snapshot s = Snapshot.decode(snaps.getString(k));
                if (s != null) {
                    snapshots.put(Long.parseLong(k), s);
                }
            }
            final JSONArray list = new JSONArray(p.getString("events", "[]"));
            for (int i = 0; i < list.length(); i++) {
                final JSONObject o = list.getJSONObject(i);
                final Event e = new Event();
                e.userId = o.optLong("u");
                e.type = o.optInt("t");
                e.oldValue = o.optString("o");
                e.newValue = o.optString("n");
                e.time = o.optLong("d");
                e.seen = o.optBoolean("s");
                events.add(e);
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private void save() {
        try {
            final JSONObject snaps = new JSONObject();
            for (java.util.Map.Entry<Long, Snapshot> e : snapshots.entrySet()) {
                snaps.put(String.valueOf(e.getKey()), e.getValue().encode());
            }
            final JSONArray list = new JSONArray();
            for (Event e : events) {
                list.put(new JSONObject().put("u", e.userId).put("t", e.type).put("o", nz(e.oldValue)).put("n", nz(e.newValue)).put("d", e.time).put("s", e.seen));
            }
            data().edit().putString("snapshots", snaps.toString()).putString("events", list.toString()).apply();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    @Override
    public void didReceivedNotification(int id, int acc, Object... args) {
        queueScan();
    }

    private void queueScan() {
        if (scanQueued) {
            return;
        }
        scanQueued = true;
        AndroidUtilities.runOnUIThread(() -> {
            scanQueued = false;
            scan();
        }, 1500);
    }

    private static Snapshot snapshotOf(TLRPC.User user, Snapshot previous) {
        final Snapshot s = new Snapshot();
        s.photoId = user.photo != null && !(user.photo instanceof TLRPC.TL_userProfilePhotoEmpty) ? user.photo.photo_id : 0;
        s.phone = user.phone;
        s.username = UserObject.getPublicUsername(user);
        s.name = ContactsController.formatName(user.first_name, user.last_name);
        if (user.status != null) {
            s.statusKnown = true;
            s.hidden = user.status instanceof TLRPC.TL_userStatusEmpty && s.photoId == 0;
        } else if (previous != null) {
            s.statusKnown = previous.statusKnown;
            s.hidden = previous.hidden;
        }
        return s;
    }

    private void scan() {
        if (!isEnabled() || snapshots == null) {
            return;
        }
        final MessagesController mc = MessagesController.getInstance(account);
        final ArrayList<TLRPC.TL_contact> contacts = new ArrayList<>(ContactsController.getInstance(account).contacts);
        if (contacts.isEmpty()) {
            return;
        }
        final int types = getTypes();
        boolean changed = false;
        final long now = System.currentTimeMillis();
        for (TLRPC.TL_contact contact : contacts) {
            final TLRPC.User user = mc.getUser(contact.user_id);
            if (user == null || user.min || UserObject.isDeleted(user) || user.self) {
                continue;
            }
            final Snapshot old = snapshots.get(user.id);
            final Snapshot now_ = snapshotOf(user, old);
            if (old == null) {
                snapshots.put(user.id, now_);
                changed = true;
                continue;
            }
            if (old.encode().equals(now_.encode())) {
                continue;
            }
            changed = true;
            if (old.statusKnown && now_.statusKnown && old.hidden != now_.hidden) {
                add(user.id, now_.hidden ? TYPE_BLOCKED : TYPE_UNBLOCKED, null, null, now, types);
            } else if (old.photoId != now_.photoId) {
                if (now_.photoId == 0) {
                    add(user.id, TYPE_PHOTO_REMOVED, null, null, now, types);
                } else {
                    add(user.id, TYPE_PHOTO, null, null, now, types);
                }
            }
            if (!TextUtils.isEmpty(now_.phone) && !TextUtils.equals(old.phone, now_.phone)) {
                add(user.id, TYPE_PHONE, old.phone, now_.phone, now, types);
            }
            if (!TextUtils.isEmpty(now_.name) && !TextUtils.equals(old.name, now_.name)) {
                add(user.id, TYPE_NAME, old.name, now_.name, now, types);
            }
            if (!TextUtils.equals(old.username, now_.username)) {
                add(user.id, TYPE_USERNAME, old.username, now_.username, now, types);
            }
            // Phone and name aren't sent while hidden: keep the last known ones.
            if (TextUtils.isEmpty(now_.phone)) {
                now_.phone = old.phone;
            }
            if (TextUtils.isEmpty(now_.name)) {
                now_.name = old.name;
            }
            snapshots.put(user.id, now_);
        }
        if (changed) {
            while (events.size() > MAX_EVENTS) {
                events.remove(events.size() - 1);
            }
            save();
        }
    }

    private void add(long userId, int type, String oldValue, String newValue, long time, int types) {
        if ((types & type) == 0) {
            return;
        }
        final Event e = new Event();
        e.userId = userId;
        e.type = type;
        e.oldValue = oldValue;
        e.newValue = newValue;
        e.time = time;
        events.add(0, e);
    }

    /** The logged changes, newest first (unseen first when that option is on). */
    public ArrayList<Event> getEvents() {
        if (events == null) {
            load();
        }
        final ArrayList<Event> list = new ArrayList<>(events);
        if (unseenFirst()) {
            final ArrayList<Event> unseen = new ArrayList<>();
            final ArrayList<Event> seen = new ArrayList<>();
            for (Event e : list) {
                (e.seen ? seen : unseen).add(e);
            }
            list.clear();
            list.addAll(unseen);
            list.addAll(seen);
        }
        return list;
    }

    public int getUnseenCount() {
        if (events == null) {
            load();
        }
        int count = 0;
        for (Event e : events) {
            if (!e.seen) {
                count++;
            }
        }
        return count;
    }

    public void markAllSeen() {
        if (events == null) {
            return;
        }
        boolean changed = false;
        for (Event e : events) {
            if (!e.seen) {
                e.seen = true;
                changed = true;
            }
        }
        if (changed) {
            save();
        }
    }

    public void remove(Event e) {
        if (events != null && events.remove(e)) {
            save();
        }
    }

    public void clear() {
        if (events != null) {
            events.clear();
            save();
        }
    }
}

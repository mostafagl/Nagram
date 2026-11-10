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
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.Utilities;
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
    public static final int TYPE_BIO = 128;
    public static final int TYPES_ALL = 255;
    public static final int[] TYPES = {TYPE_PHOTO, TYPE_PHOTO_REMOVED, TYPE_PHONE, TYPE_NAME, TYPE_USERNAME, TYPE_BLOCKED, TYPE_UNBLOCKED, TYPE_BIO};

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
        String bio;

        String encode() {
            try {
                return new JSONObject().put("p", photoId).put("t", nz(phone)).put("u", nz(username))
                        .put("n", nz(name)).put("h", hidden).put("s", statusKnown).put("b", bio).toString();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        static Snapshot decode(String s) {
            if (s.startsWith("{")) {
                try {
                    JSONObject o = new JSONObject(s);
                    Snapshot snap = new Snapshot();
                    snap.photoId = o.optLong("p");
                    snap.phone = o.optString("t");
                    snap.username = o.optString("u");
                    snap.name = o.optString("n");
                    snap.hidden = o.optBoolean("h");
                    snap.statusKnown = o.optBoolean("s");
                    snap.bio = o.has("b") ? o.optString("b") : null;
                    return snap;
                } catch (Exception e) {
                    return null;
                }
            }
            // Migrate the original six-field snapshots on the next save.
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
    private final java.util.ArrayDeque<TLRPC.User> photoQueue = new java.util.ArrayDeque<>();
    private final java.util.HashSet<Long> queuedPhotos = new java.util.HashSet<>();
    private TLRPC.User photoUser;
    private String photoName;
    private java.io.File photoSource;
    private Runnable photoTimeout;
    private boolean started;
    private boolean photoBusy;

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
        if (c.started) {
            return;
        }
        c.started = true;
        if (c.snapshots == null) c.load();
        NotificationCenter.getInstance(account).addObserver(c, NotificationCenter.updateInterfaces);
        NotificationCenter.getInstance(account).addObserver(c, NotificationCenter.contactsDidLoad);
        NotificationCenter.getInstance(account).addObserver(c, NotificationCenter.userInfoDidLoad);
        NotificationCenter.getInstance(account).addObserver(c, NotificationCenter.fileLoaded);
        NotificationCenter.getInstance(account).addObserver(c, NotificationCenter.fileLoadFailed);
        c.queueScan();
    }

    private static SharedPreferences settings() {
        return ApplicationLoader.applicationContext.getSharedPreferences("parva_contact_changes", Context.MODE_PRIVATE);
    }

    private SharedPreferences data() {
        return ApplicationLoader.applicationContext.getSharedPreferences("parva_contact_changes_" + account, Context.MODE_PRIVATE);
    }

    public static boolean isEnabled() {
        return settings().getBoolean("enabled", false);
    }

    public static void setEnabled(boolean enabled) {
        settings().edit().putBoolean("enabled", enabled).apply();
        // Start from a fresh copy each time, so nothing that changed while it was off is reported.
        for (int a = 0; a < instances.length; a++) {
            final ContactChanges c = instances[a];
            if (c != null && c.snapshots != null) {
                c.snapshots.clear();
                c.save();
                if (enabled) {
                    c.queueScan();
                }
            }
        }
    }

    /** Whether only the contacts picked in settings are watched (otherwise all contacts). */
    public static boolean onlySelected() {
        return settings().getBoolean("onlySelected", false);
    }

    public static void setOnlySelected(boolean value) {
        settings().edit().putBoolean("onlySelected", value).apply();
    }

    public static java.util.Set<String> selectedRaw(int account) {
        return new java.util.HashSet<>(settings().getStringSet("selected_" + account, new java.util.HashSet<>()));
    }

    public static ArrayList<Long> getSelected(int account) {
        final ArrayList<Long> list = new ArrayList<>();
        for (String s : selectedRaw(account)) {
            try {
                list.add(Long.parseLong(s));
            } catch (NumberFormatException ignore) {
            }
        }
        return list;
    }

    public static void setSelected(int account, long userId, boolean selected) {
        final java.util.Set<String> set = selectedRaw(account);
        if (selected) {
            set.add(String.valueOf(userId));
        } else {
            set.remove(String.valueOf(userId));
        }
        settings().edit().putStringSet("selected_" + account, set).apply();
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
        if (id == NotificationCenter.fileLoaded || id == NotificationCenter.fileLoadFailed) {
            if (photoUser != null && args.length > 0 && photoName.equals(args[0])) {
                finishPhoto(id == NotificationCenter.fileLoaded);
            }
            return;
        }
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
        s.bio = previous == null ? null : previous.bio;
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
        final java.util.Set<String> only = onlySelected() ? selectedRaw(account) : null;
        boolean changed = false;
        final long now = System.currentTimeMillis();
        for (TLRPC.TL_contact contact : contacts) {
            final TLRPC.User user = mc.getUser(contact.user_id);
            if (user == null || user.min || UserObject.isDeleted(user) || user.self) {
                continue;
            }
            if (only != null && !only.contains(String.valueOf(user.id))) {
                snapshots.remove(user.id);
                continue;
            }
            queuePhoto(user);
            final Snapshot old = snapshots.get(user.id);
            final Snapshot now_ = snapshotOf(user, old);
            TLRPC.UserFull full = mc.getUserFull(user.id);
            if (full != null) now_.bio = nz(full.about);
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
            }
            if (old.photoId != now_.photoId) {
                if (now_.photoId == 0) {
                    add(user.id, TYPE_PHOTO_REMOVED, String.valueOf(old.photoId), "0", now, types);
                } else {
                    add(user.id, TYPE_PHOTO, String.valueOf(old.photoId), String.valueOf(now_.photoId), now, types);
                }
            }
            if (old.bio != null && now_.bio != null && !TextUtils.equals(old.bio, now_.bio)) {
                add(user.id, TYPE_BIO, old.bio, now_.bio, now, types);
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
        nextPhoto();
    }

    /** Visible avatar versions only: never requests hidden or previously unseen deleted photos. */
    private void queuePhoto(TLRPC.User user) {
        if (user.photo == null || user.photo.photo_id == 0 || photoQueue.size() >= 256
                || queuedPhotos.contains(user.id)
                || data().getLong("archived_" + user.id, 0) == user.photo.photo_id
                || System.currentTimeMillis() - data().getLong("photo_attempt_" + user.id, 0) < 600_000) return;
        // Freeze the received version; Telegram may replace user.photo while a download is queued.
        TLRPC.TL_user copy = new TLRPC.TL_user();
        copy.id = user.id;
        copy.access_hash = user.access_hash;
        copy.photo = user.photo;
        queuedPhotos.add(user.id);
        photoQueue.add(copy);
    }

    public java.io.File archiveDirectory() {
        return new java.io.File(ApplicationLoader.applicationContext.getFilesDir(), "contact_photos_" + account);
    }

    public java.io.File[] getArchivedPhotos(long userId) {
        java.io.File[] files = archiveDirectory().listFiles((dir, name) -> name.startsWith(userId + "_") && name.endsWith(".jpg"));
        if (files == null) return new java.io.File[0];
        java.util.Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        return files;
    }

    private void nextPhoto() {
        if (photoBusy) return;
        if (!isEnabled()) {
            photoQueue.clear();
            queuedPhotos.clear();
            return;
        }
        photoUser = photoQueue.poll();
        if (photoUser == null) return;
        photoBusy = true;
        data().edit().putLong("photo_attempt_" + photoUser.id, System.currentTimeMillis()).apply();
        ImageLocation location = ImageLocation.getForUser(account, photoUser, ImageLocation.TYPE_BIG);
        if (location == null) {
            finishPhoto(false);
            return;
        }
        photoName = FileLoader.getAttachFileName(location.location, "jpg");
        photoSource = FileLoader.getInstance(account).getPathToAttach(location.location, "jpg", true);
        if (photoSource.isFile() && photoSource.length() > 0) {
            finishPhoto(true);
        } else {
            photoTimeout = () -> finishPhoto(false);
            AndroidUtilities.runOnUIThread(photoTimeout, 60_000);
            FileLoader.getInstance(account).loadFile(location, photoUser, "jpg", FileLoader.PRIORITY_LOW, 1);
        }
    }

    private void finishPhoto(boolean success) {
        if (photoUser == null) return;
        if (photoTimeout != null) AndroidUtilities.cancelRunOnUIThread(photoTimeout);
        final TLRPC.User user = photoUser;
        final java.io.File source = photoSource;
        photoUser = null;
        photoName = null;
        photoSource = null;
        photoTimeout = null;
        Utilities.globalQueue.postRunnable(() -> {
            boolean copied = false;
            if (success && isEnabled() && (!onlySelected() || selectedRaw(account).contains(String.valueOf(user.id)))) {
                java.io.File dir = archiveDirectory();
                java.io.File target = new java.io.File(dir, user.id + "_" + user.photo.photo_id + ".jpg");
                java.io.File temp = new java.io.File(dir, target.getName() + ".tmp");
                try {
                    if ((dir.isDirectory() || dir.mkdirs()) && source != null && source.length() > 0 && source.length() <= 10 * 1024 * 1024) {
                        if (!target.isFile()) {
                            java.nio.file.Files.copy(source.toPath(), temp.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                            if (!temp.renameTo(target)) throw new java.io.IOException("Avatar archive rename failed");
                        }
                        copied = true;
                        java.io.File[] files = dir.listFiles((d, n) -> n.endsWith(".jpg"));
                        if (files != null) {
                            java.util.Arrays.sort(files, java.util.Comparator.comparingLong(java.io.File::lastModified));
                            long total = 0;
                            for (java.io.File f : files) total += f.length();
                            for (int i = 0; i < files.length && (total > 128L * 1024 * 1024 || files.length - i > 500); i++) {
                                long bytes = files[i].length();
                                if (files[i].delete()) total -= bytes;
                            }
                        }
                    }
                } catch (Exception e) {
                    FileLog.e(e);
                } finally {
                    temp.delete();
                }
            }
            final boolean archived = copied;
            AndroidUtilities.runOnUIThread(() -> {
                if (archived) data().edit().putLong("archived_" + user.id, user.photo.photo_id).apply();
                queuedPhotos.remove(user.id);
                photoBusy = false;
                nextPhoto();
                if (!photoBusy) queueScan(); // Continue a large address book in bounded batches.
            });
        });
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

    /** Timeline for one contact, sorted newest first regardless of the global unseen-first setting. */
    public ArrayList<Event> getEventsForContact(long userId) {
        if (events == null) {
            load();
        }
        final ArrayList<Event> result = new ArrayList<>();
        for (Event event : events) {
            if (event.userId == userId) {
                result.add(event);
            }
        }
        result.sort((left, right) -> Long.compare(right.time, left.time));
        return result;
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

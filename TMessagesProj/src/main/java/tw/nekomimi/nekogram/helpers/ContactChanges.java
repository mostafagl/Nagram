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
        public int accountId;
        public long userId;
        public int type;
        public String oldValue;
        public String newValue;
        public long time;
        public boolean seen;
        public String source = "received_contact_snapshot";
    }

    private static final class Snapshot {
        long photoId;
        String phone;
        String username;
        String name;
        boolean hidden; // photo and last seen hidden: probably blocked
        boolean statusKnown;
        String bio;

        boolean sameAs(Snapshot other) {
            return photoId == other.photoId && hidden == other.hidden && statusKnown == other.statusKnown
                    && TextUtils.equals(nz(phone), nz(other.phone)) && TextUtils.equals(nz(username), nz(other.username))
                    && TextUtils.equals(nz(name), nz(other.name)) && TextUtils.equals(bio, other.bio);
        }

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
    private final java.util.HashSet<String> queuedPhotos = new java.util.HashSet<>();
    private TLRPC.User photoUser;
    private String photoName;
    private java.io.File photoSource;
    private Runnable photoTimeout;
    private boolean started;
    private boolean photoBusy;
    private volatile int archiveGeneration;
    private final HashMap<String, Long> photoObserved = new HashMap<>();

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
        Utilities.globalQueue.postRunnable(c::cleanArchive);
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

    public static boolean isArchiveEnabled() {
        return settings().getBoolean("archive_enabled", true);
    }

    public static void setArchiveEnabled(boolean enabled) {
        settings().edit().putBoolean("archive_enabled", enabled).apply();
        for (ContactChanges c : instances) if (c != null) {
            c.cancelArchiveWork();
            if (enabled) c.queueScan();
        }
    }

    private synchronized void cancelArchiveWork() {
        archiveGeneration++;
        if (photoTimeout != null) AndroidUtilities.cancelRunOnUIThread(photoTimeout);
        photoTimeout = null;
        photoUser = null;
        photoName = null;
        photoSource = null;
        photoBusy = false;
        photoQueue.clear();
        queuedPhotos.clear();
        photoObserved.clear();
    }

    public static int archiveRetentionDays() { return settings().getInt("archive_days", 30); }
    public static int archiveQuotaMb() { return settings().getInt("archive_mb", 128); }

    public static java.util.Set<String> archiveExcluded(int account) {
        return new java.util.HashSet<>(settings().getStringSet("archive_excluded_" + account, new java.util.HashSet<>()));
    }

    public static void setArchiveExcluded(int account, long userId, boolean excluded) {
        java.util.Set<String> set = archiveExcluded(account);
        if (excluded) set.add(String.valueOf(userId)); else set.remove(String.valueOf(userId));
        settings().edit().putStringSet("archive_excluded_" + account, set).apply();
        getInstance(account).queueScan();
    }

    public static void setArchiveLimits(int days, int quotaMb) {
        settings().edit().putInt("archive_days", Math.max(1, Math.min(365, days)))
                .putInt("archive_mb", Math.max(16, Math.min(1024, quotaMb))).apply();
        for (ContactChanges c : instances) if (c != null) Utilities.globalQueue.postRunnable(c::cleanArchive);
    }

    public static void clearAccount(int account) {
        ContactChanges c = getInstance(account);
        c.cancelArchiveWork();
        c.snapshots = new HashMap<>();
        c.events = new ArrayList<>();
        synchronized (c) { c.data().edit().clear().commit(); }
        settings().edit().remove("selected_" + account).remove("archive_excluded_" + account).apply();
        Utilities.globalQueue.postRunnable(() -> {
            java.io.File[] files = c.archiveDirectory().listFiles();
            if (files != null) for (java.io.File f : files) if (isArchiveFile(f)) f.delete();
        });
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
        return settings().getBoolean("unseenFirst", false);
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
                e.accountId = account;
                e.userId = o.optLong("u");
                e.type = o.optInt("t");
                e.oldValue = o.optString("o");
                e.newValue = o.optString("n");
                e.time = o.optLong("d");
                e.seen = o.optBoolean("s");
                e.source = o.optString("p", "received_contact_snapshot");
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
                list.put(new JSONObject().put("a", account).put("u", e.userId).put("t", e.type).put("o", nz(e.oldValue)).put("n", nz(e.newValue)).put("d", e.time).put("s", e.seen).put("p", e.source));
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
        s.phone = nz(user.phone);
        s.username = nz(UserObject.getPublicUsername(user));
        s.name = nz(ContactsController.formatName(user.first_name, user.last_name));
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
        if ((!isEnabled() && !isArchiveEnabled()) || snapshots == null || !UserConfig.getInstance(account).isClientActivated()) {
            return;
        }
        final MessagesController mc = MessagesController.getInstance(account);
        final ArrayList<TLRPC.TL_contact> contacts = new ArrayList<>(ContactsController.getInstance(account).contacts);
        if (contacts.isEmpty()) {
            return;
        }
        final int types = getTypes();
        final java.util.Set<String> only = onlySelected() ? selectedRaw(account) : null;
        final java.util.Set<String> excludedPhotos = archiveExcluded(account);
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
            queuePhoto(user, excludedPhotos);
            final Snapshot old = snapshots.get(user.id);
            final Snapshot now_ = snapshotOf(user, old);
            TLRPC.UserFull full = mc.getUserFull(user.id);
            if (full != null) now_.bio = nz(full.about);
            if (old == null) {
                snapshots.put(user.id, now_);
                changed = true;
                continue;
            }
            if (old.sameAs(now_)) {
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
    private void queuePhoto(TLRPC.User user, java.util.Set<String> excluded) {
        final String version = user.id + "_" + (user.photo == null ? 0 : user.photo.photo_id);
        if (!isArchiveEnabled() || user.photo == null || user.photo.photo_id == 0 || photoQueue.size() >= 256
                || excluded.contains(String.valueOf(user.id))
                || queuedPhotos.contains(version)
                || data().getLong("archived_" + user.id, 0) == user.photo.photo_id
                || (data().getLong("photo_attempt_id_" + user.id, 0) == user.photo.photo_id
                    && System.currentTimeMillis() - data().getLong("photo_attempt_" + user.id, 0) < 600_000)) return;
        // Freeze the received version; Telegram may replace user.photo while a download is queued.
        TLRPC.TL_user copy = new TLRPC.TL_user();
        copy.id = user.id;
        copy.access_hash = user.access_hash;
        copy.photo = user.photo;
        queuedPhotos.add(version);
        photoObserved.put(version, System.currentTimeMillis());
        photoQueue.add(copy);
    }

    public java.io.File archiveDirectory() {
        return new java.io.File(ApplicationLoader.applicationContext.getFilesDir(), "contact_photos_" + account);
    }

    public java.io.File[] getArchivedPhotos(long userId) {
        java.io.File[] files = archiveDirectory().listFiles(file -> isArchiveFile(file)
                && file.getName().endsWith(".jpg") && (userId == 0 || file.getName().startsWith(userId + "_")));
        if (files == null) return new java.io.File[0];
        java.util.Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        return files;
    }

    public void clearArchive(long userId) {
        cancelArchiveWork();
        Utilities.globalQueue.postRunnable(() -> {
            java.io.File[] files = archiveDirectory().listFiles();
            SharedPreferences.Editor editor = data().edit();
            if (files != null) for (java.io.File file : files) {
                if (isArchiveFile(file) && (userId == 0 || file.getName().startsWith(userId + "_")) && file.delete()) {
                    editor.remove("photo_meta_" + file.getName());
                }
            }
            editor.apply();
        });
    }

    private void nextPhoto() {
        if (photoBusy) return;
        if (!isArchiveEnabled() || !UserConfig.getInstance(account).isClientActivated()) {
            photoQueue.clear();
            queuedPhotos.clear();
            return;
        }
        photoUser = photoQueue.poll();
        if (photoUser == null) return;
        photoBusy = true;
        data().edit().putLong("photo_attempt_" + photoUser.id, System.currentTimeMillis())
                .putLong("photo_attempt_id_" + photoUser.id, photoUser.photo.photo_id).apply();
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
        final int generation = archiveGeneration;
        final String version = user.id + "_" + user.photo.photo_id;
        final long observed = photoObserved.containsKey(version) ? photoObserved.get(version) : System.currentTimeMillis();
        photoUser = null;
        photoName = null;
        photoSource = null;
        photoTimeout = null;
        Utilities.globalQueue.postRunnable(() -> {
            boolean copied = false;
            if (success && generation == archiveGeneration && isArchiveEnabled()
                    && UserConfig.getInstance(account).isClientActivated()
                    && !archiveExcluded(account).contains(String.valueOf(user.id))
                    && (!onlySelected() || selectedRaw(account).contains(String.valueOf(user.id)))) {
                java.io.File dir = archiveDirectory();
                java.io.File target = new java.io.File(dir, user.id + "_" + user.photo.photo_id + ".jpg");
                java.io.File temp = new java.io.File(dir, target.getName() + ".tmp");
                try {
                    if ((dir.isDirectory() || dir.mkdirs()) && source != null && source.length() > 0 && source.length() <= 10 * 1024 * 1024) {
                        if (!target.isFile() || target.length() == 0) {
                            // java.nio.file is unavailable on supported Android 23–25 devices.
                            try (java.io.FileInputStream input = new java.io.FileInputStream(source);
                                 java.io.FileOutputStream output = new java.io.FileOutputStream(temp)) {
                                byte[] buffer = new byte[32 * 1024];
                                int length;
                                long copiedBytes = 0;
                                while ((length = input.read(buffer)) != -1) {
                                    copiedBytes += length;
                                    if (copiedBytes > 10L * 1024 * 1024) throw new java.io.IOException("Avatar exceeds archive limit");
                                    output.write(buffer, 0, length);
                                }
                                if (copiedBytes == 0) throw new java.io.IOException("Avatar download is empty");
                            }
                            if (!temp.renameTo(target)) throw new java.io.IOException("Avatar archive rename failed");
                        }
                        copied = true;
                        JSONObject meta = new JSONObject().put("account", account).put("user", user.id)
                                .put("photo", user.photo.photo_id).put("observed", observed)
                                .put("downloaded", target.lastModified()).put("bytes", target.length());
                        synchronized (this) {
                            if (generation == archiveGeneration) {
                                if (!data().contains("photo_meta_" + target.getName()))
                                    data().edit().putString("photo_meta_" + target.getName(), meta.toString()).apply();
                            }
                            else { target.delete(); copied = false; }
                        }
                        cleanArchive();
                    }
                } catch (Exception e) {
                    FileLog.e(e);
                } finally {
                    temp.delete();
                }
            }
            final boolean archived = copied;
            AndroidUtilities.runOnUIThread(() -> {
                if (generation != archiveGeneration) return;
                if (archived) data().edit().putLong("archived_" + user.id, user.photo.photo_id).apply();
                queuedPhotos.remove(version);
                photoObserved.remove(version);
                photoBusy = false;
                nextPhoto();
                if (!photoBusy) queueScan(); // Continue a large address book in bounded batches.
            });
        });
    }

    private static boolean isArchiveFile(java.io.File file) {
        return file.isFile() && file.getName().matches("[0-9]+_-?[0-9]+\\.jpg(\\.tmp)?");
    }

    /** Runs only on globalQueue; never touches FileLoader caches or other app media. */
    private void cleanArchive() {
        java.io.File dir = archiveDirectory();
        java.io.File[] files = dir.listFiles(ContactChanges::isArchiveFile);
        if (files == null) return;
        java.util.Arrays.sort(files, java.util.Comparator.comparingLong(java.io.File::lastModified));
        long total = 0;
        int count = 0;
        long oldest = System.currentTimeMillis() - archiveRetentionDays() * 86_400_000L;
        for (java.io.File f : files) {
            if (f.getName().endsWith(".tmp")) { f.delete(); continue; }
            total += f.length();
            count++;
        }
        SharedPreferences.Editor editor = data().edit();
        for (java.io.File f : files) {
            if (!f.getName().endsWith(".jpg")) continue;
            long bytes = f.length();
            if (f.lastModified() < oldest || total > archiveQuotaMb() * 1024L * 1024 || count > 500) {
                if (f.delete()) {
                    total -= bytes;
                    count--;
                    editor.remove("photo_meta_" + f.getName());
                }
            }
        }
        for (String key : data().getAll().keySet()) {
            if (key.startsWith("photo_meta_") && !new java.io.File(dir, key.substring(11)).isFile()) editor.remove(key);
        }
        editor.apply();
    }

    private void add(long userId, int type, String oldValue, String newValue, long time, int types) {
        if (!isEnabled() || (types & type) == 0) {
            return;
        }
        final Event e = new Event();
        e.accountId = account;
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

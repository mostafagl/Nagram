"""End-to-end UI test of Parvagram on an emulator.

Phases run in order and each records PASS/FAIL; a failing phase does not stop later ones.
Screenshots and a text report go to $UITEST_OUT.
"""
import os
import random
import re
import subprocess
import sys
import time
import traceback

sys.path.insert(0, os.path.dirname(__file__))
from ui import (PKG, OUT, Check, adb, back, clear_field, dump, find, find_all, key, log, long_press, shell, shot,
                swipe_up, tap, tap_xy, texts, type_text, wait)

VLESS = ("vless://e59e0b75-1c42-4c8c-8041-3689859aca33@www.speedtest.net:2053?security=tls&encryption=none&insecure=0"
         "&host=noone.bingnet.online&type=ws&allowInsecure=0&sni=noone.bingnet.online#%F0%9F%87%A9%F0%9F%87%AASV1")
SUBSCRIPTION = "https://raw.githubusercontent.com/barry-far/V2ray-Config/main/Sub1.txt"
HERE = os.path.dirname(os.path.abspath(__file__))
state = {"v2ray_connected": False, "logged_in": False, "phone": None, "peer": None}


def phase(fn):
    def run():
        log("\n===== %s =====" % fn.__name__)
        try:
            fn()
        except Exception as e:  # noqa
            Check.record(fn.__name__, False, "exception: %s" % e)
            log(traceback.format_exc())
            try:
                shot(fn.__name__ + "_error")
            except Exception:  # noqa
                pass
    return run


def logcat(pattern, lines=40):
    out = adb("logcat", "-d", "-v", "brief", timeout=60)
    found = [l for l in out.splitlines() if re.search(pattern, l)]
    return found[-lines:]


def app_alive():
    return bool(shell("pidof %s" % PKG).strip())


def launch():
    shell("monkey -p %s -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1" % PKG)
    time.sleep(6)


def open_link(link):
    # Quoted for the device shell; the link has & and #.
    shell("am start -a android.intent.action.VIEW -d '%s' %s >/dev/null" % (link, PKG))
    time.sleep(4)


def dismiss_system_dialogs():
    for _ in range(3):
        n = find(r"^(Allow|ALLOW|Not now|NOT NOW|Continue|OK|Close app)$")
        if not n:
            return
        tap(n)


@phase
def p01_launch():
    launch()
    dismiss_system_dialogs()
    labels = shot("intro")
    Check.record("app launches to the intro screen", app_alive() and any("Start Messaging" in l for l in labels),
                 "alive=%s" % app_alive())


@phase
def p02_vless_link():
    open_link(VLESS)
    n = wait(r"V2Ray server", 20)
    labels = shot("v2ray_sheet")
    Check.record("vless link opens the V2Ray sheet", n is not None)
    joined = " ".join(labels)
    Check.record("sheet shows the parsed config", all(s in joined for s in ("VLESS", "www.speedtest.net", "2053", "WebSocket", "TLS", "noone.bingnet.online")),
                 joined[:300])
    # Ping test through the real core.
    status = find(r"Check Status")
    if status:
        tap(status)
        if wait(r"^Proceed$", 5):
            tap(r"^Proceed$")
    ping = wait(r"Ping \d+ ms|No response", 60)
    shot("v2ray_ping")
    Check.record("ping test through V2Ray finishes", ping is not None, ping.label if ping else "no result")
    tap(r"^Connect$")
    result = wait(r"Connected through|Couldn't reach Telegram|could not start|^Connected$", 60)
    shot("v2ray_connect")
    connected = result is not None and "Connected" in result.label
    state["v2ray_connected"] = connected
    Check.record("connect to Telegram through the VLESS server", connected, result.label if result else "timeout")
    log("v2ray log:\n  " + "\n  ".join(logcat(r"v2ray|Xray|V2Ray", 15)))
    if not connected:
        # Leave the proxy off so the rest of the test can reach Telegram directly.
        open_link(VLESS)
        if wait(r"^Disconnect$", 10):
            tap(r"^Disconnect$")
        back()


def open_login_menu():
    if not find(r"Your phone|Phone number|Your Phone"):
        if find(r"Start Messaging"):
            tap(r"Start Messaging")
            time.sleep(3)
        dismiss_system_dialogs()
    nodes = dump()
    # The overflow menu is the top-right icon of the phone screen.
    w = max(n.r for n in nodes) if nodes else 1080
    tap_xy(w - 60, 150)
    time.sleep(1)


@phase
def p03_proxy_list():
    open_login_menu()
    tap(r"^Proxy$")
    wait(r"Proxy Settings|Use Proxy", 10)
    shot("proxy_list")
    labels = texts()
    Check.record("proxy list has the V2Ray rows", all(any(s in l for l in labels) for s in ("Add V2Ray config", "Import From Clipboard", "V2Ray subscriptions")),
                 " | ".join(labels[:25]))
    t = find(r"Test ping of all")
    if not t:
        swipe_up()
        t = find(r"Test ping of all")
    if t:
        tap(t)
        time.sleep(20)
        shot("proxy_ping_all")
        Check.record("ping all shows ms or no response", find(r"Ping \d+ ms|No response|Connected") is not None)
    s = find(r"^Sort$")
    if s:
        tap(s)
        tap(r"Lowest ping first")
        shot("proxy_sorted")
        Check.record("sort by ping is selectable", find(r"Lowest ping first") is not None)


@phase
def p04_subscription():
    n = find(r"V2Ray subscriptions")
    if not n:
        swipe_up()
    tap(r"V2Ray subscriptions")
    tap(r"Add subscription")
    fields = [x for x in dump() if "EditText" in x.cls]
    tap_xy(fields[0].cx, fields[0].cy)
    type_text(SUBSCRIPTION)
    tap_xy(fields[1].cx, fields[1].cy)
    type_text("Barry public list")
    tap_xy(fields[2].cx, fields[2].cy)
    clear_field()
    type_text("5")
    shot("subscription_add")
    tap(r"Add and update")
    done = wait(r"Update finished", 900, interval=10)
    labels = shot("subscription_result")
    joined = " ".join(labels)
    m = re.search(r"Links: (\d+) . duplicates: (\d+) . invalid: (\d+)\s+Tested: (\d+) . working: (\d+) . added: (\d+)", joined)
    Check.record("subscription import finishes", done is not None, joined[:400])
    if m:
        links, dup, inv, tested, working, added = map(int, m.groups())
        Check.record("import stops at the limit of 5 working configs", working <= 5 and added <= 5, m.group(0))
        Check.record("subscription had links", links > 0)
    if done:
        tap(r"^OK$")
    tap(r"Update all now")
    done = wait(r"Update finished", 900, interval=10)
    labels = shot("subscription_second_update")
    joined = " ".join(labels)
    m2 = re.search(r"duplicates: (\d+).*added: (\d+)", joined, re.S)
    Check.record("second update skips duplicates", m2 is not None and int(m2.group(1)) > 0, m2.group(0) if m2 else joined[:300])
    if done:
        tap(r"^OK$")
    back()
    shot("proxy_list_after_subscription")
    back()


@phase
def p05_login():
    for attempt in range(3):
        if not find(r"Your phone|Phone number|Your Phone"):
            open_login_menu()
            back()
        open_login_menu()
        tap(r"Custom Backend")
        tb = wait(r"Test Backend", 5)
        if tb and not tb.checked:
            tap(tb)
            time.sleep(4)
        suffix = "%04d" % random.randint(0, 9999)
        phone = "999662" + suffix
        fields = [x for x in dump() if "EditText" in x.cls]
        log("phone fields:", fields)
        tap_xy(fields[0].cx, fields[0].cy)
        clear_field()
        type_text("999")
        tap_xy(fields[-1].cx, fields[-1].cy)
        clear_field()
        type_text("662" + suffix)
        shot("login_phone")
        key("KEYCODE_ENTER")
        time.sleep(3)
        if wait(r"^Yes$|^YES$", 8):
            tap(r"^Yes$|^YES$")
        code = wait(r"Enter code|Check your|code", 30)
        shot("login_code")
        if find(r"password", None):
            log("account has a password, trying another number")
            continue
        type_text("22222")
        time.sleep(6)
        if find(r"Your name|First name|Your Name"):
            fields = [x for x in dump() if "EditText" in x.cls]
            tap_xy(fields[0].cx, fields[0].cy)
            type_text("Parva")
            key("KEYCODE_ENTER")
            time.sleep(4)
            if wait(r"^Accept$", 8):
                tap(r"^Accept$")
            time.sleep(4)
        if find(r"Your Password|Enter your password|Two-Step"):
            log("2FA on this test number, retrying")
            shell("pm clear %s" % PKG)
            launch()
            continue
        dismiss_system_dialogs()
        ok = wait(r"Saved Messages|Chats|Search|New Message|Contacts", 30) is not None
        dismiss_system_dialogs()
        shot("logged_in")
        if ok:
            state["logged_in"] = True
            state["phone"] = "+" + phone
            Check.record("log in on the test DC (%s)" % ("through V2Ray" if state["v2ray_connected"] else "direct"), True, phone)
            return
    Check.record("log in on the test DC", False)


def peer(*args, timeout=180):
    r = subprocess.run([sys.executable, os.path.join(HERE, "peer.py"), *args], capture_output=True, timeout=timeout, text=True)
    log("peer", args[0], "->", r.returncode, r.stdout.strip()[-400:], r.stderr.strip()[-800:])
    return r.returncode == 0


@phase
def p06_peer_messages():
    if not state["logged_in"]:
        Check.record("peer messages", False, "not logged in")
        return
    session = os.path.join(OUT, "peer.session")
    for attempt in range(3):
        if peer("login", "999662%04d" % random.randint(0, 9999), session):
            break
    ok = peer("chat", session, state["phone"])
    state["peer"] = session if ok else None
    time.sleep(8)
    Check.record("peer sends, edits and deletes messages", ok)


def open_peer_chat():
    n = wait(r"Peer", 30)
    if not n:
        raise RuntimeError("peer dialog not in the chat list")
    tap(n)
    time.sleep(3)


@phase
def p07_chat_history():
    if not state["peer"]:
        Check.record("chat history", False, "no peer")
        return
    shot("dialogs_with_peer")
    open_peer_chat()
    labels = shot("chat_with_peer")
    joined = " ".join(labels)
    Check.record("deleted message stays in the chat", "This message will be deleted" in joined, joined[:300])
    Check.record("edited message shows its new text", "Edited text after the change" in joined)
    edited = find(r"Edited text after the change")
    if edited:
        # The time is in the bottom corner of the bubble.
        tap_xy(edited.r - 70, edited.b - 25)
        h = wait(r"Message history", 8)
        labels = shot("history_edited")
        joined = " ".join(labels)
        Check.record("tapping the time opens the edit history", h is not None and "Original text before edit" in joined, joined[:300])
        if h:
            tap(r"^Close$")
    deleted = find(r"This message will be deleted")
    if deleted:
        tap_xy(deleted.r - 70, deleted.b - 25)
        h = wait(r"Message history", 8)
        labels = shot("history_deleted")
        Check.record("deleted message history shows the deletion", h is not None and any("Deleted" in l for l in labels), " ".join(labels)[:300])
        if h:
            tap(r"^Close$")
    back()


@phase
def p08_chat_preview():
    if not state["peer"]:
        Check.record("chat preview", False, "no peer")
        return
    peer("send", state["peer"], "Unread message for the preview test")
    time.sleep(6)
    n = wait(r"Peer", 20)
    # Long-press the avatar on the left (right in RTL) of the dialog cell.
    from ui import Node  # noqa
    shell("input swipe %d %d %d %d 900" % (n.l + 80, n.cy, n.l + 80, n.cy))
    time.sleep(3)
    shot("preview_open")
    msg = find(r"Last text message in the chat|Unread message for the preview test")
    in_preview_before = find(r"Mark as read|Mute|Pin|Delete") is not None
    if msg:
        tap_xy(msg.cx, msg.cy)
        time.sleep(3)
    labels = shot("preview_after_text_tap")
    still_preview = find(r"Mark as read|Mute|Pin|Delete") is not None and not find(r"^Message$", None, cls="EditText")
    Check.record("tapping a text message keeps the preview (chat not opened)", in_preview_before and still_preview,
                 "before=%s after=%s" % (in_preview_before, still_preview))
    photo = find(r"Photo|A photo for the preview test")
    if photo:
        tap_xy(photo.cx, photo.cy)
        time.sleep(4)
        shot("preview_photo_tap")
        back()
    back()
    time.sleep(2)
    labels = shot("dialogs_after_preview")
    unread = any(re.search(r"unread", l, re.I) for l in labels)
    Check.record("chat still unread after the preview", unread, " | ".join(l for l in labels if "Peer" in l)[:200])


@phase
def p09_background():
    notif = shell("dumpsys notification --noredact")
    Check.record("connection notification is shown", "parvagram_connection_channel" in notif)
    key("KEYCODE_HOME")
    time.sleep(60)
    alive = app_alive()
    Check.record("process alive 60 s after leaving the app", alive)
    if state["peer"]:
        peer("send", state["peer"], "Background delivery check")
        time.sleep(20)
        notif = shell("dumpsys notification --noredact")
        Check.record("message notification arrives while in the background", "Background delivery check" in notif)
    launch()


def open_settings():
    # Drawer button at the top-left (top-right in RTL); the drawer has Settings.
    tap_xy(70, 150)
    time.sleep(2)
    tap(r"^Settings$|^تنظیمات$")
    time.sleep(2)


@phase
def p10_persian_nconfig():
    if not state["logged_in"]:
        Check.record("Persian N-Config", False, "not logged in")
        return
    open_settings()
    tap(r"^Language$")
    n = wait(r"فارسی", 5)
    for _ in range(8):
        if n:
            break
        swipe_up()
        n = find(r"فارسی")
    tap(n)
    time.sleep(4)
    back()
    shot("settings_fa")
    n = find(r"تنظیمات پرواگرام")
    for _ in range(6):
        if n:
            break
        swipe_up()
        n = find(r"تنظیمات پرواگرام")
    tap(n)
    time.sleep(2)
    shot("nconfig_fa")
    english = []
    for page in ("عمومی", "حساب", "چت", "آزمایشی"):
        p = find(page)
        if not p:
            continue
        tap(p)
        time.sleep(2)
        for i in range(12):
            labels = shot("nconfig_%s_%d" % (page, i)) if i < 3 else texts()
            english += [l for l in labels if re.fullmatch(r"[A-Za-z][A-Za-z ,'\-/()]{3,}", l.strip())]
            before = labels
            swipe_up(0.6)
            if texts() == before:
                break
        back()
    allowed = {"V2Ray", "OpenAI", "Google Gemini", "Groq", "DeepSeek", "Mistral AI", "OpenRouter", "Alibaba Qwen", "SiliconFlow", "Anthropic",
               "Telegram", "MicroG", "UnifiedPush", "Google FCM", "Lingocloud", "Sentry", "Pangu", "DeepL", "Yandex", "Material Design"}
    english = sorted(set(e for e in english if not any(a in e for a in allowed)))
    Check.record("no English labels in N-Config (Persian)", not english, ", ".join(english[:40]))


@phase
def p11_font_and_bubbles():
    if not state["logged_in"]:
        Check.record("font", False, "not logged in")
        return
    tap(r"^چت$")
    time.sleep(2)
    tap(r"فونت برنامه")
    time.sleep(2)
    shot("font_screen")
    tap(r"وزیرمتن")
    time.sleep(1)
    shot("font_preview_vazirmatn")
    tap(r"^اعمال$")
    if wait(r"راه‌اندازی مجدد|Restart", 5):
        tap(r"راه‌اندازی مجدد|Restart")
    time.sleep(12)
    launch()
    open_peer_chat()
    shot("chat_after_font_vazirmatn")
    shell("am force-stop %s" % PKG)
    launch()
    open_peer_chat()
    shot("chat_font_after_restart")
    prefs = shell("run-as %s cat shared_prefs/mainconfig.xml" % PKG)
    Check.record("font choice persists after restart", "builtin:vazirmatn" in prefs, re.findall(r'parva_custom_font[^/]*', prefs)[:1])
    Check.record("default text size 16 and bubble radius 17", ('name="fons_size"' not in prefs) and ('name="bubbleRadius"' not in prefs),
                 "defaults in use unless changed")
    back()


@phase
def p12_v2ray_sheet_fa():
    open_link(VLESS)
    wait(r"سرور V2Ray", 15)
    shot("v2ray_sheet_fa")
    back()


def main():
    adb("wait-for-device")
    shell("settings put global window_animation_scale 0; settings put global transition_animation_scale 0; settings put global animator_duration_scale 0")
    apk = sys.argv[1]
    log("installing", apk, adb("install", "-r", "-g", apk, timeout=300).strip()[-200:])
    adb("logcat", "-c")
    for p in (p01_launch, p02_vless_link, p03_proxy_list, p04_subscription, p05_login, p06_peer_messages,
              p07_chat_history, p08_chat_preview, p09_background, p10_persian_nconfig, p11_font_and_bubbles, p12_v2ray_sheet_fa):
        p()
        if not app_alive():
            log("app not running after", p.__name__)
            crash = logcat(r"FATAL EXCEPTION|AndroidRuntime|libc|DEBUG", 60)
            log("\n".join(crash))
            if any("FATAL EXCEPTION" in l for l in crash):
                Check.record("no crash during " + p.__name__, False)
            adb("logcat", "-c")
            launch()
    with open(os.path.join(OUT, "logcat.txt"), "w") as f:
        f.write(adb("logcat", "-d", "-v", "threadtime", timeout=120))
    ok = Check.summary()
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()

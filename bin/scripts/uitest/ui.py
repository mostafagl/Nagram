"""Small adb + uiautomator helpers for driving the app on an emulator."""
import os
import re
import subprocess
import time
import xml.etree.ElementTree as ET

PKG = "com.mostafagl.parvagram"
OUT = os.environ.get("UITEST_OUT", "uitest-out")
os.makedirs(OUT, exist_ok=True)
LOG = open(os.path.join(OUT, "report.txt"), "a", encoding="utf-8")
_shot = [0]


def log(*parts):
    line = " ".join(str(p) for p in parts)
    print(line, flush=True)
    LOG.write(line + "\n")
    LOG.flush()


def adb(*args, check=False, timeout=120):
    r = subprocess.run(["adb", *args], capture_output=True, timeout=timeout)
    if check and r.returncode != 0:
        raise RuntimeError("adb %s failed: %s" % (args, r.stderr.decode(errors="replace")))
    return r.stdout.decode(errors="replace")


def shell(cmd, timeout=120):
    return adb("shell", cmd, timeout=timeout)


class Node:
    def __init__(self, e):
        self.text = e.get("text") or ""
        self.desc = e.get("content-desc") or ""
        self.cls = e.get("class") or ""
        self.res = e.get("resource-id") or ""
        self.clickable = e.get("clickable") == "true"
        self.checked = e.get("checked") == "true"
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", e.get("bounds") or "[0,0][0,0]")
        self.l, self.t, self.r, self.b = map(int, m.groups())

    @property
    def label(self):
        return self.text or self.desc

    @property
    def cx(self):
        return (self.l + self.r) // 2

    @property
    def cy(self):
        return (self.t + self.b) // 2

    def __repr__(self):
        return "Node(%r, %r, [%d,%d][%d,%d])" % (self.text[:40], self.desc[:40], self.l, self.t, self.r, self.b)


def dump():
    for _ in range(3):
        shell("uiautomator dump /sdcard/ui.xml >/dev/null 2>&1")
        xml = shell("cat /sdcard/ui.xml")
        if "<hierarchy" in xml:
            try:
                root = ET.fromstring(xml[xml.index("<hierarchy"):xml.rindex("</hierarchy>") + len("</hierarchy>")])
                return [Node(e) for e in root.iter("node")]
            except Exception as e:  # noqa
                log("dump parse error", e)
        time.sleep(1)
    return []


def texts(nodes=None):
    nodes = dump() if nodes is None else nodes
    return [n.label for n in nodes if n.label.strip()]


def find(pattern, nodes=None, cls=None):
    rx = re.compile(pattern, re.S | re.I)
    for n in (dump() if nodes is None else nodes):
        if cls and cls not in n.cls:
            continue
        if n.label and rx.search(n.label):
            return n
    return None


def find_all(pattern, nodes=None):
    rx = re.compile(pattern, re.S | re.I)
    return [n for n in (dump() if nodes is None else nodes) if n.label and rx.search(n.label)]


def wait(pattern, timeout=30, interval=1.5):
    end = time.time() + timeout
    while time.time() < end:
        n = find(pattern)
        if n:
            return n
        time.sleep(interval)
    return None


def tap_xy(x, y):
    shell("input tap %d %d" % (x, y))
    time.sleep(1.2)


def tap(node_or_pattern, timeout=15):
    n = node_or_pattern
    if isinstance(n, str):
        n = wait(n, timeout)
        if not n:
            raise RuntimeError("not found on screen: " + node_or_pattern)
    tap_xy(n.cx, n.cy)
    return n


def long_press(n, ms=900):
    shell("input swipe %d %d %d %d %d" % (n.cx, n.cy, n.cx, n.cy, ms))
    time.sleep(1.5)


def type_text(s):
    # adb "input text" needs spaces as %s and shell metacharacters escaped.
    esc = ""
    for ch in s:
        if ch == " ":
            esc += "%s"
        elif ch in "&|;<>()$`\\\"'*?#~=%!{}[]":
            esc += "\\" + ch
        else:
            esc += ch
    shell("input text " + esc)
    time.sleep(0.8)


def key(code):
    shell("input keyevent %s" % code)
    time.sleep(0.8)


def back():
    key("KEYCODE_BACK")


def clear_field():
    key("KEYCODE_MOVE_END")
    shell("input keyevent " + " ".join(["KEYCODE_DEL"] * 40))
    time.sleep(0.5)


def swipe_up(fraction=0.5):
    size = screen_size()
    x = size[0] // 2
    shell("input swipe %d %d %d %d 400" % (x, int(size[1] * 0.75), x, int(size[1] * (0.75 - fraction))))
    time.sleep(1.2)


def screen_size():
    out = shell("wm size")
    m = re.search(r"(\d+)x(\d+)", out)
    return (int(m.group(1)), int(m.group(2))) if m else (1080, 2400)


def shot(name):
    _shot[0] += 1
    fname = "%02d_%s.png" % (_shot[0], name)
    data = subprocess.run(["adb", "exec-out", "screencap", "-p"], capture_output=True, timeout=60).stdout
    with open(os.path.join(OUT, fname), "wb") as f:
        f.write(data)
    labels = texts()
    log("[screen %s] %s" % (fname, " | ".join(l.replace("\n", " ")[:80] for l in labels[:60])))
    return labels


class Check:
    results = []

    @staticmethod
    def record(name, ok, detail=""):
        Check.results.append((name, ok, detail))
        log(("PASS" if ok else "FAIL") + ": " + name + (" -- " + detail if detail else ""))
        return ok

    @staticmethod
    def summary():
        log("\n===== SUMMARY =====")
        for name, ok, detail in Check.results:
            log(("PASS " if ok else "FAIL ") + name + (" -- " + detail if detail else ""))
        return all(ok for _, ok, _ in Check.results)

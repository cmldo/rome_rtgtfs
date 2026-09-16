"""Smoke test della release su emulatore: primo import, ricerca, tabellone, avvisi. Screenshot in shots/."""
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

APK = sys.argv[1]
PKG = "io.github.cmldo.romertgtfs"
os.makedirs("shots", exist_ok=True)


def adb(*args, check=True):
    return subprocess.run(["adb", *args], check=check, capture_output=True, text=True).stdout


def ui():
    """Albero della UI, oppure None se uiautomator non riesce (UI in animazione)."""
    out = adb("shell", "uiautomator", "dump", "/sdcard/ui.xml", check=False)
    if "dumped" not in out:
        return None
    try:
        return ET.fromstring(adb("shell", "cat", "/sdcard/ui.xml"))
    except ET.ParseError:
        return None


def labels(root):
    return [(n.get("text") or "") + " " + (n.get("content-desc") or "") for n in root.iter("node")]


def wait(what, pred, timeout):
    end = time.time() + timeout
    while time.time() < end:
        root = ui()
        if root is not None and pred(root):
            return root
        time.sleep(3)
    shot("timeout")
    sys.exit(f"Timeout in attesa di: {what}\n{labels(root) if root is not None else ''}")


def has(text):
    return lambda root: any(text in label for label in labels(root))


def tap(root, pred):
    node = next(n for n in root.iter("node") if pred(n))
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
    adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))


def shot(name):
    with open(f"shots/{name}.png", "wb") as f:
        f.write(subprocess.run(["adb", "exec-out", "screencap", "-p"], capture_output=True).stdout)


def check_alive():
    log = adb("logcat", "-d", "-b", "crash", check=False)
    if "FATAL EXCEPTION" in log:
        sys.exit("Crash:\n" + log)


adb("install", "-r", APK)
adb("logcat", "-c", check=False)
adb("shell", "am", "start", "-W", "-n", f"{PKG}/.MainActivity")

busy = ("Aggiornamento orari", "Controllo degli orari", "In attesa", "non ancora disponibili")
root = wait(
    "fine del primo import",
    lambda r: any("non riuscito" in l for l in labels(r)) or not any(b in l for l in labels(r) for b in busy),
    900,
)
check_alive()
if any("non riuscito" in label for label in labels(root)):
    shot("errore")
    sys.exit("Import non riuscito: " + str(labels(root)))
shot("1-avvio")

tap(root, lambda n: n.get("class") == "android.widget.EditText")
time.sleep(1)
adb("shell", "input", "text", "termini")
root = wait("risultati di ricerca", has("Fermata 70240"), 60)
shot("2-ricerca")

tap(root, lambda n: "Fermata 70240" in (n.get("text") or ""))
root = wait("tabellone", has("Tempo reale aggiornato"), 120)
shot("3-fermata")
if not any(re.search(r"\d+ min|ora", label) for label in labels(root)):
    sys.exit("Nessun passaggio nel tabellone: " + str(labels(root)))

adb("shell", "input", "keyevent", "KEYCODE_BACK")
root = wait("home", has("Avvisi"), 30)
tap(root, lambda n: n.get("content-desc") == "Avvisi")
wait("avvisi", has("avvisi attivi"), 120)
shot("4-avvisi")

check_alive()
print("Smoke test superato")

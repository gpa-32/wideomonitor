#!/usr/bin/env python3
"""Test aplikacji Wideodomofon na emulatorze Androida.

Symuluje bramofon (klient SIP po TCP) i przekaznik furtki (serwer HTTP z logowaniem),
steruje aplikacja przez jej panel www i sprawdza przez adb, co jest na ekranie.
Wyniki: ci-out/report.md, zrzuty ekranu, logcat, dziennik aplikacji.
"""
import base64
import html
import http.server
import json
import os
import queue
import random
import re
import socket
import string
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request

PKG = "pl.wideodomofon.app"
APK = sys.argv[1] if len(sys.argv) > 1 else "app/build/outputs/apk/release/app-release.apk"
OUT = "ci-out"
WEB = "http://127.0.0.1:8080"
HOST_FROM_EMU = "10.0.2.2"   # adres komputera-hosta widziany z emulatora
SIP_LISTEN = 5070
GATE_PORT = 9000

os.makedirs(OUT, exist_ok=True)
results = []
password = ""


def log(msg):
    print(time.strftime("%H:%M:%S"), msg, flush=True)


def adb(*args, timeout=60):
    try:
        r = subprocess.run(["adb", *args], capture_output=True, timeout=timeout)
        return (r.stdout + r.stderr).decode("utf-8", "replace")
    except Exception as e:  # noqa
        return "ADB-ERROR %s" % e


def shell(cmd, timeout=60):
    return adb("shell", cmd, timeout=timeout)


def screenshot(name):
    try:
        r = subprocess.run(["adb", "exec-out", "screencap", "-p"], capture_output=True, timeout=30)
        with open(os.path.join(OUT, name + ".png"), "wb") as f:
            f.write(r.stdout)
    except Exception as e:  # noqa
        log("zrzut ekranu nieudany: %s" % e)


def resumed():
    out = shell("dumpsys activity activities | grep ResumedActivity")
    top = None
    first = None
    for line in out.splitlines():
        m = re.search(r"ResumedActivity[:=]\s*ActivityRecord\{\S+\s+\S+\s+([A-Za-z0-9_.]+/[A-Za-z0-9_.$]+)", line)
        if not m:
            continue
        if "topResumedActivity" in line:
            top = m.group(1)
        if first is None:
            first = m.group(1)
    return top or first or ""


def wait_for(fn, timeout, step=0.5):
    end = time.time() + timeout
    last = None
    while time.time() < end:
        last = fn()
        if last:
            return last
        time.sleep(step)
    return last


def api(path, body=None, auth=True, header=True):
    req = urllib.request.Request(WEB + path)
    if auth:
        req.add_header("Authorization", "Basic " + base64.b64encode(("admin:" + password).encode()).decode())
    data = None
    if body is not None:
        data = json.dumps(body).encode()
        req.add_header("Content-Type", "application/json")
        if header:
            req.add_header("X-Wideodomofon", "1")
    try:
        with urllib.request.urlopen(req, data=data, timeout=10) as r:
            return r.status, r.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")
    except Exception as e:  # noqa
        return 0, str(e)


def status():
    code, body = api("/api/status")
    try:
        return json.loads(body) if code == 200 else {}
    except Exception:  # noqa
        return {}


def check(name, ok, detail=""):
    results.append((name, bool(ok), detail))
    log(("PASS  " if ok else "FAIL  ") + name + ("  -- " + detail if detail else ""))
    return bool(ok)


# --------------------------------------------------------------------------- przekaznik furtki (HTTP + Basic)

gate_hits = []


class GateHandler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        auth = self.headers.get("Authorization", "")
        expected = "Basic " + base64.b64encode(b"furtka:tajne").decode()
        if auth != expected:
            gate_hits.append(("401", self.path))
            self.send_response(401)
            self.send_header("WWW-Authenticate", 'Basic realm="relay"')
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        gate_hits.append(("200", self.path))
        body = b"OK relay opened"
        self.send_response(200)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *a):
        pass


# --------------------------------------------------------------------------- symulowany bramofon (SIP/TCP)

def rnd(n=10):
    return "".join(random.choice(string.ascii_lowercase + string.digits) for _ in range(n))


class SipMsg:
    def __init__(self, raw_head, body):
        lines = raw_head.split("\r\n")
        self.start = lines[0]
        self.headers = []
        for ln in lines[1:]:
            if ":" in ln:
                k, v = ln.split(":", 1)
                self.headers.append((k.strip(), v.strip()))
        self.body = body
        self.is_response = self.start.startswith("SIP/2.0")
        self.code = int(self.start.split()[1]) if self.is_response else 0
        self.method = "" if self.is_response else self.start.split()[0]

    def get(self, name, default=""):
        short = {"via": "v", "from": "f", "to": "t", "call-id": "i", "contact": "m", "content-length": "l"}
        for k, v in self.headers:
            if k.lower() == name.lower() or k.lower() == short.get(name.lower(), "\0"):
                return v
        return default

    def all(self, name):
        return [v for k, v in self.headers if k.lower() == name.lower()]


class Doorphone:
    """Minimalny klient SIP: dzwoni na tablet i reaguje na jego odpowiedzi."""

    def __init__(self):
        self.q = queue.Queue()
        self.srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.srv.bind(("0.0.0.0", SIP_LISTEN))
        self.srv.listen(5)
        threading.Thread(target=self._accept, daemon=True).start()
        self.conn = None

    def _accept(self):
        while True:
            try:
                c, _ = self.srv.accept()
            except Exception:  # noqa
                return
            threading.Thread(target=self._reader, args=(c,), daemon=True).start()

    def _reader(self, c):
        buf = b""
        while True:
            try:
                d = c.recv(65536)
            except Exception:  # noqa
                d = b""
            if not d:
                return
            buf += d
            while True:
                buf = buf.lstrip(b"\r\n")
                i = buf.find(b"\r\n\r\n")
                if i < 0:
                    break
                head = buf[:i].decode("utf-8", "replace")
                m = re.search(r"(?im)^(?:content-length|l)\s*:\s*(\d+)", head)
                n = int(m.group(1)) if m else 0
                if len(buf) < i + 4 + n:
                    break
                body = buf[i + 4:i + 4 + n].decode("utf-8", "replace")
                buf = buf[i + 4 + n:]
                try:
                    self.q.put((c, SipMsg(head, body)))
                except Exception as e:  # noqa
                    log("SIP: blad parsowania: %s" % e)

    def send(self, c, text):
        c.sendall(text.encode())

    def invite(self):
        """Wysyla INVITE; zwraca slownik z danymi dialogu."""
        while not self.q.empty():
            self.q.get()
        c = socket.create_connection(("127.0.0.1", 5060), timeout=10)
        c.settimeout(None)
        self.conn = c
        threading.Thread(target=self._reader, args=(c,), daemon=True).start()
        d = {
            "branch": "z9hG4bK" + rnd(), "tag": rnd(), "callid": rnd(16) + "@" + HOST_FROM_EMU,
            "uri": "sip:tablet@127.0.0.1:5060;transport=tcp", "totag": "", "conn": c,
        }
        sdp = ("v=0\r\no=furtka 1 1 IN IP4 %s\r\ns=-\r\nc=IN IP4 %s\r\nt=0 0\r\n"
               "m=audio 40000 RTP/AVP 0 8 101\r\na=rtpmap:0 PCMU/8000\r\na=rtpmap:8 PCMA/8000\r\n"
               "a=rtpmap:101 telephone-event/8000\r\na=fmtp:101 0-15\r\na=sendrecv\r\n") % (HOST_FROM_EMU, HOST_FROM_EMU)
        d["from"] = '"Furtka test" <sip:furtka@%s>;tag=%s' % (HOST_FROM_EMU, d["tag"])
        d["to"] = "<%s>" % d["uri"]
        msg = ("INVITE %s SIP/2.0\r\n"
               "Via: SIP/2.0/TCP %s:%d;branch=%s;rport\r\n"
               "Max-Forwards: 70\r\n"
               "From: %s\r\nTo: %s\r\nCall-ID: %s\r\nCSeq: 1 INVITE\r\n"
               "Contact: <sip:furtka@%s:%d;transport=tcp>\r\n"
               "Allow: INVITE, ACK, BYE, CANCEL, OPTIONS, INFO\r\n"
               "User-Agent: test-bramofon\r\n"
               "Content-Type: application/sdp\r\nContent-Length: %d\r\n\r\n%s") % (
                  d["uri"], HOST_FROM_EMU, SIP_LISTEN, d["branch"], d["from"], d["to"], d["callid"],
                  HOST_FROM_EMU, SIP_LISTEN, len(sdp), sdp)
        self.send(c, msg)
        return d

    def _ack(self, d, resp):
        to = resp.get("To") or d["to"]
        if resp.code < 300:
            branch = "z9hG4bK" + rnd()
            m = re.search(r"<([^>]+)>", resp.get("Contact"))
            uri = m.group(1) if m else d["uri"]
            d["remote_target"] = uri
            d["to_full"] = to
        else:
            branch = d["branch"]
            uri = d["uri"]
        msg = ("ACK %s SIP/2.0\r\nVia: SIP/2.0/TCP %s:%d;branch=%s;rport\r\nMax-Forwards: 70\r\n"
               "From: %s\r\nTo: %s\r\nCall-ID: %s\r\nCSeq: 1 ACK\r\nContent-Length: 0\r\n\r\n") % (
                  uri, HOST_FROM_EMU, SIP_LISTEN, branch, d["from"], to, d["callid"])
        self.send(d["conn"], msg)

    def _reply_ok(self, c, req):
        lines = ["SIP/2.0 200 OK"]
        for v in req.all("Via"):
            lines.append("Via: " + v)
        for h in ("From", "To", "Call-ID", "CSeq"):
            lines.append("%s: %s" % (h, req.get(h)))
        lines.append("Content-Length: 0")
        self.send(c, "\r\n".join(lines) + "\r\n\r\n")

    def wait(self, d, want, timeout):
        """Czeka na zdarzenie: 'ringing', 'final' (odpowiedz koncowa na INVITE) albo 'bye'.
        Zwraca kod odpowiedzi / True, albo None po przekroczeniu czasu."""
        end = time.time() + timeout
        while time.time() < end:
            try:
                c, m = self.q.get(timeout=0.5)
            except queue.Empty:
                continue
            if m.is_response:
                if "INVITE" not in m.get("CSeq"):
                    continue
                log("SIP <- %s" % m.start)
                if m.code in (180, 183) and want == "ringing":
                    return m.code
                if m.code >= 200:
                    self._ack(d, m)
                    if want == "final":
                        return m.code
                    if want == "ringing":
                        return -m.code
            else:
                log("SIP <- %s" % m.start)
                if m.method != "ACK":
                    self._reply_ok(c, m)
                if m.method == "BYE" and want == "bye":
                    return True
        return None

    def bye(self, d):
        """Rozlaczenie od strony bramofonu (sprzatanie po nieudanym tescie)."""
        try:
            msg = ("BYE %s SIP/2.0\r\nVia: SIP/2.0/TCP %s:%d;branch=z9hG4bK%s;rport\r\nMax-Forwards: 70\r\n"
                   "From: %s\r\nTo: %s\r\nCall-ID: %s\r\nCSeq: 2 BYE\r\nContent-Length: 0\r\n\r\n") % (
                      d.get("remote_target", d["uri"]), HOST_FROM_EMU, SIP_LISTEN, rnd(), d["from"],
                      d.get("to_full", d["to"]), d["callid"])
            self.send(d["conn"], msg)
        except Exception:  # noqa
            pass

    def cancel(self, d):
        try:
            msg = ("CANCEL %s SIP/2.0\r\nVia: SIP/2.0/TCP %s:%d;branch=%s;rport\r\nMax-Forwards: 70\r\n"
                   "From: %s\r\nTo: %s\r\nCall-ID: %s\r\nCSeq: 1 CANCEL\r\nContent-Length: 0\r\n\r\n") % (
                      d["uri"], HOST_FROM_EMU, SIP_LISTEN, d["branch"], d["from"], d["to"], d["callid"])
            self.send(d["conn"], msg)
        except Exception:  # noqa
            pass


# --------------------------------------------------------------------------- scenariusze

def is_call_screen():
    return (PKG + "/.CallActivity") in resumed().replace(PKG + "/" + PKG, PKG + "/")


def is_settings():
    return resumed().startswith("com.android.settings/")


def open_previous_app():
    shell("am start -a android.settings.SETTINGS")
    return wait_for(is_settings, 15)


def idle():
    return status().get("call_state") == "czuwanie"


def test_start():
    log("=== Instalacja i start ===")
    out = adb("install", "-r", APK, timeout=300)
    check("Instalacja APK", "Success" in out, out.strip()[-200:])
    shell("pm grant %s android.permission.RECORD_AUDIO" % PKG)
    shell("pm grant %s android.permission.POST_NOTIFICATIONS" % PKG)
    shell("appops set %s SYSTEM_ALERT_WINDOW allow" % PKG)
    shell("am start -n %s/.MainActivity" % PKG)
    time.sleep(15)
    pid = shell("pidof " + PKG).strip()
    check("Proces aplikacji dziala po starcie", pid.isdigit(), "pid=" + pid)
    svc = shell("dumpsys activity services " + PKG)
    check("Usluga w tle uruchomiona (pierwszoplanowa)", "IntercomService" in svc and "isForeground=true" in svc)
    screenshot("01-ekran-glowny")


def test_web():
    global password
    log("=== Panel www ===")
    adb("forward", "tcp:8080", "tcp:8080")
    adb("forward", "tcp:5060", "tcp:5060")
    xml = html.unescape(shell("cat /data/data/%s/shared_prefs/cfg.xml" % PKG))
    m = re.search(r'"web_pass"\s*:\s*"([^"]+)"', xml)
    password = m.group(1) if m else ""
    check("Wygenerowano losowe haslo panelu", len(password) >= 8)
    code, _ = api("/api/status", auth=False)
    check("Panel odrzuca dostep bez hasla (401)", code == 401, "kod %s" % code)
    code, body = api("/api/status")
    check("Panel odpowiada po zalogowaniu", code == 200 and '"version"' in body, body[:300])
    code, body = api("/")
    check("Strona konfiguracji laduje sie", code == 200 and "Wideodomofon" in body, "kod %s, %d B" % (code, len(body)))
    code, _ = api("/api/config", body={"dtmf_code": "9"}, header=False)
    check("Zapis bez naglowka ochronnego odrzucony (403)", code == 403, "kod %s" % code)
    cfg = {"gate_mode": "http", "http_url": "http://%s:%d/fcgi/OpenDoor?action=OpenDoor&DoorNum=1" % (HOST_FROM_EMU, GATE_PORT),
           "http_user": "furtka", "http_pass": "tajne", "hangup_after_open_s": 3, "video_source": "sip",
           "ring_timeout_s": 45, "ring_enabled": False}
    code, body = api("/api/config", body=cfg)
    check("Zapis ustawien przez www", code == 200, body[:200])
    code, body = api("/api/config")
    try:
        saved = json.loads(body)
    except Exception:  # noqa
        saved = {}
    check("Ustawienia zapamietane", saved.get("gate_mode") == "http" and saved.get("http_user") == "furtka"
          and saved.get("dtmf_code") == "#", json.dumps({k: saved.get(k) for k in ("gate_mode", "http_user", "dtmf_code")}))
    time.sleep(6)  # restart SIP po zapisie
    st = status()
    check("SIP dziala po zmianie ustawien", "blad" not in str(st.get("sip", "blad")), str(st.get("sip")))
    log("status: " + json.dumps(st, ensure_ascii=False))


def ring(door, label):
    """Wspolny poczatek: poprzednia aplikacja na wierzchu, dzwonimy, sprawdzamy ekran."""
    check(label + ": poprzednia aplikacja (Ustawienia) na wierzchu", open_previous_app(), resumed())
    time.sleep(1)
    d = door.invite()
    r = door.wait(d, "ringing", 15)
    check(label + ": tablet przyjal polaczenie i dzwoni", r in (180, 183), "odpowiedz SIP: %s" % r)
    ok = wait_for(is_call_screen, 10)
    check(label + ": ekran polaczenia wyskoczyl nad aplikacja", ok, resumed())
    st = status()
    check(label + ": stan 'dzwoni' z nazwa dzwoniacego", str(st.get("call_state", "")).startswith("dzwoni")
          and "Furtka test" in str(st.get("call_state")), str(st.get("call_state")))
    return d


def back_to_previous(label):
    ok = wait_for(lambda: is_settings() and idle(), 15)
    check(label + ": po zakonczeniu wrocila poprzednia aplikacja", ok, "na wierzchu: %s, stan: %s" % (resumed(), status().get("call_state")))
    time.sleep(2)


def test_reject(door):
    log("=== Polaczenie 1: odrzucenie ===")
    d = ring(door, "Odrzucenie")
    time.sleep(1)
    screenshot("02-dzwoni")
    api("/api/action", body={"name": "hangup"})
    r = door.wait(d, "final", 10)
    check("Odrzucenie: bramofon dostal odmowe", r is not None and r >= 400, "kod SIP: %s" % r)
    if r is None:
        door.cancel(d)
    back_to_previous("Odrzucenie")
    screenshot("03-po-odrzuceniu")


def test_answer(door):
    log("=== Polaczenie 2: odebranie i rozlaczenie ===")
    d = ring(door, "Rozmowa")
    api("/api/action", body={"name": "answer"})
    r = door.wait(d, "final", 10)
    check("Rozmowa: polaczenie odebrane (200 OK)", r == 200, "kod SIP: %s" % r)
    ok = wait_for(lambda: str(status().get("call_state", "")).startswith("rozmowa"), 10)
    check("Rozmowa: stan 'rozmowa'", ok, str(status().get("call_state")))
    time.sleep(2)
    screenshot("04-rozmowa")
    check("Rozmowa: ekran polaczenia nadal na wierzchu", is_call_screen(), resumed())
    api("/api/action", body={"name": "hangup"})
    r = door.wait(d, "bye", 10)
    check("Rozmowa: tablet rozlaczyl (BYE)", r is True, str(r))
    if r is not True:
        door.bye(d)
    back_to_previous("Rozmowa")


def test_gate(door):
    log("=== Polaczenie 3: otwarcie furtki podczas dzwonienia ===")
    del gate_hits[:]
    d = ring(door, "Furtka")
    api("/api/action", body={"name": "open"})
    ok = wait_for(lambda: any(h[0] == "200" for h in gate_hits), 10)
    check("Furtka: przekaznik dostal zapytanie HTTP z poprawnym logowaniem", ok, str(gate_hits))
    time.sleep(1)
    screenshot("05-furtka-otwarta")
    r = door.wait(d, "final", 12)
    check("Furtka: polaczenie zakonczone automatycznie po otwarciu", r is not None and r >= 400, "kod SIP: %s" % r)
    if r is None:
        door.cancel(d)
    back_to_previous("Furtka")
    check("Furtka: wynik widoczny w panelu", "Furtka otwarta" in str(status().get("gate")), str(status().get("gate")))
    screenshot("06-po-furtce")


def test_caller_hangup(door):
    log("=== Polaczenie 4: dzwoniacy rezygnuje ===")
    d = ring(door, "Rezygnacja")
    door.cancel(d)
    door.wait(d, "final", 8)
    back_to_previous("Rezygnacja")


def test_recovery(door):
    log("=== Odpornosc: nagle ubicie procesu ===")
    pid = shell("pidof " + PKG).strip()
    shell("kill -9 " + pid)
    time.sleep(1)

    def alive():
        p = shell("pidof " + PKG).strip()
        return p.isdigit() and p != pid and status().get("version")

    t0 = time.time()
    ok = wait_for(alive, 90, step=2)
    check("Po ubiciu procesu usluga wstaje sama", ok, "po %.0f s" % (time.time() - t0))
    time.sleep(6)
    log("=== Polaczenie po restarcie ===")
    d = ring(door, "Po restarcie")
    screenshot("07-dzwoni-po-restarcie")
    api("/api/action", body={"name": "hangup"})
    r = door.wait(d, "final", 10)
    check("Po restarcie: odrzucenie dziala", r is not None and r >= 400, "kod SIP: %s" % r)
    if r is None:
        door.cancel(d)
    back_to_previous("Po restarcie")


def main():
    log("adb root: " + adb("root").strip())
    time.sleep(3)
    adb("wait-for-device")
    log("Android: " + shell("getprop ro.build.version.release").strip() + ", ABI " + shell("getprop ro.product.cpu.abi").strip())
    shell("logcat -c")

    srv = http.server.ThreadingHTTPServer(("0.0.0.0", GATE_PORT), GateHandler)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    door = Doorphone()

    steps = [test_start, test_web, lambda: test_reject(door), lambda: test_answer(door),
             lambda: test_gate(door), lambda: test_caller_hangup(door), lambda: test_recovery(door)]
    for s in steps:
        try:
            s()
        except Exception as e:  # noqa
            import traceback
            traceback.print_exc()
            check("Krok testu zakonczony bez bledu skryptu", False, repr(e))
            screenshot("blad-%d" % len(results))

    logcat = adb("logcat", "-d", "-v", "time", timeout=120)
    with open(os.path.join(OUT, "logcat.txt"), "w", encoding="utf-8") as f:
        f.write(logcat)
    fatal = [ln for ln in logcat.splitlines() if "FATAL EXCEPTION" in ln or ("AndroidRuntime" in ln and PKG in ln)]
    anr = [ln for ln in logcat.splitlines() if "ANR in " + PKG in ln]
    check("Brak awarii aplikacji w logach systemu", not fatal, "; ".join(fatal[:3]))
    check("Brak zawieszen (ANR) w logach systemu", not anr, "; ".join(anr[:3]))
    code, applog = api("/api/log")
    with open(os.path.join(OUT, "app-log.txt"), "w", encoding="utf-8") as f:
        f.write(applog)

    failed = [r for r in results if not r[1]]
    with open(os.path.join(OUT, "report.md"), "w", encoding="utf-8") as f:
        f.write("# Test na emulatorze: %d/%d OK\n\n" % (len(results) - len(failed), len(results)))
        for name, ok, detail in results:
            f.write("- %s %s%s\n" % ("PASS" if ok else "**FAIL**", name, (" -- `" + detail.replace("`", "'")[:300] + "`") if detail and not ok else ""))
    log("WYNIK: %d/%d OK" % (len(results) - len(failed), len(results)))
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()

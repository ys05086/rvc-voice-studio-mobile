"""rvc2phone with a window: RVC voice models (.pth) -> the phone app's files, and straight onto an Android phone.

  rvc2phone_gui.bat [VOICE.pth ...]      (or drag .pth files onto it)

Converting runs rvc2phone.py once per voice (same options as the command line) and shows its stages. 폰으로 보내기
copies the converted files into the app's folder over USB or wireless debugging (adb); the app moves them into place
by itself the next time it opens, so nothing has to be imported by hand. iPhone: copy the output folder with the
Files app as before.
"""
import os
from pathlib import Path
import queue
import re
import subprocess
import sys
import threading
import time
import tkinter as tk
from tkinter import filedialog, messagebox, ttk
import wave

HERE = Path(__file__).resolve().parent
PY = Path(sys.executable).with_name("python.exe") if sys.executable.endswith("pythonw.exe") else Path(sys.executable)
PACKAGE = "com.voicestudio.phone"
REMOTE = f"/sdcard/Android/data/{PACKAGE}/files"
NO_WINDOW = 0x08000000 if os.name == "nt" else 0  # no console window flashing for each adb / python call

# the app's palette (android/app/src/main/res/values/colors.xml)
BG, PANEL, LINE, TEXT, MUTED = "#F7F4FA", "#FFFEFD", "#E9E3F0", "#3F3A4A", "#857B94"
ACCENT, STRONG, LAVENDER, GREEN, REC = "#9E83BC", "#7C62A0", "#EAE3F5", "#5F9F82", "#D0607A"

STAGES = [  # (marker in rvc2phone's output, what the window says)
    (".enc:", "ONNX로 내보내는 중"),
    ("dec_a + dec_b", "Android GPU 디코더 만드는 중"),
    ("wrote ", "GPU 디코더 완료"),
    ("calibrating on", "NPU 디코더 보정 중 (녹음을 모델에 통과시켜요)"),
    ("calibrated on", "NPU 디코더 완료"),
    ("voice:", "완료"),
]


def adb_path():
    for candidate in (Path(os.environ.get("LOCALAPPDATA", "")) / "Android/Sdk/platform-tools/adb.exe",
                      HERE / "platform-tools/adb.exe"):
        if candidate.is_file():
            return str(candidate)
    from shutil import which
    return which("adb")


def adb(*args, timeout=600):
    exe = adb_path()
    if not exe:
        raise RuntimeError("adb가 없어요")
    r = subprocess.run([exe, *map(str, args)], capture_output=True, text=True, encoding="utf-8", errors="replace",
                       timeout=timeout, creationflags=NO_WINDOW)
    return r.returncode, (r.stdout + r.stderr).strip()


def recordings(folder):
    """(WAV files, seconds) in a calibration folder."""
    files, seconds = 0, 0.0
    for f in Path(folder).glob("*.wav") if Path(folder).is_dir() else []:
        try:
            with wave.open(str(f)) as w:
                seconds += w.getnframes() / w.getframerate()
            files += 1
        except (wave.Error, EOFError):
            pass
    return files, seconds


class App(tk.Tk):
    def __init__(self, voices):
        super().__init__()
        self.title("rvc2phone · 목소리 모델을 폰 앱용으로")
        self.configure(bg=BG)
        self.geometry("800x960")
        self.minsize(720, 760)
        self.events = queue.Queue()
        self.voices = []          # [{"path", "name", "info", "done"}]
        self.running = False
        self.style()
        self.build()
        for v in voices:
            self.add_voice(Path(v))
        self.refresh_calib()
        self.refresh_devices()
        self.after(100, self.pump)

    # ------------------------------------------------------------------ look
    def style(self):
        s = ttk.Style(self)
        s.theme_use("clam")
        font = ("Malgun Gothic", 10)
        s.configure(".", background=BG, foreground=TEXT, font=font)
        s.configure("Card.TFrame", background=PANEL, relief="solid", borderwidth=1, bordercolor=LINE)
        s.configure("Card.TLabel", background=PANEL, foreground=TEXT)
        s.configure("Title.TLabel", background=PANEL, foreground=TEXT, font=("Malgun Gothic", 11, "bold"))
        s.configure("Hint.TLabel", background=PANEL, foreground=MUTED, font=("Malgun Gothic", 9))
        s.configure("Ok.TLabel", background=PANEL, foreground=GREEN, font=("Malgun Gothic", 9, "bold"))
        s.configure("Bad.TLabel", background=PANEL, foreground=REC, font=("Malgun Gothic", 9, "bold"))
        s.configure("Card.TCheckbutton", background=PANEL, foreground=TEXT)
        s.map("Card.TCheckbutton", background=[("active", PANEL)])
        s.configure("TButton", background=LAVENDER, foreground=TEXT, bordercolor=LINE, padding=(12, 5))
        s.map("TButton", background=[("active", "#DED3EF"), ("disabled", "#F0ECF5")])
        s.configure("Primary.TButton", background=ACCENT, foreground="white", font=("Malgun Gothic", 11, "bold"), padding=(16, 8))
        s.map("Primary.TButton", background=[("active", STRONG), ("disabled", "#CFC3DE")])
        s.configure("TEntry", fieldbackground="white", bordercolor=LINE)
        s.configure("Horizontal.TProgressbar", background=ACCENT, troughcolor=LAVENDER, bordercolor=LINE)

    def card(self, parent, title, hint=None):
        frame = ttk.Frame(parent, style="Card.TFrame", padding=12)
        frame.pack(fill="x", pady=(10, 0))
        head = ttk.Frame(frame, style="Card.TFrame", padding=0, borderwidth=0)
        head.pack(fill="x")
        ttk.Label(head, text=title, style="Title.TLabel").pack(side="left")
        if hint:
            ttk.Label(head, text=hint, style="Hint.TLabel").pack(side="left", padx=(10, 0))
        return frame

    # ------------------------------------------------------------------ layout
    def build(self):
        top = tk.Frame(self, bg=BG)
        top.pack(fill="x", padx=18, pady=(14, 0))
        brand = tk.Frame(top, bg=BG)
        brand.pack(side="left")
        tk.Label(brand, text="voice ", bg=BG, fg=TEXT, font=("Malgun Gothic", 18, "bold")).pack(side="left")
        tk.Label(brand, text="/", bg=BG, fg=ACCENT, font=("Malgun Gothic", 18, "bold")).pack(side="left")
        tk.Label(brand, text=" studio · 변환 도구", bg=BG, fg=TEXT, font=("Malgun Gothic", 18, "bold")).pack(side="left")
        body = tk.Frame(self, bg=BG)
        body.pack(fill="both", expand=True, padx=18, pady=(0, 14))

        # voices
        c = self.card(body, "목소리 모델 (.pth)", "RVC v2 · 48 kHz · 음높이 있는 모델")
        row = ttk.Frame(c, style="Card.TFrame", borderwidth=0)
        row.pack(fill="x", pady=(8, 0))
        self.list = tk.Listbox(row, height=4, bg="white", fg=TEXT, selectbackground=LAVENDER, selectforeground=TEXT,
                               highlightthickness=1, highlightbackground=LINE, relief="flat", font=("Malgun Gothic", 10))
        self.list.pack(side="left", fill="x", expand=True)
        self.list.bind("<<ListboxSelect>>", lambda e: self.show_selected())
        buttons = ttk.Frame(row, style="Card.TFrame", borderwidth=0)
        buttons.pack(side="left", padx=(10, 0))
        ttk.Button(buttons, text="추가", command=self.pick_voices).pack(fill="x")
        ttk.Button(buttons, text="빼기", command=self.remove_voice).pack(fill="x", pady=(6, 0))
        name_row = ttk.Frame(c, style="Card.TFrame", borderwidth=0)
        name_row.pack(fill="x", pady=(8, 0))
        ttk.Label(name_row, text="앱에서 쓸 이름", style="Card.TLabel").pack(side="left")
        self.name = tk.StringVar()
        entry = ttk.Entry(name_row, textvariable=self.name, width=24)
        entry.pack(side="left", padx=(10, 0))
        self.name.trace_add("write", lambda *_: self.rename_selected())
        self.info = ttk.Label(name_row, text="", style="Hint.TLabel")
        self.info.pack(side="left", padx=(12, 0))

        # calibration
        c = self.card(body, "보정 녹음 (Android NPU용)", "내 목소리 WAV 몇 분")
        row = ttk.Frame(c, style="Card.TFrame", borderwidth=0)
        row.pack(fill="x", pady=(8, 0))
        self.calib = tk.StringVar(value=str(HERE / "calib"))
        ttk.Entry(row, textvariable=self.calib).pack(side="left", fill="x", expand=True)
        ttk.Button(row, text="바꾸기", command=self.pick_calib).pack(side="left", padx=(8, 0))
        ttk.Button(row, text="열기", command=lambda: self.open_folder(self.calib.get())).pack(side="left", padx=(6, 0))
        self.calib_info = ttk.Label(c, text="", style="Hint.TLabel")
        self.calib_info.pack(anchor="w", pady=(6, 0))
        ttk.Label(c, style="Hint.TLabel", wraplength=700, justify="left",
                  text="Android 폰의 NPU는 이 녹음으로 맞춘 '정수 디코더'를 쓰면 32비트와 같은 소리로 2배 빨라져요. "
                       "평소 말소리에 큰 소리·작은 소리·웃음·숨소리가 섞이면 더 좋아요. 없으면 그 파일만 건너뛰어요.").pack(anchor="w", pady=(4, 0))

        # options and output
        c = self.card(body, "옵션")
        self.npu = tk.BooleanVar(value=True)
        self.ncnn = tk.BooleanVar(value=True)
        self.common = tk.BooleanVar(value=not (HERE / "out/common/hubert.onnx").is_file())
        ttk.Checkbutton(c, text="Android NPU 정수 디코더 (권장, 보정 녹음 필요)", variable=self.npu, style="Card.TCheckbutton").pack(anchor="w", pady=(8, 0))
        ttk.Checkbutton(c, text="Android GPU 디코더 (ncnn, 32비트)", variable=self.ncnn, style="Card.TCheckbutton").pack(anchor="w", pady=(2, 0))
        ttk.Checkbutton(c, text="공통 모델도 만들기 (hubert · rmvpe · NPU용 hubert, 처음 한 번 · 1 GB 받기)", variable=self.common,
                        style="Card.TCheckbutton").pack(anchor="w", pady=(2, 0))
        row = ttk.Frame(c, style="Card.TFrame", borderwidth=0)
        row.pack(fill="x", pady=(8, 0))
        ttk.Label(row, text="출력 폴더", style="Card.TLabel").pack(side="left")
        self.out = tk.StringVar(value=str(HERE / "out"))
        ttk.Entry(row, textvariable=self.out).pack(side="left", fill="x", expand=True, padx=(10, 0))
        ttk.Button(row, text="열기", command=lambda: self.open_folder(self.out.get())).pack(side="left", padx=(8, 0))

        # convert
        row = tk.Frame(body, bg=BG)
        row.pack(fill="x", pady=(12, 0))
        self.convert_button = ttk.Button(row, text="변환하기", style="Primary.TButton", command=self.convert)
        self.convert_button.pack(side="left")
        self.status = tk.Label(row, text="", bg=BG, fg=STRONG, font=("Malgun Gothic", 10, "bold"), anchor="w")
        self.status.pack(side="left", padx=(12, 0), fill="x", expand=True)
        self.progress = ttk.Progressbar(body, mode="determinate", value=0)
        self.progress.pack(fill="x", pady=(8, 0))

        # phone
        c = self.card(body, "폰으로 보내기 (Android)", "USB 디버깅 또는 무선 디버깅")
        row = ttk.Frame(c, style="Card.TFrame", borderwidth=0)
        row.pack(fill="x", pady=(8, 0))
        self.device = tk.StringVar()
        self.devices = ttk.Combobox(row, textvariable=self.device, state="readonly", width=34)
        self.devices.pack(side="left")
        ttk.Button(row, text="새로고침", command=self.refresh_devices).pack(side="left", padx=(8, 0))
        ttk.Button(row, text="무선 연결…", command=self.wireless).pack(side="left", padx=(6, 0))
        self.send_common = tk.BooleanVar(value=False)
        ttk.Checkbutton(c, text="공통 모델도 보내기 (폰에 없을 때만 · 약 1.1 GB)", variable=self.send_common,
                        style="Card.TCheckbutton").pack(anchor="w", pady=(8, 0))
        row = ttk.Frame(c, style="Card.TFrame", borderwidth=0)
        row.pack(fill="x", pady=(8, 0))
        self.send_button = ttk.Button(row, text="골라 둔 목소리 보내기", command=self.send)
        self.send_button.pack(side="left")
        self.phone_info = ttk.Label(row, text="", style="Hint.TLabel", wraplength=480, justify="left")
        self.phone_info.pack(side="left", padx=(12, 0))

        # log
        self.log = tk.Text(body, height=5, bg=PANEL, fg=MUTED, relief="flat", highlightthickness=1,
                           highlightbackground=LINE, font=("Consolas", 9), wrap="none")
        self.log.pack(fill="both", expand=True, pady=(10, 0))

    def busy(self, on):
        """The bar runs while something works and stays empty otherwise."""
        if on:
            self.progress.configure(mode="indeterminate")
            self.progress.start(12)
        else:
            self.progress.stop()
            self.progress.configure(mode="determinate", value=0)

    # ------------------------------------------------------------------ voices
    def add_voice(self, path):
        if path.suffix.lower() != ".pth" or any(v["path"] == path for v in self.voices):
            return
        v = {"path": path, "name": re.sub(r"[^\w\-]+", "_", path.stem), "info": "확인 중…", "ok": None}
        self.voices.append(v)
        self.redraw_list(len(self.voices) - 1)
        threading.Thread(target=self.inspect, args=(v,), daemon=True).start()

    def inspect(self, v):
        """Version, rate and pitch from the .pth (torch loads it without running anything)."""
        try:
            import torch
            cpt = torch.load(str(v["path"]), map_location="cpu", weights_only=True)
            if "weight" not in cpt or "config" not in cpt:
                v["info"], v["ok"] = "RVC 목소리 모델이 아니에요 (학습 중간 저장본이면 weights/의 작은 .pth를 고르세요)", False
            else:
                version, rate, f0 = cpt.get("version", "v1"), cpt["config"][-1], cpt.get("f0", 1)
                v["ok"] = version == "v2" and rate == 48000 and f0 == 1
                v["info"] = f"{version} · {rate // 1000} kHz · 음높이 {'있음' if f0 == 1 else '없음'}" + ("" if v["ok"] else " → 아직 안 돼요 (v2 · 48 kHz · 음높이 있음만)")
        except Exception as e:
            v["info"], v["ok"] = f"읽지 못했어요: {e}", False
        self.events.put(("list", None))

    def redraw_list(self, select=None):
        keep = self.list.curselection()
        self.list.delete(0, "end")
        for v in self.voices:
            mark = {True: "✓", False: "✕", None: "…"}[v["ok"]] + (" 변환됨" if v.get("done") else "")
            self.list.insert("end", f"{v['path'].name}  →  {v['name']}   {mark}")
        index = select if select is not None else (keep[0] if keep else None)
        if index is not None and index < len(self.voices):
            self.list.selection_set(index)
        self.show_selected()

    def selected(self):
        sel = self.list.curselection()
        return self.voices[sel[0]] if sel else None

    def show_selected(self):
        v = self.selected()
        self._renaming = True
        self.name.set(v["name"] if v else "")
        self._renaming = False
        self.info.configure(text=v["info"] if v else "", style="Hint.TLabel" if not v or v["ok"] is not False else "Bad.TLabel")

    def rename_selected(self):
        if getattr(self, "_renaming", False):
            return
        v = self.selected()
        if v and self.name.get().strip():
            v["name"] = re.sub(r"[^\w\-]+", "_", self.name.get().strip())
            i = self.voices.index(v)
            mark = {True: "✓", False: "✕", None: "…"}[v["ok"]] + (" 변환됨" if v.get("done") else "")
            self.list.delete(i)
            self.list.insert(i, f"{v['path'].name}  →  {v['name']}   {mark}")
            self.list.selection_set(i)

    def pick_voices(self):
        for f in filedialog.askopenfilenames(title="목소리 모델 고르기", filetypes=[("RVC 모델", "*.pth")]):
            self.add_voice(Path(f))

    def remove_voice(self):
        v = self.selected()
        if v and not self.running:
            self.voices.remove(v)
            self.redraw_list()

    # ------------------------------------------------------------------ calibration
    def pick_calib(self):
        d = filedialog.askdirectory(title="보정 녹음 폴더", initialdir=self.calib.get())
        if d:
            self.calib.set(d)
            self.refresh_calib()

    def refresh_calib(self):
        n, seconds = recordings(self.calib.get())
        if n:
            self.calib_info.configure(text=f"WAV {n}개 · {int(seconds // 60)}분 {int(seconds % 60)}초", style="Ok.TLabel")
        else:
            self.calib_info.configure(text="WAV 녹음이 없어요: NPU 정수 디코더는 건너뛰어요", style="Bad.TLabel")

    @staticmethod
    def open_folder(path):
        Path(path).mkdir(parents=True, exist_ok=True)
        os.startfile(path)

    # ------------------------------------------------------------------ convert
    def say(self, text):
        self.log.insert("end", text + "\n")
        self.log.see("end")

    def convert(self):
        todo = [v for v in self.voices if v["ok"]]
        if not todo and not self.common.get():
            messagebox.showinfo("변환", "변환할 수 있는 목소리 모델(.pth)을 추가하세요.")
            return
        self.refresh_calib()
        self.running = True
        self.convert_button.state(["disabled"])
        self.send_button.state(["disabled"])
        self.busy(True)
        # tkinter is not for worker threads: everything they need is read here, on the window's thread
        opts = dict(out=Path(self.out.get()), calib=self.calib.get(), common=self.common.get(), npu=self.npu.get(),
                    ncnn=self.ncnn.get())
        threading.Thread(target=self.convert_all, args=(todo, opts), daemon=True).start()

    def convert_all(self, todo, opts):
        out, common = opts["out"], opts["common"]
        failed = []
        jobs = todo or [None]
        for i, v in enumerate(jobs):
            cmd = [str(PY), "-u", str(HERE / "rvc2phone.py")]
            if v:
                cmd += [str(v["path"]), "--name", v["name"]]
            cmd += ["--out", str(out), "--calib", opts["calib"]]
            if common and i == 0:
                cmd.append("--common")
            if not opts["npu"]:
                cmd.append("--no-npu")
            if not opts["ncnn"]:
                cmd.append("--no-ncnn")
            label = v["name"] if v else "공통 모델"
            self.events.put(("status", f"{label} ({i + 1}/{len(jobs)}): 시작"))
            env = dict(os.environ, PYTHONIOENCODING="utf-8")
            proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, encoding="utf-8",
                                    errors="replace", cwd=str(HERE), env=env, creationflags=NO_WINDOW)
            for line in proc.stdout:
                line = line.rstrip()
                if not line or line.startswith(" ") and "pieces" not in line or "Warning" in line or "warn(" in line:
                    continue  # pnnx is chatty, and PyTorch warns about its exporter
                self.events.put(("log", line))
                for marker, text in STAGES:
                    if marker in line:
                        self.events.put(("status", f"{label} ({i + 1}/{len(jobs)}): {text}"))
            if proc.wait() != 0:
                failed.append(label)
                self.events.put(("log", f"!! {label}: 변환 실패 (위 기록 참고)"))
            elif v:
                v["done"] = True
                self.events.put(("list", None))
        self.events.put(("finished", failed))

    # ------------------------------------------------------------------ phone
    def refresh_devices(self):
        try:
            code, text = adb("devices", "-l", timeout=20)
        except Exception as e:
            self.devices["values"] = []
            self.phone_info.configure(text=f"{e}. Android SDK platform-tools를 설치하거나 adb.exe를 platform-tools 폴더에 두세요")
            return
        found = []
        for line in text.splitlines()[1:]:
            parts = line.split()
            if len(parts) >= 2 and parts[1] == "device":
                model = next((p.split(":", 1)[1] for p in parts if p.startswith("model:")), "")
                found.append(f"{parts[0]}  {model}")
        self.devices["values"] = found
        if found:
            self.devices.current(0)
            self.phone_info.configure(text="")
        else:
            self.device.set("")
            self.phone_info.configure(text="연결된 폰이 없어요. USB 디버깅을 켜고 꽂거나, 무선 연결을 쓰세요.")

    def wireless(self):
        win = tk.Toplevel(self, bg=PANEL)
        win.title("무선 디버깅 연결")
        win.transient(self)
        pad = {"padx": 12, "pady": 4, "sticky": "w"}
        tk.Label(win, bg=PANEL, fg=MUTED, justify="left", wraplength=420,
                 text="폰: 설정 › 개발자 옵션 › 무선 디버깅. 처음 한 번은 '페어링 코드로 기기 페어링'의 주소와 6자리 코드로 "
                      "페어링하고, 그다음은 화면의 'IP 주소 및 포트'로 연결해요.").grid(row=0, column=0, columnspan=3, padx=12, pady=(12, 6))
        pair_addr, code, conn_addr = tk.StringVar(), tk.StringVar(), tk.StringVar()
        for r, (label, var) in enumerate([("페어링 주소 (IP:포트)", pair_addr), ("페어링 코드", code), ("연결 주소 (IP:포트)", conn_addr)], 1):
            tk.Label(win, text=label, bg=PANEL, fg=TEXT).grid(row=r, column=0, **pad)
            ttk.Entry(win, textvariable=var, width=26).grid(row=r, column=1, **pad)
        result = tk.Label(win, text="", bg=PANEL, fg=STRONG, wraplength=420, justify="left")
        result.grid(row=5, column=0, columnspan=3, padx=12, pady=(6, 12), sticky="w")

        def run(*args):
            try:
                _, text = adb(*args, timeout=30)
            except Exception as e:
                text = str(e)
            result.configure(text=text[-300:])
            self.refresh_devices()

        ttk.Button(win, text="페어링", command=lambda: run("pair", pair_addr.get().strip(), code.get().strip())).grid(row=2, column=2, padx=12)
        ttk.Button(win, text="연결", command=lambda: run("connect", conn_addr.get().strip())).grid(row=3, column=2, padx=12)

    def send(self):
        serial = self.device.get().split()[0] if self.device.get() else ""
        if not serial:
            messagebox.showinfo("폰으로 보내기", "연결된 폰을 고르세요.")
            return
        v = self.selected()
        out = Path(self.out.get())
        voices = [v] if v else [x for x in self.voices if x.get("done")]
        voices = [x for x in voices if (out / x["name"] / f"{x['name']}.enc.onnx").is_file()]
        if not voices and not self.send_common.get():
            messagebox.showinfo("폰으로 보내기", "보낼 변환 결과가 없어요. 먼저 변환하세요.")
            return
        self.running = True
        self.send_button.state(["disabled"])
        self.convert_button.state(["disabled"])
        self.busy(True)
        threading.Thread(target=self.send_all, args=(serial, voices, out, self.send_common.get()), daemon=True).start()

    def send_all(self, serial, voices, out, with_common):
        try:
            code, text = adb("-s", serial, "shell", "pm", "path", PACKAGE, timeout=20)
            if "package:" not in text:
                raise RuntimeError("폰에 Voice Studio 앱이 없어요. 앱을 먼저 설치하세요.")
            files = []
            for v in voices:
                folder = out / v["name"]
                for part in ("enc.onnx", "dec.onnx", "dec.w8a16.onnx", "dec.ncnn.param", "dec.ncnn.bin"):
                    f = folder / f"{v['name']}.{part}"
                    if f.is_file():
                        files.append((f, f.name))
                if (folder / "settings.json").is_file():  # seeds a voice the phone does not have yet; never overwrites
                    files.append((folder / "settings.json", f"{v['name']}.settings.json"))
            if with_common:
                code, have = adb("-s", serial, "shell", f"ls {REMOTE}/common", timeout=20)
                for name in ("hubert.onnx", "rmvpe.onnx", "hubert.htp.onnx"):
                    f = out / "common" / name
                    if f.is_file() and name not in have.split():
                        files.append((f, name))
            adb("-s", serial, "shell", "am", "start", "-n", f"{PACKAGE}/.MainActivity", timeout=20)  # makes its folder
            total = len(files)
            for i, (f, remote) in enumerate(files, 1):
                self.events.put(("status", f"폰으로 보내는 중 ({i}/{total}) {remote} · {f.stat().st_size / 1e6:.0f} MB"))
                code, text = adb("-s", serial, "push", str(f), f"{REMOTE}/{remote}", timeout=1800)
                if code != 0:
                    raise RuntimeError(f"{remote}: {text[-200:]}")
                self.events.put(("log", f"보냄: {remote}"))
            # the app sorts the loose files into place when it opens
            adb("-s", serial, "shell", "am", "force-stop", PACKAGE, timeout=20)
            adb("-s", serial, "shell", "am", "start", "-n", f"{PACKAGE}/.MainActivity", timeout=20)
            time.sleep(3)
            code, left = adb("-s", serial, "shell", f"ls {REMOTE}", timeout=20)
            # (a voice's settings.json only seeds a new voice: the app drops it when the phone has the voice already)
            stuck = [name for _, name in files if name in left.split() and not name.endswith(".settings.json")]
            if stuck:
                msg = "앱이 아직 파일을 옮기지 못했어요 (앱을 한 번 열면 옮겨요): " + ", ".join(stuck[:4])
            else:
                msg = f"폰에 넣었어요: {', '.join(v['name'] for v in voices) or '공통 모델'}. 앱의 설정에서 목소리를 고르세요."
            self.events.put(("sent", msg))
        except Exception as e:
            self.events.put(("sent", f"보내지 못했어요: {e}"))

    # ------------------------------------------------------------------ events from the worker threads
    def pump(self):
        try:
            while True:
                kind, value = self.events.get_nowait()
                if kind == "log":
                    self.say(value)
                elif kind == "status":
                    self.status.configure(text=value)
                elif kind == "list":
                    self.redraw_list()
                elif kind in ("finished", "sent"):
                    self.running = False
                    self.busy(False)
                    self.convert_button.state(["!disabled"])
                    self.send_button.state(["!disabled"])
                    if kind == "finished":
                        self.status.configure(text="변환 실패: " + ", ".join(value) if value else
                                              "변환 끝: 출력 폴더를 열거나 아래에서 폰으로 보내세요")
                        self.common.set(not (Path(self.out.get()) / "common/hubert.onnx").is_file())
                    else:
                        self.status.configure(text=value)
                        self.phone_info.configure(text=value)
        except queue.Empty:
            pass
        self.after(100, self.pump)


def main():
    App([a for a in sys.argv[1:] if a.lower().endswith(".pth")]).mainloop()


if __name__ == "__main__":
    main()

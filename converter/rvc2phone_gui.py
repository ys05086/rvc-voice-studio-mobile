"""Four-step, bilingual model preparation and Android transfer UI."""

import json
import locale
import os
from pathlib import Path
import queue
import re
import sys
import threading
import time
import tkinter as tk
from tkinter import filedialog, messagebox, ttk
import workflow as work

HERE = Path(__file__).resolve().parent
PY = (
    Path(sys.executable).with_name("python.exe")
    if sys.executable.endswith("pythonw.exe")
    else Path(sys.executable)
)
BG, PANEL, LINE, INK, MUTED = "#F7F4FA", "#FFFEFD", "#E9E3F0", "#3F3A4A", "#857B94"
ACCENT, STRONG, LAVENDER = "#9E83BC", "#7C62A0", "#EAE3F5"
PREFS = (
    Path(os.environ.get("LOCALAPPDATA", Path.home()))
    / "VoiceStudioConverter/preferences.json"
)
PROFILES = [
    ("일반 Android · CPU + GPU", "General Android · CPU + GPU"),
    ("Snapdragon · CPU + GPU + NPU", "Snapdragon · CPU + GPU + NPU"),
    ("CPU만", "CPU only"),
]
STATES = {
    "checking": ("검사 중", "Checking"),
    "ready": ("지원됨", "Supported"),
    "unsupported": ("지원하지 않는 형식", "Unsupported"),
    "done": ("완료", "Done"),
    "failed": ("실패 · 재시도 가능", "Failed · retry available"),
    "cancelled": ("취소됨", "Cancelled"),
}
STAGES = {
    "hubert": ("HuBERT 다운로드·내보내기", "Downloading/exporting HuBERT"),
    "rmvpe": ("RMVPE 다운로드·내보내기", "Downloading/exporting RMVPE"),
    "hubert_npu": ("NPU용 HuBERT 준비", "Preparing NPU HuBERT"),
    "onnx": ("ONNX 내보내기·검증", "Exporting/verifying ONNX"),
    "gpu": ("GPU 디코더 생성", "Building GPU decoder"),
    "npu": ("NPU 디코더 보정", "Calibrating NPU decoder"),
    "archive": ("ZIP 생성·검증", "Creating/verifying ZIP"),
}
ERRORS = {
    "app_update_required": ("32kHz 모델을 보내려면 폰 앱을 0.4.1 이상으로 업데이트하세요.", "Update the phone app to 0.4.1 or later before sending a 32 kHz model."),
    "bad_name": (
        "이름에는 문자·숫자·밑줄·하이픈을 사용하세요. 예약된 이름은 쓸 수 없어요.",
        "Use letters, numbers, underscores or hyphens. Reserved names cannot be used.",
    ),
    "duplicate_name": (
        "모델마다 서로 다른 이름을 지정하세요.",
        "Give each model a different name.",
    ),
    "checkpoint": (
        "학습 중간 저장본 대신 추론용 weights의 .pth를 선택하세요.",
        "Choose an inference weights .pth, not a training checkpoint.",
    ),
    "adb_missing": (
        "Android SDK Platform Tools를 설치하거나 converter/platform-tools에 넣으세요.",
        "Install Android SDK Platform Tools or place it in converter/platform-tools.",
    ),
    "adb_failed": (
        "기기 연결과 폰의 디버깅 승인을 확인하세요.",
        "Check the device connection and debugging authorization.",
    ),
    "app_missing": (
        "폰에 Voice Studio를 설치하고 한 번 열어 주세요.",
        "Install Voice Studio on the phone and open it once.",
    ),
    "incomplete": (
        "완성되지 않은 출력입니다. 다시 변환해 주세요.",
        "Output is incomplete. Convert it again.",
    ),
    "export_failed": (
        "변환 실패. 기록을 확인한 뒤 재시도하세요.",
        "Conversion failed. Check the log, then retry.",
    ),
    "common_required": (
        "공통 모델을 먼저 준비해 주세요.",
        "Prepare shared models first.",
    ),
    "output_link": (
        "출력 경로는 심볼릭 링크가 아닌 일반 폴더를 사용하세요.",
        "Use a regular output folder, not a symbolic link.",
    ),
    "recover_output": (
        "기존 결과를 복구하지 못했어요. 표시된 작업 폴더를 보존하고 확인하세요.",
        "Previous results could not be restored. Preserve and check the displayed work folder.",
    ),
    "no_device": ("연결된 폰을 선택하세요.", "Select a connected phone."),
    "unauthorized": (
        "폰에서 이 PC의 디버깅을 허용하세요.",
        "Authorize this PC on the phone.",
    ),
    "offline": (
        "폰이 오프라인입니다. 다시 연결하세요.",
        "Phone is offline. Reconnect it.",
    ),
}


class App(tk.Tk):
    def __init__(self, voices=(), *, discover=True, preferences=PREFS):
        super().__init__()
        self.preferences = Path(preferences)
        try:
            saved = json.loads(self.preferences.read_text(encoding="utf-8"))
            if not isinstance(saved, dict):
                saved = {}
        except (OSError, ValueError):
            saved = {}
        default = (
            "ko" if (locale.getlocale()[0] or "").lower().startswith("ko") else "en"
        )
        self.language = saved.get("language", default)
        if self.language not in ("ko", "en"):
            self.language = default
        self.events, self.voices, self.results, self.found_devices = (
            queue.Queue(),
            [],
            [],
            [],
        )
        self.failed, self.log_lines = set(), []
        self.running, self.runner, self.started = None, None, None
        self.close_pending = self._renaming = self.device_busy = False
        self.inspections = self.calib_generation = 0
        self.out = tk.StringVar(value=saved.get("out", str(HERE / "out")))
        self.calib = tk.StringVar(value=saved.get("calib", str(HERE / "calib")))
        self.profile = tk.IntVar(
            value=saved.get("profile", 0) if saved.get("profile", 0) in (0, 1, 2) else 0
        )
        self.common, self.send_common, self.name = (
            tk.BooleanVar(value=True),
            tk.BooleanVar(value=False),
            tk.StringVar(),
        )
        self.name.trace_add("write", self.rename_selected)
        self.configure(bg=BG)
        self.geometry("960x850")
        self.minsize(800, 660)
        self.protocol("WM_DELETE_WINDOW", self.close)
        self.style()
        self.build()
        self.bind("<MouseWheel>", self.wheel)
        self.after(75, self.pump)
        self.out.trace_add(
            "write", lambda *_: self.debounce("out", self.refresh_results)
        )
        self.calib.trace_add(
            "write", lambda *_: self.debounce("calib", self.refresh_calib)
        )
        for path in voices:
            self.add_voice(Path(path))
        self.refresh_calib()
        self.refresh_results()
        if discover:
            self.refresh_devices()

    def t(self, ko, en):
        return en if self.language == "en" else ko

    def style(self):
        s = ttk.Style(self)
        s.theme_use("clam")
        s.configure(".", background=BG, foreground=INK, font=("Segoe UI", 10))
        s.configure("TNotebook", borderwidth=0)
        s.configure("TNotebook.Tab", padding=(15, 10), background=LAVENDER)
        s.map(
            "TNotebook.Tab",
            background=[("selected", PANEL)],
            foreground=[("selected", STRONG)],
        )
        s.configure(
            "TButton",
            background=LAVENDER,
            foreground=INK,
            bordercolor=LINE,
            padding=(12, 7),
        )
        s.map("TButton", background=[("active", "#DED3EF"), ("disabled", "#F0ECF5")])
        s.configure(
            "Primary.TButton", background=ACCENT, foreground="white", padding=(18, 10)
        )
        s.map(
            "Primary.TButton", background=[("active", STRONG), ("disabled", "#CFC3DE")]
        )
        s.configure("TProgressbar", background=ACCENT, troughcolor=LAVENDER)
        s.configure("TEntry", fieldbackground="white")

    def label(self, parent, ko="", en="", *, big=False, muted=False):
        w = tk.Label(
            parent,
            text=self.t(ko, en),
            bg=parent.cget("bg"),
            fg=MUTED if muted else INK,
            anchor="w",
            justify="left",
            font=("Segoe UI", 11 if big else 10, "bold" if big else "normal"),
            wraplength=730,
        )
        w.pack(fill="x", pady=(0, 8))
        return w

    def button(self, parent, ko, en, action, *, primary=False, lock=True, side="left"):
        w = ttk.Button(
            parent,
            text=self.t(ko, en),
            command=action,
            style="Primary.TButton" if primary else "TButton",
        )
        w.pack(side=side, padx=(0, 8))
        if lock:
            self.inputs.append((w, "normal"))
        return w

    def row(self, parent):
        w = tk.Frame(parent, bg=parent.cget("bg"))
        w.pack(fill="x", pady=(3, 7))
        return w

    def card(self, parent, ko, en):
        w = tk.Frame(
            parent,
            bg=PANEL,
            highlightbackground=LINE,
            highlightthickness=1,
            padx=18,
            pady=14,
        )
        w.pack(fill="x", pady=(0, 12))
        self.label(w, ko, en, big=True)
        return w

    def entry(self, parent, variable):
        w = ttk.Entry(parent, textvariable=variable)
        w.pack(side="left", fill="x", expand=True, padx=(0, 8))
        self.inputs.append((w, "normal"))
        return w

    def build(self, tab=0):
        self.inputs = []
        self.title(
            self.t("Voice Studio · 모델 변환 도구", "Voice Studio · Model converter")
        )
        top = tk.Frame(self, bg=BG, padx=22, pady=16)
        top.pack(fill="x")
        tk.Label(
            top, text="voice / studio", bg=BG, fg=STRONG, font=("Segoe UI", 22, "bold")
        ).pack(side="left")
        self.language_box = ttk.Combobox(
            top, state="readonly", values=["한국어", "English"], width=11
        )
        self.language_box.current(self.language == "en")
        self.language_box.pack(side="right")
        self.language_box.bind("<<ComboboxSelected>>", self.change_language)
        self.inputs.append((self.language_box, "readonly"))
        self.button(top, "사용법", "Guide", self.help, lock=False, side="right")
        self.label(
            self,
            "    목소리 모델을 준비하고, 폰으로 보내세요.",
            "    Prepare a voice model. Take it to your phone.",
            muted=True,
        )
        self.tabs = ttk.Notebook(self)
        self.tabs.pack(fill="both", expand=True, padx=20)
        self.pages, self.canvases = [], []
        for ko, en in [
            ("1  모델 선택", "1  Choose models"),
            ("2  옵션 확인", "2  Prepare"),
            ("3  변환", "3  Convert"),
            ("4  폰으로 보내기", "4  Send to phone"),
        ]:
            host = tk.Frame(self.tabs, bg=BG)
            self.tabs.add(host, text=self.t(ko, en))
            canvas = tk.Canvas(host, bg=BG, highlightthickness=0)
            scroll = ttk.Scrollbar(host, orient="vertical", command=canvas.yview)
            scroll.pack(side="right", fill="y")
            canvas.pack(side="left", fill="both", expand=True)
            canvas.configure(yscrollcommand=scroll.set)
            body = tk.Frame(canvas, bg=BG, padx=2, pady=14)
            window = canvas.create_window((0, 0), window=body, anchor="nw")
            body.bind(
                "<Configure>",
                lambda e, c=canvas: c.configure(scrollregion=c.bbox("all")),
            )
            canvas.bind(
                "<Configure>",
                lambda e, c=canvas, w=window: c.itemconfigure(w, width=e.width),
            )
            self.pages.append(body)
            self.canvases.append(canvas)
        self.build_models()
        self.build_options()
        self.build_convert()
        self.build_phone()
        footer = tk.Frame(self, bg=BG, padx=22, pady=12)
        footer.pack(fill="x")
        self.status = self.label(
            footer,
            "준비되면 변환을 시작하세요.",
            "Start conversion when you are ready.",
        )
        row = self.row(footer)
        self.stage_label = tk.Label(row, text="", bg=BG, fg=STRONG, anchor="w")
        self.stage_label.pack(side="left", fill="x", expand=True)
        self.elapsed = tk.Label(row, text="", bg=BG, fg=MUTED)
        self.elapsed.pack(side="right")
        self.cancel_button = self.button(
            row, "변환 취소", "Cancel conversion", self.cancel, lock=False, side="right"
        )
        self.cancel_button.state(["disabled"])
        self.progress = ttk.Progressbar(footer, mode="indeterminate")
        self.progress.pack(fill="x", pady=(3, 7))
        self.tabs.bind("<<NotebookTabChanged>>", lambda e: self.review())
        self.tabs.select(tab)
        self.redraw_list()
        self.display_devices()
        self.review()

    def build_models(self):
        c = self.card(self.pages[0], "목소리 모델 (.pth)", "Voice models (.pth)")
        self.label(
            c,
            "RVC v2 · 32/48 kHz · 음높이(f0) 있는 추론용 모델을 선택하세요. 32kHz 모델은 Android 앱 0.4.1 이상이 필요합니다. 40kHz는 아직 지원하지 않습니다.",
            "Choose RVC v2, 32/48 kHz inference models with pitch (f0). 32 kHz models require Android app 0.4.1 or later. 40 kHz is not supported yet.",
            muted=True,
        )
        self.model_list = tk.Listbox(
            c,
            height=7,
            bg="white",
            fg=INK,
            selectbackground=LAVENDER,
            selectforeground=INK,
            exportselection=False,
            relief="flat",
            font=("Segoe UI", 10),
        )
        self.model_list.pack(fill="x", pady=8)
        self.model_list.bind("<<ListboxSelect>>", lambda e: self.show_selected())
        self.inputs.append((self.model_list, "normal"))
        row = self.row(c)
        self.button(row, "모델 추가", "Add models", self.pick_voices)
        self.button(row, "선택 모델 빼기", "Remove selected", self.remove_voice)
        self.label(c, "앱에서 쓸 이름", "Name in the app")
        self.entry(self.row(c), self.name)
        self.label(
            c,
            "문자·숫자·밑줄·하이픈을 사용하고, 모델마다 다른 이름을 지정하세요.",
            "Use letters, numbers, underscores or hyphens. Give each model a unique name.",
            muted=True,
        )
        self.model_info = self.label(c, muted=True)
        self.button(
            self.row(self.pages[0]),
            "다음 단계 →",
            "Next step →",
            lambda: self.tabs.select(1),
            primary=True,
        )

    def build_options(self):
        c = self.card(
            self.pages[1], "어떤 폰에서 사용하나요?", "Which phone will you use?"
        )
        for index, names in enumerate(PROFILES):
            w = tk.Radiobutton(
                c,
                text=self.t(*names),
                variable=self.profile,
                value=index,
                bg=PANEL,
                fg=INK,
                activebackground=PANEL,
                selectcolor=LAVENDER,
                anchor="w",
                command=self.review,
            )
            w.pack(fill="x", pady=2)
            self.inputs.append((w, "normal"))
        self.label(
            c,
            "일반 Android가 기본값입니다. NPU에는 보정 녹음이 필요해요. 실제 기기에서 성능과 호환성을 확인하세요.",
            "General Android is the default. NPU needs calibration audio. Verify performance and compatibility on your phone.",
            muted=True,
        )
        self.detect_button = self.button(
            self.row(c),
            "연결된 폰으로 옵션 추천",
            "Recommend from connected phone",
            self.detect,
        )
        self.recommendation = self.label(c, muted=True)
        c = self.card(
            self.pages[1],
            "보정 녹음 · NPU 선택 시 필요",
            "Calibration audio · Required for NPU",
        )
        self.label(
            c,
            "모델을 사용할 사람의 평소 말소리 WAV를 몇 분 준비하세요. 실제 마이크 입력을 보정합니다.",
            "Use a few minutes of WAV recordings of the person using the model. These calibrate microphone input.",
            muted=True,
        )
        row = self.row(c)
        self.entry(row, self.calib)
        self.button(
            row, "찾아보기…", "Browse…", lambda: self.pick_directory(self.calib)
        )
        self.calib_info = self.label(c, muted=True)
        c = self.card(self.pages[1], "결과를 저장할 폴더", "Output folder")
        row = self.row(c)
        self.entry(row, self.out)
        self.button(row, "찾아보기…", "Browse…", lambda: self.pick_directory(self.out))
        self.button(row, "열기", "Open", self.open_output)
        w = tk.Checkbutton(
            c,
            text=self.t("없는 공통 모델도 준비하기", "Build missing shared models"),
            variable=self.common,
            bg=PANEL,
            activebackground=PANEL,
            command=self.review,
        )
        w.pack(anchor="w")
        self.inputs.append((w, "normal"))
        self.common_info = self.label(c, muted=True)
        self.label(
            c,
            "공통 모델은 모든 목소리가 함께 씁니다. 처음에는 다운로드와 추가 시간이 필요해요. NPU 보정에 필요하면 자동 준비합니다.",
            "All voices use shared models. First setup needs downloads and extra time. Missing models are prepared automatically for NPU calibration.",
            muted=True,
        )
        self.button(
            self.row(self.pages[1]),
            "다음 단계 →",
            "Next step →",
            lambda: self.tabs.select(2),
            primary=True,
        )

    def build_convert(self):
        c = self.card(self.pages[2], "시작하기 전에", "Before you start")
        self.review_label = self.label(c)
        self.label(
            c,
            "완성된 출력만 결과 폴더에 반영합니다. 취소나 실패 시 기존 완성본을 유지해요.",
            "Only completed output replaces the result. Cancellation or failure preserves existing completed models.",
            muted=True,
        )
        row = self.row(c)
        self.convert_button = self.button(
            row, "변환 시작", "Start conversion", self.convert, primary=True
        )
        self.retry_button = self.button(
            row,
            "실패·취소한 작업 재시도",
            "Retry failed / cancelled jobs",
            lambda: self.convert(retry=True),
        )
        self.label(
            c,
            "재시도는 성공한 모델을 건너뛰고, 현재 옵션으로 실패·취소한 작업을 처음부터 다시 실행해요.",
            "Retry skips successful models and restarts failed/cancelled jobs from the beginning using the current options.",
            muted=True,
        )
        c = self.card(self.pages[2], "자세한 기록", "Details & log")
        self.log = tk.Text(
            c, height=8, bg=BG, fg=INK, relief="flat", wrap="word", font=("Consolas", 9)
        )
        self.log.pack(fill="x", pady=5)
        self.log.insert(
            "end", "\n".join(self.log_lines) + ("\n" if self.log_lines else "")
        )
        self.log.configure(state="disabled")
        self.label(
            c,
            "기록에는 로컬 파일 경로와 모델명이 포함될 수 있어요.",
            "Logs may contain local file paths and model names.",
            muted=True,
        )
        self.button(self.row(c), "기록 저장…", "Save log…", self.save_log, lock=False)
        row = self.row(self.pages[2])
        self.button(row, "결과 폴더 열기", "Open results", self.open_output)
        self.button(
            row,
            "폰으로 보내기 →",
            "Send to phone →",
            lambda: self.tabs.select(3),
            primary=True,
        )

    def build_phone(self):
        c = self.card(self.pages[3], "전송할 폰", "Destination phone")
        self.label(
            c,
            "USB 디버깅으로 연결하고 폰에서 이 PC를 허용하세요. 무선 디버깅도 사용할 수 있어요.",
            "Connect with USB debugging and authorize this PC, or use wireless debugging.",
            muted=True,
        )
        row = self.row(c)
        self.device_box = ttk.Combobox(row, state="readonly", width=30)
        self.device_box.pack(side="left", fill="x", expand=True, padx=(0, 8))
        self.inputs.append((self.device_box, "readonly"))
        self.device_box.bind("<<ComboboxSelected>>", lambda e: self.device_selected())
        self.refresh_button = self.button(
            row, "새로고침", "Refresh", self.refresh_devices
        )
        self.button(row, "무선 연결…", "Wireless…", self.wireless)
        self.phone_info = self.label(c, muted=True)
        c = self.card(self.pages[3], "완성본 선택", "Choose completed results")
        self.label(
            c,
            "전송 전에 폰의 녹음·통화를 끝내세요. 전송 중 앱을 종료하고 완료 후 다시 엽니다. 같은 이름의 모델을 교체하며 폰의 목소리 설정은 유지합니다.",
            "End recording/calls before transferring. Transfer stops the phone app and reopens it afterward. Models with the same name are replaced; phone voice settings are preserved.",
            muted=True,
        )
        self.result_list = tk.Listbox(
            c,
            selectmode="extended",
            exportselection=False,
            height=5,
            bg="white",
            fg=INK,
            selectbackground=LAVENDER,
            selectforeground=INK,
            relief="flat",
            font=("Segoe UI", 10),
        )
        self.result_list.pack(fill="x", pady=5)
        self.inputs.append((self.result_list, "normal"))
        self.result_info = self.label(c, muted=True)
        w = tk.Checkbutton(
            c,
            text=self.t(
                "폰에 없는 공통 모델도 보내기",
                "Also send shared models missing on the phone",
            ),
            variable=self.send_common,
            bg=PANEL,
            activebackground=PANEL,
        )
        w.pack(anchor="w")
        self.inputs.append((w, "normal"))
        row = self.row(c)
        self.send_button = self.button(
            row, "선택한 모델 보내기", "Send selected models", self.send, primary=True
        )
        self.button(row, "완성본 다시 찾기", "Refresh results", self.refresh_results)
        self.label(
            c,
            "디버깅 없이 쓰려면 결과 ZIP을 폰으로 복사하고 앱에서 불러오세요. 공통 모델은 별도로 준비합니다.",
            "Without debugging, copy the result ZIP to your phone and import it in the app. Shared models are prepared separately.",
            muted=True,
        )

    def wheel(self, event):
        if not isinstance(event.widget, (tk.Listbox, tk.Text, ttk.Combobox)):
            self.canvases[self.tabs.index("current")].yview_scroll(
                -int(event.delta / 120), "units"
            )

    def change_language(self, _event=None):
        if self.running:
            return
        tab, selection = self.tabs.index("current"), self.model_list.curselection()
        self.language = "en" if self.language_box.current() else "ko"
        for widget in self.winfo_children():
            widget.destroy()
        self.build(tab)
        if selection:
            self.model_list.selection_set(selection[0])
            self.show_selected()
        self.refresh_calib()
        self.refresh_results()
        self.save_preferences()

    def save_preferences(self):
        try:
            self.preferences.parent.mkdir(parents=True, exist_ok=True)
            temp = self.preferences.with_suffix(".tmp")
            temp.write_text(
                json.dumps(
                    dict(
                        language=self.language,
                        out=self.out.get(),
                        calib=self.calib.get(),
                        profile=self.profile.get(),
                    ),
                    ensure_ascii=False,
                ),
                encoding="utf-8",
            )
            temp.replace(self.preferences)
        except OSError:
            pass

    def background(self, function, callback):
        def worker():
            try:
                value, error = function(), None
            except Exception as caught:
                value, error = None, caught
            self.events.put(("callback", (callback, value, error)))

        threading.Thread(target=worker, daemon=True).start()

    def error_text(self, error):
        if isinstance(error, work.WorkflowError):
            return self.t(*ERRORS.get(error.key, (error.key, error.key))) + (
                "\n" + error.detail if error.detail else ""
            )
        return str(error)

    def alert(self, value):
        messagebox.showinfo(
            self.t("확인이 필요해요", "Please check"), value, parent=self
        )

    def pick_voices(self):
        for path in filedialog.askopenfilenames(
            parent=self, filetypes=[("RVC", "*.pth")]
        ):
            self.add_voice(Path(path))

    def add_voice(self, path):
        path = path.resolve()
        if (
            self.running
            or path.suffix.lower() != ".pth"
            or any(v["path"] == path for v in self.voices)
        ):
            return
        name = re.sub(r"[^\w-]+", "_", path.stem)
        base, index = name, 2
        while name.casefold() in {v["name"].casefold() for v in self.voices}:
            name, index = f"{base}_{index}", index + 1
        voice = dict(path=path, name=name, info=None, error=None, state="checking")
        self.voices.append(voice)
        self.inspections += 1
        self.redraw_list(len(self.voices) - 1)

        def inspected(result, error):
            self.inspections -= 1
            if voice not in self.voices:
                return
            voice.update(
                info=result,
                error=error,
                state="ready" if result and result["supported"] else "unsupported",
            )
            self.redraw_list()
            self.review()

        self.background(lambda: work.inspect_checkpoint(path), inspected)

    def selected(self):
        sel = self.model_list.curselection()
        return self.voices[sel[0]] if sel else None

    def redraw_list(self, selected=None):
        keep = self.model_list.curselection()
        self.model_list.delete(0, "end")
        for v in self.voices:
            self.model_list.insert(
                "end",
                f"{v['name']}  ·  {self.t(*STATES[v['state']])}  —  {v['path'].name}",
            )
        index = selected if selected is not None else (keep[0] if keep else None)
        if index is not None and index < len(self.voices):
            self.model_list.selection_set(index)
        self.show_selected()

    def show_selected(self):
        v = self.selected()
        self._renaming = True
        self.name.set(v["name"] if v else "")
        self._renaming = False
        value = self.t(
            "모델 추가로 시작하세요. 여러 모델을 함께 변환할 수 있어요.",
            "Add models to get started. You can convert several together.",
        )
        if v:
            value = (
                self.error_text(v["error"])
                if v["error"]
                else self.t(*STATES[v["state"]])
            )
            if v["info"]:
                i = v["info"]
                value += f"\n{i['version']} · {i['rate']} Hz · f0={i['pitch']}"
        self.model_info.configure(text=value)

    def rename_selected(self, *_):
        if self._renaming or self.running:
            return
        v = self.selected()
        if v:
            v["name"] = self.name.get().strip()
            self.failed.clear()
            if v["info"] and v["info"]["supported"]:
                v["state"] = "ready"
            i = self.voices.index(v)
            self.model_list.delete(i)
            self.model_list.insert(
                i, f"{v['name']}  ·  {self.t(*STATES[v['state']])}  —  {v['path'].name}"
            )
            self.model_list.selection_set(i)
            self.review()

    def remove_voice(self):
        v = self.selected()
        if v and not self.running:
            self.voices.remove(v)
            self.failed.discard(v["name"])
            self.redraw_list()
            self.review()

    def pick_directory(self, variable):
        path = filedialog.askdirectory(
            parent=self, initialdir=variable.get() or str(HERE)
        )
        if path:
            variable.set(path)

    def debounce(self, key, action):
        timer = getattr(self, "_timer_" + key, None)
        if timer:
            self.after_cancel(timer)
        setattr(self, "_timer_" + key, self.after(350, action))

    def refresh_calib(self):
        self.calib_generation += 1
        generation, path = self.calib_generation, self.calib.get()

        def show(result, error):
            if generation != self.calib_generation:
                return
            count, seconds, invalid = result or (0, 0, 0)
            value = (
                self.t(
                    f"WAV {count}개 · {int(seconds//60)}분 {int(seconds%60)}초 · 읽기 실패 {invalid}개",
                    f"{count} WAV files · {int(seconds//60)}m {int(seconds%60)}s · {invalid} unreadable files",
                )
                if count
                else self.t(
                    "사용 가능한 WAV가 없어요. NPU를 쓰려면 녹음을 선택하세요.",
                    "No usable WAV files. Choose recordings to use NPU.",
                )
            )
            self.calib_info.configure(text=self.error_text(error) if error else value)

        self.background(lambda: work.recordings(path), show)

    def review(self):
        if not hasattr(self, "review_label"):
            return
        count = sum(bool(v["info"] and v["info"]["supported"]) for v in self.voices)
        self.review_label.configure(
            text=self.t(f"지원 모델 {count}개", f"{count} supported models")
            + "\n"
            + self.t(*PROFILES[self.profile.get()])
            + "\n"
            + self.out.get()
        )
        try:
            missing = work.missing_common(Path(self.out.get()))
            value = (
                self.t("없는 공통 모델: ", "Missing shared models: ")
                + ", ".join(missing)
                if missing
                else self.t(
                    "공통 모델 3개 준비됨 · 다시 만들지 않아요.",
                    "All 3 shared models ready · They will be reused.",
                )
            )
            self.common_info.configure(text=value)
        except OSError as error:
            self.common_info.configure(text=str(error))
        if not self.running:
            self.retry_button.state(["!disabled"] if self.failed else ["disabled"])

    def refresh_results(self):
        if self.running:
            return
        self.results = []
        self.result_list.delete(0, "end")
        out = Path(self.out.get())
        try:
            for folder in sorted(out.iterdir()) if out.is_dir() else []:
                if (
                    not folder.is_dir()
                    or folder.name.startswith(".")
                    or folder.name in ("common", "tmp")
                ):
                    continue
                try:
                    work.validate_voice(out, folder.name)
                except (OSError, ValueError, work.WorkflowError):
                    continue
                self.results.append(work.Voice(folder, folder.name))
                self.result_list.insert("end", folder.name)
            self.result_info.configure(
                text=(
                    ""
                    if self.results
                    else self.t(
                        "완성본이 없어요. 변환하거나 기존 출력 폴더를 선택하세요.",
                        "No completed results. Convert first or choose an existing output folder.",
                    )
                )
            )
        except OSError as error:
            self.result_info.configure(text=str(error))
        self.review()

    def open_output(self):
        try:
            path = Path(self.out.get()).resolve()
            path.mkdir(parents=True, exist_ok=True)
            os.startfile(path)
        except OSError as error:
            self.alert(str(error))

    def help(self):
        os.startfile(HERE / ("USAGE.md" if self.language == "en" else "USAGE.ko.md"))

    def set_busy(self, mode):
        self.running = mode
        self.started = time.monotonic() if mode else None
        for widget, normal in self.inputs:
            widget.configure(state="disabled" if mode else normal)
        self.cancel_button.state(["!disabled"] if mode == "convert" else ["disabled"])
        if mode:
            self.progress.start(12)
        else:
            self.progress.stop()
            self.progress["value"] = 0
            self.stage_label.configure(text="")
            self.review()

    def convert(self, retry=False):
        if self.running:
            return
        if self.inspections:
            self.alert(
                self.t(
                    "모델 검사가 끝날 때까지 기다려 주세요.",
                    "Wait for model inspection to finish.",
                )
            )
            return
        voices = [
            work.Voice(v["path"], v["name"])
            for v in self.voices
            if v["info"]
            and v["info"]["supported"]
            and (not retry or v["name"] in self.failed)
        ]
        options = work.Options(
            Path(self.out.get()).resolve(),
            Path(self.calib.get()).resolve(),
            gpu=self.profile.get() != 2,
            npu=self.profile.get() == 1,
            common=self.common.get() or retry and "common" in self.failed,
        )
        try:
            if not self.out.get().strip():
                raise ValueError(
                    self.t("출력 폴더를 선택하세요.", "Choose an output folder.")
                )
            work.validate_names(v.name for v in voices)
            if options.npu and voices and not work.recordings(options.calib)[0]:
                self.alert(
                    self.t(
                        "NPU에는 WAV 보정 녹음이 필요해요. 녹음을 선택하거나 일반 Android / CPU 옵션을 선택하세요.",
                        "NPU requires WAV calibration audio. Choose recordings or use General Android / CPU only.",
                    )
                )
                self.tabs.select(1)
                return
            if not voices and not (work.missing_common(options.out) and options.common):
                self.alert(
                    self.t(
                        "지원 모델을 추가하거나 공통 모델 준비를 선택하세요.",
                        "Add supported models or enable shared-model setup.",
                    )
                )
                return
            existing = [
                v.name
                for v in voices
                if (options.out / v.name).exists()
                or (options.out / f"{v.name}.zip").exists()
            ]
            if existing and not messagebox.askyesno(
                self.t("결과 교체", "Replace output"),
                self.t(
                    "새 변환 성공 후 다음 결과를 교체할까요?",
                    "Replace these results after successful conversion?",
                )
                + "\n\n"
                + "\n".join(existing),
                parent=self,
            ):
                return
        except (OSError, ValueError, work.WorkflowError) as error:
            self.alert(self.error_text(error))
            return
        self.save_preferences()
        self.runner = work.ProcessRunner()
        self.failed = set()
        self.set_busy("convert")
        self.tabs.select(2)
        self.status.configure(text=self.t("변환 준비 중…", "Preparing conversion…"))
        self.say(self.review_label.cget("text"))

        def finished(result, error):
            self.set_busy(None)
            if error:
                self.failed = {v.name for v in voices} | (
                    {"common"} if options.common else set()
                )
                self.say(self.error_text(error))
                self.status.configure(
                    text=self.t(
                        "변환 실패 · 기록을 확인하세요.",
                        "Conversion failed · Check the log.",
                    )
                )
            else:
                self.failed = set(result["failed"])
                done, bad = len(result["completed"]), len(result["failed"])
                self.status.configure(
                    text=self.t(
                        f"완료 {done}개 · 실패/미완료 {bad}개",
                        f"{done} completed · {bad} failed / incomplete",
                    )
                )
                for v in self.voices:
                    if v["name"] in result["failed"]:
                        v["state"] = "cancelled" if result["cancelled"] else "failed"
                    elif v["name"] in result["completed"]:
                        v["state"] = "done"
            self.redraw_list()
            self.refresh_results()
            if self.close_pending:
                self.destroy()

        self.background(
            lambda: work.convert_batch(voices, options, PY, self.runner, self.emit),
            finished,
        )

    def cancel(self):
        if self.running == "convert" and self.runner:
            self.runner.cancel()
            self.cancel_button.state(["disabled"])
            self.status.configure(
                text=self.t(
                    "중지하고 임시 파일을 정리하고 있어요…",
                    "Stopping and cleaning temporary files…",
                )
            )

    def refresh_devices(self):
        if self.running or self.device_busy:
            return
        self.device_busy = True
        self.refresh_button.state(["disabled"])
        self.phone_info.configure(text=self.t("연결 확인 중…", "Checking connection…"))

        def refreshed(result, error):
            self.device_busy = False
            self.found_devices = result or []
            self.display_devices()
            if error:
                self.phone_info.configure(text=self.error_text(error))
            if not self.running:
                self.refresh_button.state(["!disabled"])

        self.background(work.devices, refreshed)

    def display_devices(self):
        self.device_box["values"] = [
            f"{d['model'] or d['serial']} · {d['serial']} · {d['state']}"
            for d in self.found_devices
        ]
        if self.found_devices:
            self.device_box.current(
                next(
                    (
                        i
                        for i, d in enumerate(self.found_devices)
                        if d["state"] == "device"
                    ),
                    0,
                )
            )
        else:
            self.device_box.set("")
        self.device_selected()

    def device_selected(self):
        index = self.device_box.current()
        state = self.found_devices[index]["state"] if index >= 0 else "no_device"
        self.phone_info.configure(
            text=(
                self.t("연결됨", "Connected")
                if state == "device"
                else self.t(*ERRORS[state])
            )
        )

    def selected_device(self):
        index = self.device_box.current()
        if index < 0:
            raise work.WorkflowError("no_device")
        device = self.found_devices[index]
        if device["state"] != "device":
            raise work.WorkflowError(device["state"])
        return device["serial"]

    def detect(self):
        try:
            serial = self.selected_device()
        except work.WorkflowError as error:
            self.alert(self.error_text(error))
            self.tabs.select(3)
            return
        self.detect_button.state(["disabled"])
        self.recommendation.configure(text=self.t("확인 중…", "Checking…"))

        def detected(result, error):
            if not self.running:
                self.detect_button.state(["!disabled"])
                if result:
                    self.profile.set(1 if result == "snapdragon" else 0)
                    self.review()
            self.recommendation.configure(
                text=(
                    self.error_text(error)
                    if error
                    else self.t(
                        "추천 옵션을 적용했어요. 직접 바꿀 수도 있어요.",
                        "Recommended options applied. You can change them.",
                    )
                )
            )

        self.background(lambda: work.phone_profile(serial), detected)

    def send(self):
        if self.running:
            return
        try:
            serial = self.selected_device()
            voices = [self.results[i] for i in self.result_list.curselection()]
            if not voices and not self.send_common.get():
                self.alert(
                    self.t(
                        "보낼 완성본 또는 공통 모델을 선택하세요.",
                        "Select completed results or shared models to send.",
                    )
                )
                return
        except work.WorkflowError as error:
            self.alert(self.error_text(error))
            return
        out, shared = Path(self.out.get()).resolve(), self.send_common.get()
        self.save_preferences()
        self.set_busy("send")

        def sent(count, error):
            self.set_busy(None)
            self.status.configure(
                text=(
                    self.t(
                        "전송 실패 · 연결을 확인하고 재시도하세요. 앱이 중지된 상태일 수 있어요.",
                        "Transfer failed · Check the connection and retry. The phone app may still be stopped.",
                    )
                    if error
                    else self.t(
                        f"파일 {count}개 전송 완료. 폰 앱에서 목소리를 확인하세요.",
                        f"{count} files sent. Check your voice models in the phone app.",
                    )
                )
            )
            if error:
                self.say(self.error_text(error))
                self.tabs.select(2)

        self.background(
            lambda: work.transfer(serial, voices, out, shared, self.emit), sent
        )

    def wireless(self):
        win = tk.Toplevel(self, bg=PANEL)
        win.title(self.t("무선 디버깅 연결", "Wireless debugging"))
        win.transient(self)
        win.grab_set()
        body = tk.Frame(win, bg=PANEL, padx=22, pady=18)
        body.pack(fill="both", expand=True)
        self.label(
            body,
            "폰의 무선 디버깅 → 페어링 코드로 기기 페어링에서 주소와 코드를 확인하세요.\n페어링 후 기본 화면의 연결 주소를 사용하세요. 두 포트는 보통 달라요.",
            "Phone: Wireless debugging → Pair device with pairing code.\nAfter pairing, use the connection address on the main screen. The two ports are usually different.",
            muted=True,
        )
        variables = [tk.StringVar() for _ in range(3)]
        for i, (ko, en) in enumerate(
            [
                ("페어링 주소 (IP:포트)", "Pairing address (IP:port)"),
                ("6자리 페어링 코드", "Six-digit pairing code"),
                ("연결 주소 (IP:포트)", "Connection address (IP:port)"),
            ]
        ):
            self.label(body, ko, en)
            ttk.Entry(
                body, textvariable=variables[i], width=48, show="•" if i == 1 else ""
            ).pack(fill="x", pady=(0, 8))
        result = self.label(body)
        buttons = []

        def run(pair):
            address = variables[0 if pair else 2].get().strip()
            code = variables[1].get().strip()
            if not re.fullmatch(r"(?:[\w.-]+|\[[0-9a-fA-F:]+\]):\d{1,5}", address) or (
                pair and not re.fullmatch(r"\d{6}", code)
            ):
                result.configure(
                    text=self.t(
                        "IP:포트 주소와 6자리 코드를 확인하세요.",
                        "Check the IP:port address and six-digit code.",
                    )
                )
                return
            for b in buttons:
                b.state(["disabled"])
            result.configure(text=self.t("처리 중…", "Working…"))

            def finished(value, error):
                if win.winfo_exists():
                    variables[1].set("")
                    result.configure(text=self.error_text(error) if error else value)
                    for b in buttons:
                        b.state(["!disabled"])
                self.refresh_devices()

            self.background(
                lambda: work.adb(
                    *(["pair", address, code] if pair else ["connect", address]),
                    timeout=35,
                ),
                finished,
            )

        row = self.row(body)
        buttons.append(
            self.button(row, "페어링", "Pair", lambda: run(True), lock=False)
        )
        buttons.append(
            self.button(row, "연결", "Connect", lambda: run(False), lock=False)
        )

    def emit(self, kind, value):
        self.events.put((kind, value))

    def say(self, value):
        lines = str(value).splitlines()
        if not lines:
            return
        overflow = max(0, len(self.log_lines) + len(lines) - 1500)
        self.log_lines = (self.log_lines + lines)[-1500:]
        self.log.configure(state="normal")
        self.log.insert("end", "\n".join(lines) + "\n")
        if overflow:
            self.log.delete("1.0", f"{overflow+1}.0")
        self.log.see("end")
        self.log.configure(state="disabled")

    def save_log(self):
        path = filedialog.asksaveasfilename(
            parent=self,
            defaultextension=".txt",
            initialfile="converter-log.txt",
            filetypes=[("Text", "*.txt")],
        )
        if path:
            try:
                Path(path).write_text("\n".join(self.log_lines), encoding="utf-8")
            except OSError as error:
                self.alert(str(error))

    def pump(self):
        try:
            for _ in range(80):
                kind, value = self.events.get_nowait()
                if kind == "callback":
                    callback, result, error = value
                    callback(result, error)
                    if self.close_pending and not self.running:
                        return
                elif kind == "log":
                    self.say(value)
                elif kind == "job":
                    name, index, total = value
                    name = (
                        self.t("공통 모델", "Shared models")
                        if name == "common"
                        else name
                    )
                    self.status.configure(
                        text=self.t(
                            f"작업 {index}/{total} · {name}",
                            f"Job {index}/{total} · {name}",
                        )
                    )
                elif kind == "stage":
                    self.stage_label.configure(
                        text=self.t(*STAGES.get(value, (value, value)))
                    )
                elif kind == "job_error":
                    name, key, detail = value
                    self.say(
                        name
                        + ": "
                        + self.t(*ERRORS.get(key, (key, key)))
                        + "\n"
                        + detail
                    )
                elif kind == "job_done":
                    self.say(value[0] + ": " + self.t("완료", "Done"))
                elif kind == "transfer_file":
                    name, index, total = value
                    self.status.configure(
                        text=self.t(
                            f"파일 {index}/{total} 전송 중 · {name}",
                            f"Sending file {index}/{total} · {name}",
                        )
                    )
        except queue.Empty:
            pass
        if self.started:
            elapsed = int(time.monotonic() - self.started)
            self.elapsed.configure(
                text=self.t("경과 ", "Elapsed ") + f"{elapsed//60:02d}:{elapsed%60:02d}"
            )
        self.after(75, self.pump)

    def close(self):
        if self.running == "send":
            self.alert(
                self.t(
                    "파일 전송이 끝난 뒤 닫아 주세요.",
                    "Wait for the transfer to finish before closing.",
                )
            )
            return
        if self.running:
            if messagebox.askyesno(
                self.t("변환 취소", "Cancel conversion"),
                self.t("변환을 취소하고 닫을까요?", "Cancel conversion and close?"),
                parent=self,
            ):
                self.close_pending = True
                self.cancel()
            return
        self.save_preferences()
        self.destroy()


if __name__ == "__main__":
    App([a for a in sys.argv[1:] if a.lower().endswith(".pth")]).mainloop()

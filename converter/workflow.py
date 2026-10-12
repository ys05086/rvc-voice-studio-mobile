"""GUI workflow helpers. No Tk or ML imports; workers communicate through events."""

from dataclasses import dataclass
import json
import os
from pathlib import Path
import queue
import re
import shutil
import signal
import subprocess
import threading
import time
import uuid
import wave
import zipfile

HERE = Path(__file__).resolve().parent
PACKAGE = "com.voicestudio.phone"
REMOTE = f"/sdcard/Android/data/{PACKAGE}/files"
COMMON = ("hubert.onnx", "rmvpe.onnx", "hubert.htp.onnx")
NO_WINDOW = subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0


class Cancelled(Exception):
    pass


class WorkflowError(Exception):
    def __init__(self, key, detail=""):
        self.key, self.detail = key, str(detail)
        super().__init__(f"{key}: {detail}")


def safe_name(name):
    name = name.strip()
    if not name or len(name) > 80 or not re.fullmatch(r"[\w-]+", name):
        raise WorkflowError("bad_name", name)
    if name.casefold() in {"con", "prn", "aux", "nul", "common", "tmp"} or re.fullmatch(
        r"(com|lpt)[1-9]", name, re.I
    ):
        raise WorkflowError("bad_name", name)
    return name


def validate_names(names):
    seen = set()
    for name in names:
        safe_name(name)
        if name.casefold() in seen:
            raise WorkflowError("duplicate_name", name)
        seen.add(name.casefold())


def present(path):
    return path.is_file() and path.stat().st_size > 0


def missing_common(out):
    return [name for name in COMMON if not present(Path(out) / "common" / name)]


def recordings(folder):
    files, seconds, invalid = 0, 0.0, 0
    folder = Path(folder)
    for path in folder.iterdir() if folder.is_dir() else []:
        if path.suffix.lower() != ".wav" or not path.is_file():
            continue
        try:
            with wave.open(str(path)) as audio:
                duration = audio.getnframes() / audio.getframerate()
                if duration <= 0:
                    raise ValueError("empty recording")
                seconds += duration
                files += 1
        except (OSError, wave.Error, EOFError, ValueError, ZeroDivisionError):
            invalid += 1
    return files, seconds, invalid


def inspect_checkpoint(path):
    import torch

    data = torch.load(str(path), map_location="cpu", weights_only=True)
    if (
        not isinstance(data, dict)
        or "weight" not in data
        or not isinstance(data.get("config"), (list, tuple))
        or not data["config"]
    ):
        raise WorkflowError("checkpoint")
    version, rate, pitch = (
        data.get("version", "v1"),
        data["config"][-1],
        data.get("f0", 1),
    )
    return dict(
        version=version,
        rate=rate,
        pitch=pitch,
        supported=version == "v2" and rate == 48000 and pitch == 1,
    )


def adb_path():
    candidates = [
        Path(os.environ.get("LOCALAPPDATA", "")) / "Android/Sdk/platform-tools/adb.exe",
        HERE / "platform-tools/adb.exe",
    ]
    return next((str(p) for p in candidates if p.is_file()), shutil.which("adb"))


def adb(*args, timeout=25):
    exe = adb_path()
    if not exe:
        raise WorkflowError("adb_missing")
    result = subprocess.run(
        [exe, *map(str, args)],
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        timeout=timeout,
        creationflags=NO_WINDOW,
    )
    if result.returncode:
        raise WorkflowError(
            "adb_failed", (result.stdout + result.stderr).strip()[-600:]
        )
    return result.stdout.strip()


def devices():
    found = []
    for line in adb("devices", "-l").splitlines():
        fields = line.split()
        if len(fields) >= 2 and fields[1] in ("device", "offline", "unauthorized"):
            model = next(
                (p.partition(":")[2] for p in fields[2:] if p.startswith("model:")), ""
            )
            found.append(dict(serial=fields[0], state=fields[1], model=model))
    return found


def phone_profile(serial):
    manufacturer = adb("-s", serial, "shell", "getprop", "ro.soc.manufacturer").lower()
    model = adb("-s", serial, "shell", "getprop", "ro.soc.model").lower()
    return (
        "snapdragon"
        if "qualcomm" in manufacturer or "snapdragon" in model
        else "portable"
    )


class ProcessRunner:
    """Cancellation owns only the process tree created for this conversion."""

    def __init__(self):
        self.cancelled = threading.Event()

    def cancel(self):
        self.cancelled.set()

    @staticmethod
    def kill(proc):
        if os.name == "nt":
            subprocess.run(
                ["taskkill", "/PID", str(proc.pid), "/T", "/F"],
                capture_output=True,
                timeout=15,
                creationflags=NO_WINDOW,
            )
        else:
            try:
                os.killpg(proc.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
        if proc.poll() is None:
            proc.kill()
        proc.wait(timeout=15)

    def run(self, command, emit, cwd=HERE, timeout=None):
        if self.cancelled.is_set():
            raise Cancelled()
        env = dict(os.environ, PYTHONIOENCODING="utf-8", PYTHONUNBUFFERED="1")
        proc = subprocess.Popen(
            command,
            cwd=cwd,
            env=env,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            encoding="utf-8",
            errors="replace",
            creationflags=NO_WINDOW,
            start_new_session=os.name != "nt",
        )
        lines = queue.Queue()

        def read():
            try:
                for line in proc.stdout:
                    lines.put(line.rstrip())
            finally:
                lines.put(None)

        reader = threading.Thread(target=read, daemon=True)
        reader.start()
        started = time.monotonic()
        try:
            ended = False
            while not ended:
                if self.cancelled.is_set():
                    raise Cancelled()
                if timeout and time.monotonic() - started > timeout:
                    raise TimeoutError("Process timed out")
                try:
                    line = lines.get(timeout=0.1)
                    if line is None:
                        ended = True
                    elif line:
                        emit(line)
                except queue.Empty:
                    continue
            return proc.wait()
        except BaseException:
            self.kill(proc)
            raise
        finally:
            reader.join(timeout=3)
            if not reader.is_alive():
                proc.stdout.close()


@dataclass(frozen=True)
class Voice:
    path: Path
    name: str


@dataclass(frozen=True)
class Options:
    out: Path
    calib: Path
    gpu: bool = True
    npu: bool = False
    common: bool = True


def required_voice(name, gpu=False, npu=False):
    return (
        [f"{name}.enc.onnx", f"{name}.dec.onnx", "settings.json"]
        + ([f"{name}.dec.ncnn.param", f"{name}.dec.ncnn.bin"] if gpu else [])
        + ([f"{name}.dec.w8a16.onnx"] if npu else [])
    )


def validate_voice(out, name, gpu=False, npu=False, archive=False):
    safe_name(name)
    folder = Path(out) / name
    required = required_voice(name, gpu, npu)
    missing = [part for part in required if not present(folder / part)]
    if missing:
        raise WorkflowError("incomplete", ", ".join(missing))
    with (folder / "settings.json").open(encoding="utf-8") as settings:
        if not isinstance(json.load(settings), dict):
            raise WorkflowError("incomplete", "settings.json")
    gpu_pair = [
        present(folder / f"{name}.dec.ncnn.{suffix}") for suffix in ("param", "bin")
    ]
    if any(gpu_pair) and not all(gpu_pair):
        raise WorkflowError("incomplete", "ncnn param/bin")
    if archive:
        with zipfile.ZipFile(Path(out) / f"{name}.zip") as z:
            if (
                not all(f"{name}/{part}" in z.namelist() for part in required)
                or z.testzip()
            ):
                raise WorkflowError("incomplete", f"{name}.zip")
    return [folder / part for part in required]


def remove_work(path, jobs_root):
    """Never recursively remove a path outside our generated .jobs directory."""
    resolved, root = path.resolve(), jobs_root.resolve()
    if (
        root.name != ".jobs"
        or resolved == root
        or root not in resolved.parents
        or path.is_symlink()
    ):
        raise RuntimeError("Unsafe work directory")
    if path.exists():
        shutil.rmtree(path)


def publish(stage, out, names):
    """Roll back previous outputs if a completed folder/ZIP cannot be installed."""
    backup = stage / "previous"
    backup.mkdir()
    moved_old, moved_new = [], []
    try:
        for name in names:
            destination = out / name
            if destination.is_symlink():
                raise WorkflowError("output_link", name)
            if destination.exists():
                destination.replace(backup / name)
                moved_old.append(name)
            (stage / name).replace(destination)
            moved_new.append(name)
    except BaseException:
        try:
            for name in reversed(moved_new):
                (out / name).replace(stage / name)
            for name in reversed(moved_old):
                (backup / name).replace(out / name)
        except Exception as recovery_error:
            error = WorkflowError("recover_output", str(stage))
            error.keep_stage = True
            raise error from recovery_error
        raise


def convert_batch(voices, options, python, runner, emit):
    """Fresh staging for each job; failed/cancelled output never replaces a good model."""
    out = options.out.resolve()
    validate_names(v.name for v in voices)
    out.mkdir(parents=True, exist_ok=True)
    jobs_root = out / ".jobs"
    if jobs_root.is_symlink():
        raise WorkflowError("output_link", ".jobs")
    jobs_root.mkdir(exist_ok=True)
    needs_common = bool(missing_common(out)) and (options.common or options.npu)
    jobs = ([None] if needs_common else []) + list(voices)
    failed, completed, cancelled = [], [], False
    for index, voice in enumerate(jobs):
        label = voice.name if voice else "common"
        if runner.cancelled.is_set():
            cancelled = True
            failed.extend(v.name if v else "common" for v in jobs[index:])
            break
        # NPU calibration must not rebuild shared files inside an unsuccessful voice job.
        if voice and options.npu and missing_common(out):
            failed.append(label)
            emit("job_error", (label, "common_required", ""))
            continue
        stage = jobs_root / uuid.uuid4().hex
        stage.mkdir()
        emit("job", (label, index + 1, len(jobs)))
        command = [
            str(python),
            "-u",
            str(HERE / "rvc2phone.py"),
            "--out",
            str(stage),
            "--progress-events",
        ]
        if voice:
            command += [
                str(voice.path),
                "--name",
                voice.name,
                "--calib",
                str(options.calib),
                "--common-dir",
                str(out / "common"),
            ]
            if not options.gpu:
                command.append("--no-ncnn")
            if not options.npu:
                command.append("--no-npu")
        else:
            command.append("--common")

        def line(text):
            if text.startswith("@rvc2phone "):
                try:
                    emit("stage", json.loads(text[len("@rvc2phone ") :])["stage"])
                    return
                except (ValueError, KeyError, TypeError):
                    pass
            emit("log", text)

        keep_stage = False
        try:
            if runner.run(command, line):
                raise WorkflowError("export_failed")
            if runner.cancelled.is_set():
                raise Cancelled()
            if voice:
                validate_voice(stage, label, options.gpu, options.npu, archive=True)
                names = [label, f"{label}.zip"]
            else:
                if missing_common(stage):
                    raise WorkflowError("incomplete", ", ".join(missing_common(stage)))
                names = ["common"]
            publish(stage, out, names)
            completed.append(label)
            emit("job_done", (label, index + 1, len(jobs)))
        except Cancelled:
            cancelled = True
            failed.extend(v.name if v else "common" for v in jobs[index:])
            break
        except Exception as error:
            keep_stage = getattr(error, "keep_stage", False)
            failed.append(label)
            emit(
                "job_error",
                (
                    label,
                    getattr(error, "key", "export_failed"),
                    getattr(error, "detail", str(error)),
                ),
            )
        finally:
            if not keep_stage:
                remove_work(stage, jobs_root)
    return dict(
        completed=completed, failed=failed, cancelled=cancelled, total=len(jobs)
    )


def transfer(serial, voices, out, with_common, emit, call=adb):
    """Check complete local outputs before modifying the phone; upload each file via .part."""
    import shlex

    files = []
    for voice in voices:
        validate_voice(out, voice.name)
        folder = out / voice.name
        for part in (
            "enc.onnx",
            "dec.onnx",
            "dec.w8a16.onnx",
            "dec.ncnn.param",
            "dec.ncnn.bin",
        ):
            file = folder / f"{voice.name}.{part}"
            if present(file):
                files.append((file, file.name))
        files.append((folder / "settings.json", f"{voice.name}.settings.json"))
    if with_common:
        missing = missing_common(out)
        if missing:
            raise WorkflowError("common_required", ", ".join(missing))
    if "package:" not in call("-s", serial, "shell", "pm", "path", PACKAGE):
        raise WorkflowError("app_missing")
    if with_common:
        have = call(
            "-s", serial, "shell", f"ls {REMOTE}/common 2>/dev/null || true"
        ).split()
        files.extend(
            (Path(out) / "common" / name, name) for name in COMMON if name not in have
        )
    if not files:
        return 0
    call("-s", serial, "shell", "am", "force-stop", PACKAGE)
    call("-s", serial, "shell", "mkdir", "-p", REMOTE)
    remote_part = f"{REMOTE}/.transfer-{uuid.uuid4().hex}.part"
    try:
        for index, (file, name) in enumerate(files, 1):
            emit("transfer_file", (name, index, len(files)))
            call("-s", serial, "push", file, remote_part, timeout=1800)
            call(
                "-s",
                serial,
                "shell",
                f'mv {shlex.quote(remote_part)} {shlex.quote(REMOTE + "/" + name)}',
            )
        call("-s", serial, "shell", "am", "start", "-n", f"{PACKAGE}/.MainActivity")
    finally:
        # Only the unique temporary file owned by this transfer is eligible for removal.
        try:
            call("-s", serial, "shell", "rm", "-f", remote_part)
        except Exception:
            pass
    return len(files)

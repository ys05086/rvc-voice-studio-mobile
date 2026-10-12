import json
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from unittest.mock import patch
import wave
import zipfile

import workflow as w


def output(root, name, gpu=False, npu=False):
    folder = root / name
    folder.mkdir(parents=True, exist_ok=True)
    for part in w.required_voice(name, gpu, npu):
        (folder / part).write_text(
            "{}" if part == "settings.json" else "model", encoding="utf-8"
        )
    with zipfile.ZipFile(root / f"{name}.zip", "w") as archive:
        for file in folder.iterdir():
            archive.write(file, f"{name}/{file.name}")


class FakeRunner(w.ProcessRunner):
    def __init__(self, fail=(), incomplete=False):
        super().__init__()
        self.fail = fail
        self.incomplete = incomplete
        self.jobs = []

    def run(self, command, emit):
        root = Path(command[command.index("--out") + 1])
        name = command[command.index("--name") + 1] if "--name" in command else "common"
        self.jobs.append(name)
        emit('@rvc2phone {"stage":"onnx"}')
        if name in self.fail:
            return 1
        if name == "common":
            (root / "common").mkdir()
            for file in w.COMMON:
                (root / "common" / file).write_text("model")
        else:
            output(
                root,
                name,
                gpu="--no-ncnn" not in command,
                npu="--no-npu" not in command,
            )
            if self.incomplete:
                (root / name / f"{name}.dec.onnx").unlink()
        return 0


class WorkflowTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)

    def tearDown(self):
        self.temp.cleanup()

    def test_names_and_case_insensitive_collisions(self):
        for name in ("../voice", "CON", "common", "a/b", "", "LPT1", "a" * 81):
            with self.subTest(name=name), self.assertRaises(w.WorkflowError):
                w.safe_name(name)
        self.assertEqual(w.safe_name("목소리_1"), "목소리_1")
        with self.assertRaises(w.WorkflowError):
            w.validate_names(["Voice", "voice"])

    def test_shared_check_requires_all_nonempty_files(self):
        folder = self.root / "common"
        folder.mkdir()
        (folder / "hubert.onnx").write_bytes(b"x")
        (folder / "rmvpe.onnx").touch()
        self.assertEqual(w.missing_common(self.root), ["rmvpe.onnx", "hubert.htp.onnx"])

    def test_recordings_ignores_invalid_and_accepts_uppercase(self):
        with wave.open(str(self.root / "audio.WAV"), "wb") as audio:
            audio.setparams((1, 2, 16000, 0, "NONE", "not compressed"))
            audio.writeframes(b"\0\0" * 16000)
        (self.root / "broken.wav").write_text("bad")
        self.assertEqual(w.recordings(self.root), (1, 1.0, 1))

    def test_incomplete_gpu_and_bad_settings_are_not_transferable(self):
        output(self.root, "Voice")
        (self.root / "Voice/Voice.dec.ncnn.param").write_text("param")
        with self.assertRaises(w.WorkflowError):
            w.validate_voice(self.root, "Voice")
        (self.root / "Voice/Voice.dec.ncnn.bin").write_text("bin")
        (self.root / "Voice/settings.json").write_text("[]")
        with self.assertRaises(w.WorkflowError):
            w.validate_voice(self.root, "Voice")

    def test_success_then_failure_preserves_existing_result(self):
        output(self.root, "Old")
        old = (self.root / "Old.zip").read_bytes()
        runner = FakeRunner(fail={"Old"})
        result = w.convert_batch(
            [w.Voice(Path("a.pth"), "New"), w.Voice(Path("b.pth"), "Old")],
            w.Options(self.root, self.root, common=False),
            sys.executable,
            runner,
            lambda *_: None,
        )
        self.assertEqual(result["completed"], ["New"])
        self.assertEqual(result["failed"], ["Old"])
        self.assertEqual((self.root / "Old.zip").read_bytes(), old)
        self.assertEqual(list((self.root / ".jobs").iterdir()), [])

    def test_zero_exit_with_missing_output_is_failure(self):
        output(self.root, "Voice")
        old = (self.root / "Voice.zip").read_bytes()
        result = w.convert_batch(
            [w.Voice(Path("a.pth"), "Voice")],
            w.Options(self.root, self.root, common=False),
            sys.executable,
            FakeRunner(incomplete=True),
            lambda *_: None,
        )
        self.assertEqual(result["failed"], ["Voice"])
        self.assertEqual((self.root / "Voice.zip").read_bytes(), old)

    def test_common_is_separate_and_not_rebuilt_when_complete(self):
        runner = FakeRunner()
        opts = w.Options(self.root, self.root)
        w.convert_batch(
            [w.Voice(Path("a.pth"), "Voice")],
            opts,
            sys.executable,
            runner,
            lambda *_: None,
        )
        self.assertEqual(runner.jobs, ["common", "Voice"])
        runner.jobs.clear()
        w.convert_batch(
            [w.Voice(Path("a.pth"), "Voice")],
            opts,
            sys.executable,
            runner,
            lambda *_: None,
        )
        self.assertEqual(runner.jobs, ["Voice"])

    def test_failed_common_blocks_npu_voice(self):
        runner = FakeRunner(fail={"common"})
        result = w.convert_batch(
            [w.Voice(Path("a.pth"), "Voice")],
            w.Options(self.root, self.root, npu=True),
            sys.executable,
            runner,
            lambda *_: None,
        )
        self.assertEqual(runner.jobs, ["common"])
        self.assertEqual(result["failed"], ["common", "Voice"])

    def test_cancel_keeps_old_result_and_marks_remaining_jobs(self):
        output(self.root, "Voice")
        old = (self.root / "Voice.zip").read_bytes()
        runner = FakeRunner()

        def run(command, emit):
            runner.cancel()
            raise w.Cancelled()

        runner.run = run
        result = w.convert_batch(
            [w.Voice(Path("a.pth"), "Voice"), w.Voice(Path("b.pth"), "Next")],
            w.Options(self.root, self.root, common=False),
            sys.executable,
            runner,
            lambda *_: None,
        )
        self.assertTrue(result["cancelled"])
        self.assertEqual(result["failed"], ["Voice", "Next"])
        self.assertEqual((self.root / "Voice.zip").read_bytes(), old)
        self.assertEqual(list((self.root / ".jobs").iterdir()), [])

    def test_publish_rolls_back_folder_if_zip_move_fails(self):
        stage = self.root / "stage"
        stage.mkdir()
        output(self.root, "Voice")
        output(stage, "Voice")
        (self.root / "Voice/Voice.enc.onnx").write_text("original")
        original_replace = Path.replace

        def replace(path, destination):
            if path == stage / "Voice.zip":
                raise PermissionError("locked")
            return original_replace(path, destination)

        with patch.object(Path, "replace", replace), self.assertRaises(PermissionError):
            w.publish(stage, self.root, ["Voice", "Voice.zip"])
        self.assertEqual((self.root / "Voice/Voice.enc.onnx").read_text(), "original")
        self.assertTrue((self.root / "Voice.zip").is_file())

    def test_cleanup_rejects_outside_workspace(self):
        with self.assertRaises(RuntimeError):
            w.remove_work(self.root, self.root / ".jobs")
        self.assertTrue(self.root.is_dir())

    def test_actual_process_cancels_silent_worker(self):
        runner = w.ProcessRunner()
        began = threading.Event()
        caught = []

        def worker():
            try:
                runner.run(
                    [
                        sys.executable,
                        "-u",
                        "-c",
                        'import time; print("ready"); time.sleep(60)',
                    ],
                    lambda _: began.set(),
                )
            except w.Cancelled:
                caught.append(True)

        thread = threading.Thread(target=worker)
        thread.start()
        self.assertTrue(began.wait(10))
        runner.cancel()
        thread.join(20)
        self.assertFalse(thread.is_alive())
        self.assertEqual(caught, [True])

    def test_transfer_preflight_does_not_touch_phone_on_incomplete_output(self):
        calls = []
        with self.assertRaises(w.WorkflowError):
            w.transfer(
                "phone",
                [w.Voice(Path("a"), "Missing")],
                self.root,
                False,
                lambda *_: None,
                lambda *a, **kw: calls.append(a),
            )
        self.assertEqual(calls, [])

    def test_transfer_failed_push_never_promotes_partial_file(self):
        output(self.root, "Voice")
        calls = []

        def adb(*args, **kw):
            calls.append(args)
            if "push" in args:
                raise w.WorkflowError("adb_failed", "disconnected")
            if "pm" in args:
                return "package:app.apk"
            return ""

        with self.assertRaises(w.WorkflowError):
            w.transfer(
                "phone",
                [w.Voice(Path("a"), "Voice")],
                self.root,
                False,
                lambda *_: None,
                adb,
            )
        self.assertFalse(any(any(str(a).startswith("mv ") for a in c) for c in calls))
        self.assertTrue(any("rm" in c for c in calls))

    def test_existing_settings_are_sent_with_seed_filename(self):
        output(self.root, "Voice")
        calls = []

        def adb(*args, **kw):
            calls.append(args)
            return "package:app.apk" if "pm" in args else ""

        count = w.transfer(
            "phone",
            [w.Voice(Path("a"), "Voice")],
            self.root,
            False,
            lambda *_: None,
            adb,
        )
        self.assertEqual(count, 3)
        self.assertTrue(any("Voice.settings.json" in str(c) for c in calls))
        self.assertFalse(any("/models/" in str(c) for c in calls))


if __name__ == "__main__":
    unittest.main()

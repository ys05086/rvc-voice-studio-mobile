"""Windows/Tk integration checks; the workflow tests also run without a display."""

import os
from pathlib import Path
import tempfile
import time
import unittest
from unittest.mock import patch


@unittest.skipUnless(os.name == "nt", "Requires the Windows Tk desktop")
class GuiTests(unittest.TestCase):
    def setUp(self):
        import rvc2phone_gui

        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.app = rvc2phone_gui.App(
            discover=False, preferences=self.root / "preferences.json"
        )
        self.app.withdraw()
        self.app.out.set(str(self.root / "out"))
        self.app.common.set(False)

    def tearDown(self):
        self.app.update_idletasks()
        for timer in self.app.tk.call("after", "info"):
            self.app.after_cancel(timer)
        self.app.destroy()
        self.temp.cleanup()

    def test_retry_skips_success_and_uses_current_options(self):
        app = self.app
        app.voices = [
            dict(
                path=self.root / f"{name}.pth",
                name=name,
                info={"supported": True},
                error=None,
                state=state,
            )
            for name, state in [("Done", "done"), ("Retry", "failed")]
        ]
        app.failed = {"Retry"}
        app.profile.set(2)
        calls = []

        def convert(voices, options, *args):
            calls.append((voices, options))
            return dict(completed=["Retry"], failed=[], cancelled=False, total=1)

        with patch("rvc2phone_gui.work.convert_batch", side_effect=convert):
            app.convert(retry=True)
            deadline = time.monotonic() + 5
            while app.running and time.monotonic() < deadline:
                app.update()
                time.sleep(0.01)
        self.assertIsNone(app.running)
        self.assertEqual([v.name for v in calls[0][0]], ["Retry"])
        self.assertFalse(calls[0][1].gpu)
        self.assertFalse(calls[0][1].npu)
        self.assertEqual(app.failed, set())
        self.assertTrue(app.retry_button.instate(["disabled"]))
        self.assertEqual(float(app.progress["value"]), 0)

    def test_log_limit_and_language_rebuild_preserve_lines(self):
        app = self.app
        app.say("\n".join(str(i) for i in range(1600)))
        self.assertEqual(len(app.log_lines), 1500)
        self.assertEqual(app.log.get("1.0", "end-1c"), "\n".join(app.log_lines) + "\n")
        app.language_box.current(1)
        app.change_language()
        app.say("last line")
        self.assertEqual(app.language, "en")
        self.assertEqual(app.log.get("1.0", "end-1c"), "\n".join(app.log_lines) + "\n")
        app.language_box.current(0)
        app.change_language()
        self.assertEqual(app.language, "ko")


if __name__ == "__main__":
    unittest.main()

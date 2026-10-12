import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import model_format as fmt
import workflow as w
from test_workflow import output


class ModelFormatTests(unittest.TestCase):
    def checkpoint(self, rate):
        config = [None] * 18
        config[2] = 192
        config[12] = [10, 8, 2, 2] if rate in (32000, "32k") else [12, 10, 2, 2]
        config[-1] = rate
        return dict(version="v2", f0=1, config=config, weight={})

    def test_32_and_48_checkpoint_geometry(self):
        for rate in (32000, 48000, "32k", "48k"):
            self.assertTrue(fmt.checkpoint_info(self.checkpoint(rate))["supported"])
        for change in [dict(version="v1"), dict(f0=0)]:
            model = self.checkpoint(32000)
            model.update(change)
            self.assertFalse(fmt.checkpoint_info(model)["supported"])
        model = self.checkpoint(32000)
        model["config"][12] = [12, 10, 2, 2]
        self.assertFalse(fmt.checkpoint_info(model)["supported"])
        self.assertFalse(fmt.checkpoint_info(self.checkpoint(40000))["supported"])

    def test_metadata_legacy_and_validation(self):
        with tempfile.TemporaryDirectory() as tmp:
            folder = Path(tmp)
            self.assertEqual(fmt.read_geometry(folder)["sample_rate"], 48000)
            (folder / "model.json").write_text(json.dumps(fmt.geometry(32000)))
            self.assertEqual(fmt.read_geometry(folder)["hop_length"], 320)
            bad = fmt.geometry(32000)
            bad["hop_length"] = 480
            (folder / "model.json").write_text(json.dumps(bad))
            with self.assertRaises(ValueError):
                fmt.read_geometry(folder)
            (folder / "model.json").write_text("{}")
            with self.assertRaises(ValueError):
                fmt.read_geometry(folder)

    def test_new_export_requires_metadata_but_old_folder_remains_readable(self):
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp)
            output(out, "Voice")
            (out / "Voice/model.json").unlink()
            w.validate_voice(out, "Voice")
            with self.assertRaises(w.WorkflowError):
                w.validate_voice(out, "Voice", archive=True)

    def test_32_transfer_requires_new_app_and_includes_metadata(self):
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp)
            output(out, "Voice")
            (out / "Voice/model.json").write_text(json.dumps(fmt.geometry(32000)))
            calls = []

            def call(*args, **kwargs):
                calls.append(args)
                return (
                    "package:app.apk"
                    if "path" in args
                    else "versionCode=3" if "dumpsys" in args else ""
                )

            with self.assertRaises(w.WorkflowError) as caught:
                w.transfer(
                    "phone",
                    [w.Voice(Path("Voice.pth"), "Voice")],
                    out,
                    False,
                    lambda *_: None,
                    call,
                )
            self.assertEqual(caught.exception.key, "app_update_required")
            self.assertFalse(any("force-stop" in c or "push" in c for c in calls))

            def current(*args, **kwargs):
                calls.append(args)
                return (
                    "package:app.apk"
                    if "path" in args
                    else "versionCode=5" if "dumpsys" in args else ""
                )

            w.transfer(
                "phone",
                [w.Voice(Path("Voice.pth"), "Voice")],
                out,
                False,
                lambda *_: None,
                current,
            )
            self.assertTrue(any("Voice.model.json" in str(c) for c in calls))


if __name__ == "__main__":
    unittest.main()

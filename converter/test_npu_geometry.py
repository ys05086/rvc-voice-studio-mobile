"""Calibration geometry regression without loading neural-network weights."""

import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import numpy as np

from model_format import geometry
import npu_export as npu


class CalibrationGeometryTests(unittest.TestCase):
    def test_calibration_stride_does_not_replace_native_sample_hop(self):
        class Session:
            def __init__(self, path, **kwargs):
                self.name = Path(path).name

            def run(self, _, feeds):
                if self.name == "hubert.onnx":
                    n = feeds["audio"].shape[1] // 320
                    return [np.zeros((1, n - 1, 768), dtype=np.float32)]
                if self.name == "rmvpe.onnx":
                    return [np.zeros((1, feeds["audio"].shape[1] // 160, 360))]
                n = feeds["phone"].shape[1]
                return [np.broadcast_to(np.arange(n, dtype=np.float32), (1, 192, n))]

        with tempfile.TemporaryDirectory() as tmp:
            folder = Path(tmp)
            for rate in (32000, 48000):
                with self.subTest(rate=rate):
                    (folder / "model.json").write_text(json.dumps(geometry(rate)))
                    with patch.dict(
                        "sys.modules",
                        {"onnxruntime": SimpleNamespace(InferenceSession=Session)},
                    ), patch.object(
                        npu,
                        "read_wav",
                        return_value=(np.ones(16000 * 6, dtype=np.float32), 16000),
                    ), patch.object(
                        npu, "resample", side_effect=lambda x, *_: x
                    ), patch.object(
                        npu, "decode_f0", side_effect=lambda x: np.full(len(x), 200.0)
                    ):
                        pieces = npu.calibration_pieces(
                            folder / "voice.enc.onnx", folder, [Path("example.wav")]
                        )
                    self.assertEqual(len(pieces), 3)
                    for z, sine in pieces:
                        self.assertEqual(z.shape, (1, 192, npu.FRAMES))
                        self.assertEqual(sine.shape, (1, npu.FRAMES * rate // 100, 1))
                        self.assertTrue(np.isfinite(sine).all())
                    self.assertEqual(
                        [int(z[0, 0, 0]) for z, _ in pieces], [0, 168, 336]
                    )

    def test_native_excitation_keeps_frequency_at_both_sample_rates(self):
        for rate in (32000, 48000):
            actual = npu.nsf_source(
                np.full(100, 200.0),
                np.random.default_rng(0),
                upp=rate // 100,
                sample_rate=rate,
                noise_std=0,
            ).ravel()
            expected = 0.1 * np.sin(2 * np.pi * 200 * np.arange(1, rate + 1) / rate)
            np.testing.assert_allclose(actual, expected, atol=1e-7)


if __name__ == "__main__":
    unittest.main()

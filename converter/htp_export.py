"""HuBERT for the Snapdragon NPU (Hexagon HTP through ONNX Runtime's QNN execution provider).

  python htp_export.py common/hubert.onnx [-o common/hubert.htp.onnx] [--check]

HuBERT's GELUs are exported as erf formulas (x * 0.5 * (1 + erf(x / sqrt 2))). QNN has no Erf on the HTP, so ONNX
Runtime ran those 20 nodes on the CPU and cut the model into 21 NPU pieces with copies in between: on the Galaxy S23+
a realtime step (1.6 s window) took 131 ms, 59 of them in Erf on the CPU. This fuses each formula into one
com.microsoft Gelu node (the same function), which QNN maps to its own GELU on the HTP. The Android app uses
hubert.htp.onnx instead of hubert.onnx when the front runs on the NPU.
--check compares the two files on the CPU over random audio (ORT runs the fused Gelu as erf too).
"""
import argparse
from pathlib import Path

import numpy as np
import onnx
from onnxruntime.transformers.fusion_gelu import FusionGelu
from onnxruntime.transformers.onnx_model import OnnxModel


def fuse(src, out, check=False):
    """src -> out with every erf GELU as one com.microsoft Gelu node."""
    model = OnnxModel(onnx.load(str(src)))
    before = sum(n.op_type == "Erf" for n in model.model.graph.node)
    FusionGelu(model).apply()
    model.prune_graph()
    nodes = model.model.graph.node
    erf, gelu = sum(n.op_type == "Erf" for n in nodes), sum(n.op_type == "Gelu" for n in nodes)
    if erf:
        raise SystemExit(f"{erf} of {before} Erf nodes did not match the GELU pattern")
    if not any(o.domain == "com.microsoft" for o in model.model.opset_import):
        model.model.opset_import.append(onnx.helper.make_opsetid("com.microsoft", 1))
    onnx.save(model.model, str(out))
    print(f"{out}: {before} erf GELUs -> {gelu} Gelu nodes")
    if check:
        import onnxruntime as ort
        rng = np.random.default_rng(0)
        x = (rng.standard_normal((1, 25600)) * 0.1).astype(np.float32)
        ref = ort.InferenceSession(str(src), providers=["CPUExecutionProvider"]).run(None, {"audio": x})[0]
        new = ort.InferenceSession(str(out), providers=["CPUExecutionProvider"]).run(None, {"audio": x})[0]
        print(f"max difference {np.abs(ref - new).max():.2e} (output range {np.abs(ref).max():.2f})")


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("model", type=Path)
    p.add_argument("-o", "--out", type=Path)
    p.add_argument("--check", action="store_true")
    a = p.parse_args()
    fuse(a.model, a.out or a.model.with_name(a.model.stem + ".htp.onnx"), a.check)


if __name__ == "__main__":
    main()

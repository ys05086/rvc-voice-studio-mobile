# Notice

The PC converter (`converter/rvc/`) contains model code from
[RVC-Project/Retrieval-based-Voice-Conversion-WebUI](https://github.com/RVC-Project/Retrieval-based-Voice-Conversion-WebUI)
(MIT, see `converter/rvc/LICENSE`). The app reimplements RVC's inference pipeline around ONNX exports of those models.

## Used by the app, not stored here

| Component | Source | License |
| --- | --- | --- |
| ONNX Runtime with the QNN execution provider | github.com/microsoft/onnxruntime (Android AAR `onnxruntime-android-qnn`) | MIT |
| Qualcomm AI Engine Direct (QNN) runtime libraries | bundled in the ONNX Runtime QNN package | their own license |
| ncnn (Android GPU decoder) | github.com/Tencent/ncnn | BSD-3-Clause |
| ContentVec (HuBERT base), as `hubert.onnx` | huggingface.co/lengyue233/content-vec-best | MIT |
| RMVPE, as `rmvpe.onnx` | huggingface.co/lj1995/VoiceConversionWebUI | MIT |
| PyTorch, Transformers, pnnx (converter only) | PyPI / download.pytorch.org (`converter/requirements.txt`) | their own licenses |

Voice models are not included. Use only models and voices you have the right to use.

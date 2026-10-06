# Notice

The PC converter (`converter/rvc/`) contains model code from
[RVC-Project/Retrieval-based-Voice-Conversion-WebUI](https://github.com/RVC-Project/Retrieval-based-Voice-Conversion-WebUI)
(MIT, see `converter/rvc/LICENSE`). The apps reimplement RVC's inference pipeline around ONNX exports of those models.

## Used by the apps, not stored here

| Component | Source | License |
| --- | --- | --- |
| ONNX Runtime | github.com/microsoft/onnxruntime (Android AAR, Swift package) | MIT |
| ncnn (Android GPU decoder) | github.com/Tencent/ncnn | BSD-3-Clause |
| ContentVec (HuBERT base), as `hubert.onnx` | huggingface.co/lengyue233/content-vec-best | MIT |
| RMVPE, as `rmvpe.onnx` | huggingface.co/lj1995/VoiceConversionWebUI | MIT |
| PyTorch, Transformers, pnnx (converter only) | PyPI / download.pytorch.org (`converter/requirements.txt`) | their own licenses |

Voice models are not included. Use only models and voices you have the right to use.

AI 模型放置与说明
==================

本目录已内置两个模型（已重命名）：

| 文件 | 用途 | 模型 | 输入 | 输出 |
|---|---|---|---|---|
| `denoise_model.tflite` | AI 去噪 | NafNet-SIDD width32 fp16（LiteRT 社区） | [1,3,256,256] NCHW float32 | [1,3,256,256] |
| `sr_model.tflite` | AI 4× 超分 | Real-ESRGAN-General-x4v3 float（Qualcomm AI Hub） | [1,128,128,3] NHWC float32 | [1,512,512,3] |

代码（AIDenoise / AISuperResolution）已按模型的实际固定输入尺寸做分块推理：
任意尺寸照片会 pad 到模型输入尺寸的整数倍，逐块推理后拼回再裁回原比例，
无需自己处理尺寸。

模型来源与下载
--------------
- 超分（float，4.9MB）：
  https://qaihub-public-assets.s3.us-west-2.amazonaws.com/qai-hub-models/models/real_esrgan_general_x4v3/releases/v0.45.0/real_esrgan_general_x4v3-tflite-float.zip
- 去噪（fp16，60MB，SIDD）：
  https://huggingface.co/litert-community/NAFNet-SIDD-width32-LiteRT/resolve/main/nafnet_sidd_width32_fp16.tflite

自行转换方法
------------
1) 超分（PyTorch → TFLite，ai-edge-torch）：
   pip install ai-edge-torch-nightly
   python:
     import ai_edge_torch, torch
     model = torch.load("realesr-general-x4v3.pth", map_location="cpu")
     model.eval()
     edge = ai_edge_torch.convert(model, (torch.randn(1, 3, 128, 128),))
     edge.export("sr_model.tflite")
   注意：原始 Real-ESRGAN 的 PReLU / PixelShuffle 在 TFLite 上可能不被支持，
   直接用上面的预导出版本最省事。

2) 去噪（ONNX → TFLite，onnx2tf）：
   pip install onnx2tf
   onnx2tf -i nafnet_denoise.onnx -o nafnet_tflite/ -osd
   注意：onnx2tf 2.6.7 能转换固定画布图，但 LiteRT 2.1.2 推理时可能报
   “input tensor lacks data”，建议直接用上面的预导出版本。

模型缺失/加载失败时应用照常运行：对应按钮显示「不可用」并置灰，不会崩溃。

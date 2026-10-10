# MultiCam —— 多摄多帧融合拍照 App

基于 Camera2 + OpenCV + TensorFlow Lite + ML Kit 的 Android 拍照应用：支持全像素单摄、
双/三摄并发连拍、时序降噪、像素级配准、多视角融合合成、AI 去噪、AI 4× 超分、
GL 实时调色预览（LUT）、手动对焦/锁焦、AI 辅助构图（实时）、RAW/DNG、多摄视频、全景拼接，
以及 **夜景 / 星空 / 时光慢门 / HDR / 人像虚化** 五个 vivo 风格拍摄模式。

> **本次更新（v2）**：①修复 20+ 处缺陷（3 处属「必崩/必失效」级别）；②新增 5 个拍摄模式
> 与底部横向快捷开关行；③APK 瘦身（移除未使用的 GPU 委托、只保留 arm64-v8a）。
> 原有功能（**含全景拼接**）全部保留，未删除任何特性。缺陷清单见文末 **第十节**，
> 新增模式见 **第二·五节**。

## 一、依赖和编译

- Android Studio Hedgehog 及以上、JDK 17
- OpenCV 首次同步下载约 100MB（依赖 `com.quickbirdstudios:opencv:4.5.3.0`，来自 Maven Central）
- minSdk 26；如需三摄并发建议 minSdk 30；如需全像素模式建议 minSdk 31
- 命令行构建：
  ```bash
  ./gradlew assembleDebug        # 产物在 app/build/outputs/apk/debug/app-debug.apk（已自动签名）
  ./gradlew assembleRelease      # 未开启混淆；已配置签名，产物可直接安装
  ```

### 体积说明

Debug APK 约 **106 MB**，构成如下（均为不可压缩内容，属正常）：

| 项 | 大小 | 说明 |
|---|---|---|
| `assets/denoise_model.tflite` | 59.6 MB | NafNet-SIDD 去噪模型，**红线：不可压缩、不可删** |
| `lib/arm64-v8a/libopencv_java4.so` | 16.4 MB | OpenCV 4.5.3 原生库 |
| `classes*.dex` | 10.5 MB | 业务代码 |
| `lib/arm64-v8a/libface_detector_v2_jni.so` | 8.0 MB | ML Kit 人脸检测 |
| `lib/arm64-v8a/libc++_shared.so` | 5.9 MB | C++ 运行时 |
| `assets/sr_model.tflite` | 4.7 MB | Real-ESRGAN x4v3 超分模型，**同样不可压缩** |
| `lib/arm64-v8a/libtensorflowlite_jni.so` | 3.7 MB | TFLite 运行时 |

本轮瘦身手段（均不影响功能）：
1. **移除 `tensorflow-lite-gpu`** —— 全项目没有任何 `GpuDelegate` 引用（只用 `NnApiDelegate`），省约 6 MB；
2. **`ndk { abiFilters 'arm64-v8a' }`** —— 砍掉 armeabi-v7a 的整套原生库（约省 40 MB+）；
   如需兼容 2016 年前的 32 位机，把 `'armeabi-v7a'` 加回 `app/build.gradle` 即可，代码零改动；
3. **`jniLibs { useLegacyPackaging = false }`** —— `.so` 不压缩存放（配合 `zipalign -p 4` 页对齐），
   安装时 mmap 直映，省解压耗时与运行内存；
4. `packagingOptions { exclude 'META-INF/*.kotlin_module' }` —— 剔除无用元数据。

> **不要动两个 `.tflite`**：`AIDenoise` / `AISuperResolution` 用
> `AssetFileDescriptor + FileChannel.map()` 零拷贝加载，一旦被压缩即抛
> `FileNotFoundException: ... probably compressed`，AI 功能整体失效。
> `androidResources { noCompress 'tflite' }` 是硬红线，请勿删除。

## 一之二、APK 签名（重要）

项目已内置签名配置，**debug 与 release 都会自动签名**，无需手动处理：

- 密钥库：`keystore/multicam.jks`（alias `multicam`，口令 `multicam123`）
- 签名方式：同时启用 **v1 + v2 + v3**，兼容 Android 7 ~ 14
- 配置位置：`app/build.gradle` 的 `signingConfigs.release` 与 `buildTypes`

**换成你自己的密钥**：把 `app/build.gradle` 里的 `storeFile / storePassword / keyAlias / keyPassword` 改成你的值即可；正式发布建议改用 `keystore.properties` 外部读取，不要把口令写进版本库。

**为什么必须签名**：未签名的 APK 在 Android 上无法安装（报「解析包时出现问题」或「未签名」）。手动重新打包（例如剥离 `armeabi-v7a` 减小体积）会破坏原有签名，**必须重新签名**，否则装不上。正确流程：

```bash
# 1. 剥离 ABI（如需减小体积）
# 2. zipalign 4 字节对齐
zipalign -f -p 4 input.apk aligned.apk
# 3. 重新签名（v1/v2/v3）
apksigner sign --ks keystore/multicam.jks --ks-key-alias multicam \
  --ks-pass pass:multicam123 --key-pass pass:multicam123 \
  --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
  --out output-signed.apk aligned.apk
# 4. 验证（务必执行）
apksigner verify --print-certs -v output-signed.apk
```

> 注：`resources.arsc` 必须保持**不压缩**（Stored）；`assets/*.tflite` **必须保持不压缩**——
> `AIDenoise` / `AISuperResolution` 用 `AssetFileDescriptor + FileChannel.map` 加载模型，
> 一旦被压缩就会抛 `FileNotFoundException: ... probably compressed`，AI 功能直接失效。
> 若需缩小体积，可压缩 `lib/**/*.so`（`useLegacyPackaging = false`），但**不要动 tflite**。

## 二、界面说明

- 顶部左：App 名「MultiCam」；顶部右：模式胶囊（全像素 / 多摄）
- 顶部中：状态胶囊，显示当前模式 + 能力摘要
- 中央：GLSurfaceView 实时预览；点击预览区域手动对焦（对焦环跟随手指）
- 底部左侧：最近一张照片缩略图（点击直接打开图库）
- 底部右侧：最近一次保存的 Uri 末段
- 底部中间：白色圆形快门，点击有缩放动画
- **底部横向快捷开关行**（新增，位于变焦条与快门之间，可横向滚动）：
  画质优先 / AI 画质 / 降噪 / RAW / 网格 / 构图 / 自动变焦 / 自动调色 / 高像素 / 镜像 / 光轨 / 丝绢 / 虚化强度
  —— 与设置面板**双向同步**：快捷行改状态会写入 `SettingsStore`，设置面板同步刷新；反之亦然。
- 模式条（横向可滚动）：拍照 / 人像 / 夜景 / 录像 / 专业 / 全景 / 超级月亮 / 星空 / 时光慢门 / HDR / 高像素
- 参数行（横向可滚动）：画质优先 / 帧数 / 方向 / 镜像 / AI / 去噪 / 镜头 / 导出日志 / 调色 / 构图 / RAW / 录像 / 全景 / 任务 / 自动变焦 / 自动调色
- 帧数支持 1 / 3 / 5 / 7 循环
- AI 4× 超分：`assets/sr_model.tflite`（Real-ESRGAN x4v3，输入 [1,128,128,3] NHWC float32，输出 [1,512,512,3]）
- AI 去噪：`assets/denoise_model.tflite`（NafNet-SIDD fp16，输入 [1,3,256,256] NCHW float32）
- 两个模型已内置在压缩包 assets 中；模型缺失时对应按钮置灰「不可用」，不影响使用
- 「镜头」切换超广角 / 主摄 / 长焦（自动回主摄全像素或指定镜头单摄）
- 「调色」展开调色盘：盘内拖动调色调/影调，外环调饱和度；GL 实时预览 LUT，同时作用于保存的 JPEG；关闭调色自动恢复默认参数
- 「构图」开启 AI 辅助构图：预览实时分析（每 400ms）——地平线歪斜提示左右转、引导线提示主体位置并建议变焦、三分法偏离提示（风景优先，不再只认人脸）
- 「自动变焦」开启后，构图建议里的引导线变焦建议自动执行（SCALER_CROP_REGION 数字变焦）
- 「自动调色」拍摄时自动分析亮度/色温/饱和度/对比度生成调色参数（手动调色盘参数优先）
- 「任务」查看后台 AI 处理任务进度（原图立即保存，AI 结果后台出，两份都会入相册）
- 「RAW」开启后拍照同时输出 DNG（DngCreator 写全元数据，DCIM/MultiCam，需设备支持 RAW_SENSOR）
- 「录像」多摄同步录像（每镜头一路 MP4，第一个摄像头同时出预览，保存到 App 外部目录）
- 「全景」进入全景模式后缓慢平移点拍多次，再点一次结束并自动拼接（ORB 累积单应）
- 「导出日志」把 App 运行日志（含 AI 模型/相机/处理链路）导出到 Downloads/MultiCam 并打开
- 所有开关（模式/帧数/方向/镜像/AI/去噪/镜头/RAW/构图/构图锁定/自动变焦/自动调色）自动保存，下次启动恢复
- 处理中会弹出全屏半透明遮罩 + 进度文案

## 二·五、新增的 5 个拍摄模式

所有模式共用一条采集链路：**`BracketPlan`（逐帧曝光计划）→ 相机逐帧下发不同 ISO/快门/EV →
JPEG 帧聚合 → 对应处理器融合 → 保存**。

```
MainActivity.planForMode(模式名)          // 生成逐帧曝光计划
      ↓  pushControls(plan)
CameraController.applyControls(CameraControls(bracket = plan))
      ↓
FullResCaptureSession.capture()           // repeat(N) 逐帧发请求
      ↓  applyControlsTo(builder, frameIndex = i, plan)
  plan.isBracketed → CONTROL_AE_MODE_OFF + SENSOR_SENSITIVITY + SENSOR_EXPOSURE_TIME
  否则             → CONTROL_AE_EXPOSURE_COMPENSATION = plan.evAt(i)
      ↓  ImageReader 收帧（acquireNextImage 循环，不丢帧）
  bracketBuf 聚合 N 帧 → onBracketReady(frames)
      ↓
MainActivity.handleBracketResult(模式名, frames)   // 后台线程分派
```

### 1. 夜景（6 帧）

- 曝光计划：ISO 400/800，快门 120ms / 240ms / 360ms 交错
- 处理：`NightProcessor.merge()` → 复用 `TemporalDenoiser`（多帧对齐 + 时域加权平均）
- 效果：显著降噪、暗部提亮，适合手持夜景

### 2. 星空（4 帧，需长曝光）

- 曝光计划：ISO 提升至主摄上限的 2×，快门 1s / 2s / 3s / 4s
- 处理：`AstroProcessor.stack()` = 时域堆栈 → **去暗底**（逐通道 5% 分位直方图）→
  **对比拉伸**（逐通道 2% / 99.2% 分位线性映射）
- 可用性：`CameraCapabilities.longExposureSupported`（= `SENSOR_INFO_EXPOSURE_TIME_RANGE.upper > 0`）
  为 false 时模式置灰。**注意：Camera2 没有 `SENSOR_INFO_MAX_EXPOSURE_TIME` 这个常量**，
  曝光上限只能从 `SENSOR_INFO_EXPOSURE_TIME_RANGE` 的 `upper`（单位 ns）取。

### 3. 时光慢门（8 帧，两个子档）

- 子档由底部快捷行的「光轨 / 丝绢」切换，选择会持久化
- **光轨**（`Style.LIGHT_TRAIL`）：`Core.max(base, aligned)` 逐像素取最大 →
  车流/星轨拉出连续光带
- **丝绢**（`Style.SILK`）：逐帧在线均值 `Core.addWeighted(acc, (n-1)/n, frame, 1/n, 0.0, acc)` →
  流水/瀑布呈丝滑质感
- 快门 0.5s / 1s / 1.5s … 递增

### 4. HDR（7 帧）

- 曝光计划：EV `[-2, -1, 0, +1, +2, 0, +0.5]`，走 AE 自动曝光包围（不锁 AE_OFF）
- 处理：`HdrProcessor.merge()` —— 曝光融合近似（Mertens 不在 Android OpenCV 绑定内）：
  逐帧转灰度 → 中间调加权 `w = 1 − |2g − 1|` → 归一化后加权求和
- 效果：高光不过曝、暗部有细节，直出宽容度明显提升

### 5. 人像虚化（单帧 + 软件虚化）

- 采集中间帧为基准 → `CompositionAnalyzer.detectFaceBoxes()`（ML Kit）拿人脸框
- `PortraitBokehProcessor.render()`：画保护椭圆掩膜（人脸框宽 ×1.25 / 高 ×1.8）→
  高斯羽化 → 整图高斯模糊 → 按掩膜做 `sharp·α + soft·(1−α)` 融合
- 无检出人脸时退化为中心椭圆（主体居中假设）
- 快捷行「虚化强度」三档循环 25% → 55% → 90%，映射到高斯核半径 7 ~ 45

### 模式可用性判定

| 模式 | 可用条件 |
|---|---|
| 拍照 / 专业 / 全景 / HDR / 人像 / 高像素 / 夜景 | 恒可用（纯软件实现） |
| 星空 / 时光慢门 | `longExposureSupported`（曝光时间上限 > 0） |
| 录像 | 并发摄像头 ≥ 2 |
| 超级月亮 | 仍置灰：依赖厂商月亮识别与长焦算法，无通用实现路径 |

不可用的模式点击会 Toast 具体原因，不会静默无响应。

## 三、预览方向校正

- 读取后置摄像头 `SENSOR_ORIENTATION` 作为基础旋转（默认 90°）
- 点击「方向 X°」每次 +90°，叠加到基础旋转
- 「镜像」开关做水平翻转
- 预览由 GLSurfaceView 渲染（外部 OES 纹理 + 512×512 LUT），旋转/镜像/等比裁切在顶点着色器 UV 中完成
- **触摸对焦**与**构图叠加层**都按同一套 center-crop 几何 + 传感器方向做坐标映射，与预览严格对齐

## 四、交互与处理流程

手势：
- 单击预览：对焦 + 锁定 AE（对焦环动画）
- 长按 600ms：锁定 AF/AE（再次长按解锁）

多摄拍照：
1. 点快门 → 显示「拍摄中…」遮罩
2. 每颗镜头连拍 N 帧
3. TemporalDenoiser 时序降噪 → 「时序降噪中…」
4. ImageAligner 像素级配准 → 「像素级配准…」
5. ImageCompositor 融合 → 「融合合成…」
6. 调色（手动/自动）→ 保存「composed」到相册
7. 若开启 AI：原图/AI 结果分两份保存（_raw.jpg 立即存、_ai.jpg 后台 BackgroundProcessor 出），点「任务」可看进度

单摄 / 全像素模式：先保存「_raw.jpg」原图，AI 去噪 → AI 4× 超分 → 调色后台出「_ai.jpg」。
RAW 开启时：JPEG 与 RAW 同时提交，RAW 通道用独立 CaptureResult 写 DngCreator，保存到 DCIM/MultiCam。
自动调色开启时：拍照后按画面亮度/色温/饱和度/对比度生成调色参数（手动优先）。

构图实时分析：640×480 YUV 流（已挂进预览重复请求），SceneAdvisor 每 400ms 输出建议覆盖层；
「自动变焦」开启时引导线建议自动调用 setZoom。

去噪在超分之前，避免噪点被超分模型当作细节放大。

## 五、全像素模式

- 停预览 → 拍全像素 → 恢复预览 → 保存
- 无合成步骤，直接保存 JPEG（可走 AI 去噪 / 超分 / 调色）
- 快门带防抖（全像素 900ms / 单摄 400ms 最小间隔），避免连点导致 HAL 报错

## 六、容错

- 打开相机 / 会话配置 / 拍照失败：3 次重试
- 特征点不足 / 单应失败 / 有效区 < 5%：退化到等比例居中缩放
- 时序降噪失败：直接用首帧
- AI 模型缺失 / 加载失败 / 推理失败：对应开关置灰或静默跳过，不崩溃
- AI 模型启动时打印 input/output shape+dtype 日志（App 内「导出日志」可看）；支持 FLOAT32/UINT8/INT8 输入输出分派，输出统一 0–1 → 0–255
- AI 超分采用**分块推理 + 双三次底图兜底**：任意分块失败/尺寸异常时该块保留底图，不会出现黑边或撕裂
- 构图实时分析失败：静默跳过该帧，不影响拍照
- 录像会话建立失败/摄像头出错：自动回收并提示，不会卡在「录制中」
- **包围曝光超时兜底**：部分机型在 `AE_MODE_OFF` 下会吞帧，若在
  `快门间隔 × 帧数 + 3s` 内未收齐，则用已收到的帧降级处理并打日志，UI 绝不卡死
- **模式处理防重入**：`modeProcessing` 标志保证上一轮融合未结束时丢弃新一轮帧，避免 OOM
- **模式融合失败**：任一步抛异常都退回该批首帧并照常保存，不会丢照片

## 七、已知限制

- 全像素在多数第三方 App 上仍可能被 HAL 拒绝；实际输出大小以 onResult 的字节数为准
- 三摄并发分辨率通常被 HAL 限制在 1080p～1440p
- 合成是几何近似的像素级配准，非严格多视角融合
- OpenCV findHomography 需要场景有足够纹理，纯色/低纹理场景退化到居中缩放
- RAW_SENSOR 部分机型不支持（自动跳过，Toast 提示）；RAW+全像素组合可能被 HAL 拒绝
- 多摄视频受 HAL 限制，部分机型只支持单路编码，会降级/失败
- 全景基于 ORB 累积单应（OpenCV Stitcher 模块不在 Android 绑定内），大幅平移或低纹理场景可能拼接失败
- 构图实时分析使用 640×480 YUV 下采样帧，对低端机 CPU 有一定占用（400ms 间隔）
- 自动变焦为数字变焦（SCALER_CROP_REGION），依赖 SceneAdvisor 检出引导线；低纹理场景可能无建议
- 自动调色基于整帧统计（平均亮度/色温/饱和度/对比度），极端场景（大面积单色）可能误判
- GL 预览 LUT 为近似采样（非逐像素精确），最终保存以 OpenCV 色彩矩阵结果为准
- ML Kit 人脸模型首次使用可能需联网下载（约 2MB），无网络时自动退回规则版
- **星空 / 时光慢门**依赖设备开放长曝光：多数手机 `SENSOR_INFO_EXPOSURE_TIME_RANGE.upper`
  只有几十毫秒，此时两模式按预期置灰（这是 HAL 限制，不是 App 缺陷）
- **包围曝光在 `AE_MODE_OFF` 下部分 HAL 会拒帧或吞帧**，App 已在超时后降级用实际收到的帧处理，
  帧数可能少于计划值
- **HDR 是曝光融合近似**（中间调加权），不是完整的 Mertens/拉普拉斯金字塔多带融合，
  因为 Android 版 OpenCV 绑定不含 `photo` 模块
- **人像虚化为纯软件实现**：基于单帧 + 人脸检测掩膜，没有深度图参与，
  发丝边缘与前景遮挡处的过渡不如厂商双摄方案；人脸检测失败时退化为中心椭圆
- **夜景 / 星空 / 慢门均假定拍摄期间机位基本静止**，手持强烈抖动会超出
  `ImageAligner` 的对齐能力，表现为轻微重影

## 八、工程结构

- `settings.gradle` / 根 `build.gradle` / `gradle.properties` / `app/proguard-rules.pro` / Gradle Wrapper
- `keystore/multicam.jks`：内置签名密钥
- `app/src/main/res/layout/activity_main.xml`：主界面（含底部横向快捷开关行 `quickRow`）
- `app/src/main/java/com/example/multicam/`（共 40 个文件）：
  - **采集层**：`CameraController` / `FullResCaptureSession` / `MultiConcurrentSession` /
    `MultiVideoSession` / `CameraCapabilityDetector` / `CaptureStrategy` / `CameraControls` /
    `BracketPlan`（逐帧曝光计划）
  - **新增模式处理器**：`NightProcessor` / `AstroProcessor` / `LongExposureProcessor` /
    `HdrProcessor` / `PortraitBokehProcessor`
  - **图像处理**：`ImageAligner` / `ImageCompositor` / `TemporalDenoiser` / `ColorGrader` /
    `AIDenoise` / `AISuperResolution` / `PanoramaStitcher` / `MatUtils` / `LutBuilder`
  - **分析与辅助**：`SceneAdvisor` / `CompositionAnalyzer` / `AutoColorAdvisor` /
    `CompositionOverlayView` / `OrientationTracker` / `BackgroundProcessor` / `FileSaver` /
    `SettingsStore` / `AppLogger`
  - **视图**：`MainActivity` / `PreviewRenderer` / `ColorWheelView` / `GridOverlayView` /
    `CompositionPanelView` / `FilterPreset` / `FilterPresets`
- `app/src/main/assets/`：两个 TFLite 模型 + `README.txt`（模型规格说明）
- 其余为业务代码与资源

## 九、v2 新增内容速览

| 项 | 文件 | 说明 |
|---|---|---|
| 逐帧曝光计划 | `BracketPlan.kt` | `evSteps` / `isoSteps` / `shutterNsSteps` / `frameCount` |
| 多帧采集 | `FullResCaptureSession.kt` | `capture()` 逐帧发请求；JPEG 监听改 `acquireNextImage` 循环；帧聚合 + 超时兜底 |
| 多帧采集（多摄） | `MultiConcurrentSession.kt` | 同上；ImageReader 队列按包围帧数扩容 |
| 模式分派 | `MainActivity.kt` | `planForMode()` / `handleBracketResult()` / `pushModePlan()` |
| 底部快捷行 | `MainActivity.kt` + `activity_main.xml` | `QuickToggle` / `quickToggles()` / `buildQuickRow()` |
| 能力探测 | `CameraCapabilityDetector.kt` | `maxExposureNs` / `isoRange` / `depthSupported` |
| 新持久化项 | `SettingsStore.kt` | 慢门子档、人像虚化强度 |
| 人脸框接口 | `CompositionAnalyzer.kt` | `detectFaceBoxes()`（软件虚化用） |
| 瘦身 | `app/build.gradle` | 移除 GPU 委托、仅 arm64-v8a、jniLibs 不压缩、noCompress tflite |

## 十、修复记录

> 以下均为本轮实际修改的缺陷，按严重度排列。所有修复都只改实现、不改功能语义，
> **原有功能（含全景拼接 `PanoramaStitcher`）一个都没有删除**。

### P0 —— 必崩 / 功能完全失效

| # | 文件 | 问题 | 修复 |
|---|---|---|---|
| 1 | `ImageCompositor.kt` | `Core.multiply(base(8UC3), invAlpha(32FC3))` 类型不一致，OpenCV 必抛 `CvException`；异常被外层 catch 吞掉后返回首帧原图 → **多摄合成功能完全无效** | 统一在 CV_32FC3 域内做加权，最后 `convertTo` 回 8UC3 |
| 2 | `ColorGrader.kt` | `p.isDefault` 时 `return input` 返回入参别名，调用方随即 `graded.release()` → native use-after-free 崩溃/花屏 | 改为 `return input.clone()` |
| 3 | `AIDenoise.kt` | 模型未就绪时 `return input`，调用方 `denoised.release()` 释放掉自己的 Mat → use-after-free | 改为 `input.clone()` |
| 4 | `AISuperResolution.kt` | 同上，`return input` 别名导致 use-after-free | 改为 `input.clone()` |

### P1 —— 结果错误 / 内存泄漏

| # | 文件 | 问题 | 修复 |
|---|---|---|---|
| 5 | `AISuperResolution.kt` | **「超分图七零八碎」的根因**：`sr_model.tflite` 输入是固定 [1,128,128,3]，旧代码却把 512+ 的读块（padding 到 32 倍数，如 544×544）直接喂进模型，尺寸不匹配 → 输出错乱；写回坐标在边缘块越界；最后还有一步「降采样再缩回」把细节毁掉 | 重写 `upscale()`：双三次底图铺满 → 读块(含 16px overlap) → 缩放到模型输入尺寸推理 → 缩回读块×scale → 严格算核心区偏移写回（带边界防御）；删除破坏性的降采样步骤 |
| 6 | `AISuperResolution.kt` | FLOAT32/UINT8 正常分支下 `rgb` 中间 Mat 从不释放，每块泄漏一张模型尺寸 Mat | 在填完输入缓冲后统一 `rgb.release()` |
| 7 | `ImageAligner.kt` | 单应矩阵 `h` 与形态学核 `k` 从未 release → 每次对齐泄漏两块 native 内存，连续对齐几十次即撑爆 native heap | 提升到 try 外，`finally` 中 `runCatching { release() }` |
| 8 | `ImageAligner.kt` | `MatOfPoint2f`（srcPts/dstPts）同样未释放 | `finally` 中释放 |
| 9 | `ImageAligner.kt` | `fallbackResize` 在 target 尺寸为 0 时算出 Infinity → `toInt()` 得 `Int.MAX_VALUE` → resize 崩溃 | 前置校验尺寸 + `scale.isFinite()` 判断 + `coerceIn(1, base)` |
| 10 | `TemporalDenoiser.kt` | `res.mask.convertTo(mask32, CV_32FC1)` 未做 `1/255` 归一化，mask 是 0/255 而非 0/1 → 每帧权重被放大 255 倍，参考帧相对权重可忽略 → **时序降噪实际只取了最后一帧，且引入拖影** | `convertTo(..., 1.0/255.0)` 显式归一化 |
| 11 | `MatUtils.kt` | EXIF `FLIP_HORIZONTAL/VERTICAL/TRANSPOSE/TRANSVERSE` 只做 `postScale(-1f, 1f)`，未平移 → 图像被翻到负坐标区，得到空白/错位图 | 用 pivot 版本 `postScale(sx, sy, px, py)` / `postRotate(deg, px, py)` 按 Exif 标准重写 |
| 12 | `ColorGrader.kt` | `toneOffset`（0..1 尺度）与 `Core.transform` 的像素尺度（0..255）混用，影调偏移几乎不可见 | 统一到 0..255 像素尺度：对比度项 ×255，影调系数 25 → 64 |
| 13 | `MultiConcurrentSession.kt` | 连拍用 `acquireLatestImage()`，会丢弃队列中所有旧帧只留最新一帧 → 连拍 N 帧实际只拿到 1~2 帧，`onAllFrames` 可能永不触发 → **多摄合成长时间卡在「处理中」** | 改为 `acquireNextImage()` 循环取空队列 |
| 14 | `MultiConcurrentSession.kt` | `applyControls()` 未设置 `SCALER_CROP_REGION`，任何控制项变更都会把数码变焦重置回 1× | 补上 `set(SCALER_CROP_REGION, computeCropRegion(currentZoom))` |
| 15 | `MainActivity.kt` | `calculateFocusRect` 忽略 `SENSOR_ORIENTATION`、预览 center-crop 与镜像 → **点左上角对焦到右上角** | 重写：触摸点 → 归一化 → 反 center-crop → 按传感器方向旋转 → 镜像 → 映射到 −1000..1000 |
| 16 | `CompositionOverlayView.kt` | 叠加层按全屏宽高缩放 640×480 分析坐标，与预览裁剪不一致 → 瞄准环整体偏移 | 新增 `contentRect`/`analysisW/H`，先映射到预览内容区再映射到 View |
| 17 | `MainActivity.kt` | `savePreferQuality` / `saveFrames` / `savePreferredRole` 定义了但从未调用；`rawEnabled` / `compositionEnabled` / `compositionLocked` 连 key 都没有 → **改完设置冷启动全丢** | 补齐 `SettingsStore` 的 RAW/构图/构图锁定 key，并在 `restoreSettings()` 与各开关回调中读写 |
| 18 | `MainActivity.kt` | 录像模式下 `videoSession` 从未实例化，点快门只执行空操作 → **录像功能完全没有反应** | 新增 `startVideo()` / `stopVideo()`，在 `switchMode("录像")` 时真正建立 `MultiVideoSession`（含 1080p 尺寸协商、错误回收、保存提示） |
| 19 | `MainActivity.kt` | `panoramaFrames` 是普通 `ArrayList`，但回调来自后台线程且多摄下并发进入 → 并发 `add` 抛 `ArrayIndexOutOfBounds` / 丢帧 | 改用 `Collections.synchronizedList`，并先取值再做 UI 提示 |

### P2 —— 稳定性 / 体验

| # | 文件 | 问题 | 修复 |
|---|---|---|---|
| 20 | `AIDenoise.kt` | `Mat(h, w, CV_8UC3)` 未初始化，被跳过的分块留下未定义脏像素（花屏噪点） | 改为 `Mat.zeros` |
| 21 | `AIDenoise.kt` | `w <= OVERLAP` 时 `((w-OVERLAP)+step-1)/step` 得 0 列 → 整图不处理 | `coerceAtLeast(1)` |
| 22 | `FullResCaptureSession.kt` | 快门无节流，连点/长按会交错提交多组 STILL_CAPTURE → `CAMERA_ERROR` 或队列堵死卡在「处理中」 | 新增 `capturing` 标记 + 最小间隔（全像素 900ms / 单摄 400ms） |
| 23 | `BackgroundProcessor.kt` | `onJobsChanged` 是进程级单例上的公有 `var`，MainActivity 直接赋 lambda → **Activity 泄漏**，回调还会碰已销毁的 View | 改为 `setOnJobsChanged()` + `WeakReference` 包装，`onDestroy` 中置空 |
| 24 | `FileSaver.kt` | 新增 `videoDir()`：API 29+ 用 app 专属 Movies 目录（免权限），API 26–28 用 `DCIM/MultiCam` | 为录像提供合法输出路径 |

## 十一、第二轮全量排查修复记录

> 第二轮针对「运行时不出 bug」做了一次完整扫描（并发 / 生命周期 / 崩溃 / Mat 内存 /
> OpenCV 绑定 / 数值正确性六个方向），共确认并修复 13 项。以下按严重度排列。
> 同样**不删除任何功能**，全景拼接 `PanoramaStitcher` 完整保留。

### 关键结论：模型布局实测

用 `tflite` Python 绑定直接读取 flatbuffer，得到真实 shape（不再靠猜）：

| 模型 | 输入 | 输出 | 布局 |
|---|---|---|---|
| `denoise_model.tflite` | `[1,3,256,256]` | `[1,3,256,256]` | **NCHW**（输入输出都是） |
| `sr_model.tflite` | `[1,128,128,3]` | `[1,512,512,3]` | NHWC |

这是下面 #31 的判定依据。反交织算法已用 numpy 独立复算验证（还原后与原图逐元素相等）。

### P0

| # | 文件 | 问题 | 修复 |
|---|---|---|---|
| 31 | `AIDenoise.kt` | **NCHW 输出被当作 HWC 读取**。模型输出是 `[R平面\|G平面\|B平面]`，代码在输入侧正确拼了平面（`extractChannel`），输出侧却直接把扁平数组按交错像素读 → **AI 去噪出图通道错位、画面撕裂** | 新增 `outNCHW` 独立判定（按输出 shape 而非沿用输入），对 FLOAT32 / UINT8 分支都做反交织；同时把输出归一化量级改为自适应（`peak <= 1.5` 判定 0..1 还是 0..255），不再硬编码 ×255 |

### P1

| # | 文件 | 问题 | 修复 |
|---|---|---|---|
| 32 | `MultiConcurrentSession.kt` | 凑批阈值恒为 `framesPerCamera`，而 `capture()` 在包围模式下会用 `plan.effectiveFrames`（HDR 7 帧 / 星空 4 帧）→ 期望 > 阈值时**永不触发 `onAllFrames`**，按快门后一直卡「处理中」 | 新增 `expectedPerCamera`，`capture()` 先写入期望值再发请求，`handleFrame` 按它判定；另补 `flushIfTimeout()` 超时降级 |
| 33 | `FullResCaptureSession.kt` | 包围超时按 `captureMinIntervalMs × 帧数` 估算，但慢门 8 帧 × 最长 4s 曝光实际需 18s+，而超时仅 10.2s → **误判超时、提前降级丢帧** | 改为逐帧累加真实曝光时长 + 逐帧落盘余量 + 3s 兜底 |
| 34 | `FullResCaptureSession.kt` | 单张拍照路径无超时兜底，且 `capturing` 只在 `onCaptureCompleted` 复位；该回调因断流/抢占丢失时 → **快门永久失效，点了没反应** | 新增 `clearCaptureGuardIfIdle()`；并让 `dispatchJpeg` 在非包围路径首个 JPEG 到达时即复位 |
| 35 | `MainActivity.kt` | `onBracketFrames` 回调里读可变的 `currentMode`，采集期间切换模式会把 HDR 的帧交给夜景处理器 → **出图与所选模式不符** | 新增 `bracketModeSnapshot`，在 `pushControls()` 发起时固化，回调只读快照 |
| 36 | `MainActivity.kt` | `onPause()` 清空 `previewSurface`，而恢复依赖 GLSurfaceView 重发 `onSurfaceTextureReady`；该 SurfaceTexture 被复用时不重发 → **回前台后预览永久黑屏** | `onResume()` 直接用仍存活的 `glSurfaceTexture` 重建 Surface 并启动相机 |
| 37 | `MainActivity.kt` | `switchMode("录像")` 先 `startVideo()` 再 `restartCamera()`，两者用**同一 cameraId** 各开一个会话 → 互相 `onDisconnected` 抢占，开始录制即断流 | 录像模式跳过 `restartCamera()`；并在 `startVideo()` 开头回收旧会话 |
| 38 | `MainActivity.kt` | 相机回调与 `BackgroundProcessor` 是进程级线程，Activity 销毁后仍会 `runOnUiThread` 操作已销毁 View → 崩 | 新增 `thisDestroyed` + `thisDone()`（含 `isFinishing`/`isDestroyed`），26 处跨线程回 UI 入口全部加守卫 |
| 39 | `MainActivity.kt` | 人脸检测 `boxed.await(2s)` 超时后无条件 `bmp.recycle()`，而 ML Kit 异步回调可能仍在读该 Bitmap → 回收后使用导致**原生崩溃** | 仅在回调完成后回收；超时则跳过回收（宁可小泄漏不崩溃），改用单元素数组承载跨线程结果 |

### P2

| # | 文件 | 问题 | 修复 |
|---|---|---|---|
| 40 | `LongExposureProcessor.kt` | `base` 既是配准基准又是累加目标，第 2 帧起所有帧都在与「已合成结果」做配准 → **基准逐帧漂移**，光轨/丝绢出现拖尾重影 | `ref` 固定为第 0 帧（只读），累加写入独立 `acc` |
| 41 | `LutBuilder.kt` | 影调系数 `tone * 25/255`，而 `ColorGrader` 用 `tone * 64/255`（相同参数效果差 ~2.5 倍）→ **实时预览与成片不一致，所见非所得** | 统一为 `64f / 255f` |
| 42 | `MainActivity.kt` | 能力探测失败时 `isoRange` 为 `0..0`，`coerceIn(0,0)` 把逐帧 ISO 全压成 **0**，而 `SENSOR_SENSITIVITY=0` 是非法值 → 夜景/星空/HDR 拍出纯黑 | 新增 `safeIsoRange()` 兜底 `100..3200`；`planForMode` 的快门时长也改为受 `maxExposureNs` 约束并保证 ≥1ms |
| 43 | 多处 | **Mat 泄漏与杂项**：`AutoColorAdvisor` 的 `MatOfDouble` 从不释放；`yuvToGray` 异常路径泄漏 `gray` 且 `rowStride*h` 越界可抛异常；`PanoramaStitcher` 的 `cums`/`shift`/`h`/形态学核全未释放；`AISuperResolution.runTile` 在模型未就绪时 `return tile` 返回入参别名（调用方随即 release → use-after-free）；`AIDenoise` 末行末列 <4px 留黑边、降采样路径不回调 100、异常路径 `outMat`/`src` 泄漏；对焦取消 runnable 作用于已关闭 session | 逐处补 `finally` 释放、加 `focusCancelToken` 代际号作废过期任务、边界窗口回退凑满 4px、回调补 100%、`runTile` 改 `tile.clone()` |

---

## 十二、AI 构图重写（参考 DOKA 相机）

### 为什么重写

原实现的做法是「算出一个三分点 → 在屏幕上画一个小瞄准环 → 用一段文字告诉用户"把主体移到三分点"」。
问题有三：

1. **用户不知道"三分点"在哪**，只有一个点看不出取景范围；
2. **对齐与否没有即时反馈**，全靠文字理解；
3. **没有焦段建议**，主体占画面过小时用户不会主动推近。

### DOKA 的交互链路（本次调研结论）

| 阶段 | DOKA 的做法 | 本实现对应 |
|---|---|---|
| 1. 触发 | 点击「AI 构图」，扫描一帧 | 顶栏新增 **[AI 构图] 圆形按钮**，点击即扫描 |
| 2. 引导 | 生成**推荐取景框** + AR 引导线 | `targetBox` 虚线框 + 四角 L 形标记 + 主体→框中心的动态箭头 |
| 3. 对齐 | 移动手机使主体进入框 | `alignProgress` 0..1，框内叠加三分线辅助 |
| 4. 达标 | 引导线**变绿**，提示可以拍 | `aligned=true` 时全部元素切绿色 `#4CAF50`，箭头收起，画对勾 |
| 5. 焦段 | 按主体占比推荐焦段 | 右下角「建议 X×」标签，吸附到 1/1.5/2/3/4/5/6 档 |

### 实现要点

**`SceneAdvisor.kt`（核心逻辑）**

- **`choosePlan()`** 按优先级选法则并生成推荐框：
  - 主体距中心 < 0.12 → **对称构图**（框居中）
  - 有引导线且主体偏离 > 0.15 → **引导线构图**（框放主体侧三分点，缩至 92%）
  - 兜底 → **三分法**（框吸附最近三分点）
  - 框尺寸固定为画面宽 62%、高 72%（3:4 比例），`boxAt()` 自动钳制在 2% 安全边距内
- **`alignRatio()`** 分轴计算对齐进度：`1 - |位移| / 半宽`，值域钳制 `[0,1]`
- **`aligned = inBox && alignProgress >= 0.8`** —— 阈值 0.8 是刻意的：留 20% 容差，避免用户反复微调到不可能达成的完美值
- **`recommendZoomFor()`** 以主体占比 12% 为理想值，`sqrt(ideal/area)` 推算倍率，按档位吸附且差异 <0.15 时不打扰用户
- **`buildArLines()`** 生成「主体当前位置 → 推荐框中心」的主引导线 + 四角辅助线
- 新增 **`findSubject()`** 用 Canny 边缘矩算重心，并对贴边重心做中心加权收缩，同时用 m20/m02 估等效半径供焦段推荐使用
- 新增分轴 EMA 平滑 **`smoothAxis()`**（`smoothSubX`/`smoothSubY`），避免一轴抖动污染另一轴

**`CompositionOverlayView.kt`（叠加层）**

- 推荐框：虚线圆角矩形 + 四角 L 形加粗（DOKA 取景框标志性外观），未对齐时框内叠三分线
- AR 引导线：实线主引导线带箭头，随 `alignProgress` 提升逐渐变淡；辅助线用更淡的短虚线只保留外侧一段，避免框内杂乱
- 对齐后：框变绿色实线，中心画绿色圆圈 + 对勾
- 法则名标签在框上方（深色圆角背景），焦段推荐在右下角（蓝色标签）
- 保留原有地平线参考线与坐标映射修复（分析坐标 → 预览内容区 → View）

**`CompositionPanelView.kt`（面板）**

- 新增「对齐」进度条（分轴双色刻度：横/纵各一条）
- 标题右侧显示「✓ 构图合适」或法则名
- 高度 260 → 330，XML 同步

**`MainActivity.kt` 接线**

- 顶栏新增 `btnComposition` 按钮，开启时高亮 + Toast 提示，关闭时 `forceClear()`
- `compositionOverlayView.forceClear()` 新增：冻结状态下也能清空（原 `update(null)` 被 `frozen` 拦截）
- 新增 `lastCompositionRule` 快照，锁定后仍在面板上展示法则名
- `restartCamera()` 的 `compositionOverlay` 回调透传全部 9 个新字段
- 快速开关行「构图」与设置项「构图辅助」同样更新按钮高亮与跟踪重置

**`FullResCaptureSession.kt` / `MultiConcurrentSession.kt`**

- `toSuggestion()` 全部补齐新字段透传（多摄侧原先只传了 type/confidence/guidePoints/message，缺 score/tilt/subject/hint，属于顺带修复的遗漏）

### 验证

- `:app:compileDebugKotlin` 与 `:app:assembleDebug` 均 **BUILD SUCCESSFUL**（APK 111,728,001 字节）
- 旧函数 `edgeCentroid` / `Thirds` / `thirdsAdvice` / `directionHint` 已删除，无残留引用
- 用 Python 独立复算决策数学，5 组共 20 个用例全部通过：
  1. `boxAt` 钳制 —— 6 个边界中心点，框均完整落在 `[0,1]` 内
  2. `alignRatio` 单调性 —— 位移递减时进度单调递增，超界归零
  3. `aligned` 判据 —— 4 组主体位置，对齐/未对齐判定与预期一致
  4. `choosePlan` 分流 —— 5 组，对称/引导线/三分法分流正确
  5. `recommendZoom` —— 4 组，结果全部落在合法档位

---

# third/ — 第三方开源组件（vendored）

> 规则：**开源代码一律进本目录，工程内通过相对路径引用，不拷贝进源码树**。
> 升级组件 = 替换本目录内容 + 更新下方版本记录，工程无需改动。

---

## 1. sherpa-onnx（语音推理库，MIT）

**用途**：端侧 ASR / TTS / 声纹 / VAD 全家桶（C++ 核心 + Kotlin 封装）。

| 项 | 值 |
|----|----|
| 上游 | https://github.com/k2-fsa/sherpa-onnx |
| 版本 | **v1.13.2**（与 android-agent/Kestrel 真机验证版本一致） |
| 文档 | https://k2-fsa.github.io/sherpa/onnx/ |

### 目录说明

```
third/sherpa-onnx/
├── jniLibs/arm64-v8a/            # 官方预编译 .so（releases: sherpa-onnx-v1.13.2-android.tar.bz2）
│   ├── libonnxruntime.so         # onnxruntime 运行时（~25MB）
│   └── libsherpa-onnx-jni.so     # sherpa JNI（~4.6MB）
└── kotlin-api/src/main/kotlin/com/k2fsa/sherpa/onnx/
    ├── OfflineRecognizer.kt      # 离线 ASR（SenseVoice 等）
    ├── Tts.kt                    # 离线 TTS（VITS/Kokoro/ZipVoice）
    ├── Speaker.kt                # 说话人（声纹）数据类
    ├── SpeakerEmbeddingExtractorConfig.kt  # 声纹提取器配置（ECAPA-TDNN 等）
    ├── Vad.kt                    # Silero VAD
    └── ...（其余为上游原样封装）
```

**注意**：
- package 必须保持 `com.k2fsa.sherpa.onnx`（JNI 注册名写死，不得改名）；
- `.so` 仅保留 `arm64-v8a`（真机目标 ABI）；
- **`.so` 不入库**（29MB 二进制，由脚本获取，见下方"获取 .so"）；
- 227MB onnx 模型必须 `noCompress`（sherpa 靠 mmap/AAsset_getBuffer 读取）。

### 获取 .so

```bash
# 从 sherpa-onnx v1.13.2 releases 下载 android 预编译包
# https://github.com/k2-fsa/sherpa-onnx/releases/tag/v1.13.2
# 取 sherpa-onnx-v1.13.2-android.tar.bz2，解压后：
cp jniLibs/arm64-v8a/libonnxruntime.so     third/sherpa-onnx/jniLibs/arm64-v8a/
cp jniLibs/arm64-v8a/libsherpa-onnx-jni.so third/sherpa-onnx/jniLibs/arm64-v8a/
# P0 后续将提供 scripts/fetch-native-libs.sh 一键脚本
```

### 更新方法

```bash
# 1) 上游 releases 取新版本 android tar，替换 jniLibs/arm64-v8a/*.so
# 2) 上游仓库取对应 tag 的 sherpa-onnx/kotlin-api/*.kt，覆盖 kotlin-api/ 目录
# 3) 更新上方版本号；跑 body 冒烟（gradlew :core:assembleDebug）
```

---

## 2. pi（Agent 内核，MIT）— npm 引用

**用途**：Agent loop / 工具调用 / 事件流 / 多 Provider LLM。

- 上游：https://github.com/earendil-works/pi
- 引入方式：**npm 依赖**（`brain/package.json` → `@earendil-works/pi-agent-core` / `@earendil-works/pi-ai`），版本锁定 `package-lock.json`；
- 更新：`cd brain && npm update @earendil-works/pi-agent-core @earendil-works/pi-ai`。

> npm 是 JS 生态标准的引用机制（lockfile 锁版本、npm audit 审计），故不 vendor 源码；非 JS 的原生资产才 vendor 到本目录。

---

## 3. llama.cpp（端侧 LLM 兜底，MIT）— 二进制引用

**用途**：Termux 内跑 Qwen3.5-2B int4 离线文本决策（P1+ 接入）。

- 上游：https://github.com/ggml-org/llama.cpp
- 引入方式：Termux 内 `pkg install llama.cpp` 或下载官方预编译（llama.cpp releases 的 android arm64 产物）；
- 更新：Termux `pkg upgrade` / 重新下载 release。

---

## 4. 复用自 android-agent（Kestrel，本项目前代，自有代码）

**用途**：sherpa kotlin-api 的完整落地方案（`SherpaSenseVoiceAsr` 等）为本项目自有资产的移植参考，非第三方开源，不放入 third/。
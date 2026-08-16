# STT/TTS 集成探索存档（未实施）

> 状态：**探索完成，暂不实施**（2026-08）。以下为调查结论与推荐方案，供后续实施参考。

## 背景

chatcore（Campus AI 助手）无语音能力：前端 `ChatPage.tsx`（React + SSE 流式）与后端 `/api/chat/stream`（文本协议）；LLM 为 DeepSeek（无音频 API）。STT/TTS 需外部引入。

集成点（已确认）：
- 前端：`frontend_web/src/pages/ChatPage.tsx`——textarea 输入区 + `handleSend`（SSE）+ assistant 消息渲染（朗读按钮挂载点）
- 约束：docker-compose 单机、生产 VM 2vCPU 只拉镜像、项目风格为免费/自托管

## 方案对比

| 方案 | STT | TTS | 工作量 | 限制 |
|---|---|---|---|---|
| A. 纯前端 | Web Speech API | 浏览器 SpeechSynthesis | 最小 | STT 依赖 Chrome 云端；TTS 音质不可控 |
| B. 后端 TTS + 前端 STT（原推荐） | Web Speech API | 独立 speech-service | 中 | 见下方引擎选择 |
| C. 本地全栈 | faster-whisper 容器 | edge-tts/Kokoro | 大 | whisper 中文中等；资源增加 |
| D. 云 API | Whisper | MiniMax/Azure/OpenAI | 中 | 需 key/付费 |

## TTS 技术调查（2025-2026，已核实）

| 方案 | 类型 | 质量 | 资源 | 中文 | 许可证 |
|---|---|---|---|---|---|
| **Kokoro-82M / v1.0**（PyPI kokoro 0.9.4） | 自托管 | 高（接近商用） | 82M 参数，CPU 实时；需 torch + espeak-ng + `misaki[zh]` | ✅ | Apache 2.0 |
| **edge-tts 7.2.8** | 在线 | 中上（微软神经语音） | 零部署（aiohttp） | ✅ | 免费无 key |
| Orpheus-TTS（canopyai，6.3k⭐） | 自托管 | 极高（情感/笑声） | 3B 模型需 GPU | 英文 | Apache 2.0 |
| MiniMax Speech / CosyVoice3 / Zonos | 云 API / 开源 | 极高（中文 SOTA） | 需 key 或 1-2GB 模型 | ✅ | 各异 |
| Piper / MeloTTS | 自托管 | 中 | 轻量 | ✅ | MIT |

**结论**：聊天朗读场景需低延迟 + CPU 可用 → edge-tts（零部署）或 Kokoro-82M（Apache 2.0、无外网依赖、质量更高）为可行自托管选项；1B+ 模型（Orpheus/CosyVoice）需 GPU，本项目排除。

## 推荐落地路径（若后续实施）

```
services/speech_service/（FastAPI）
  ├── TTSProvider 接口（可插拔）
  │   ├── EdgeTTSProvider（默认：SPEECH_TTS_ENGINE=edge-tts，零部署）
  │   └── KokoroProvider（可选：SPEECH_TTS_ENGINE=kokoro，torch CPU + espeak-ng，
  │        模型卷缓存，复用 lost-found-embedding 的部署模式）
  └── POST /v1/tts {text, engine?, voice?, rate?} → audio/mpeg
      （X-Service-Key 鉴权 + 文本长度限制，防滥用）

前端 ChatPage：
  ├── 麦克风按钮：SpeechRecognition → 填入输入框（STT，浏览器免费）
  └── assistant 消息朗读按钮：POST /v1/tts → Audio 播放
```

编排层无需改动（TTS 由前端直连 speech-service；STT 纯前端）。

## 决策记录

- 2026-08：探索完成，用户决定**先跳过 TTS**（STT/TTS 整体搁置），未实施任何代码。

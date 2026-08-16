// ──────────────────────────────────────────────
//  useSpeechRecognition — 浏览器 Web Speech API 语音输入（STT）
//  纯前端、零后端：识别结果通过 onFinal 回调返回
//  仅 Chrome / Edge 支持（webkit 前缀）；不支持时 supported=false
//  附带实时输入音量（getUserMedia + AnalyserNode RMS）与中间结果 interim
//
//  会话代际（generation）：start/stop/cancel/卸载时递增；旧会话的迟到回调
//  （onend / getUserMedia resolve）据此自弃，避免竞态串扰与麦克风泄漏。
// ──────────────────────────────────────────────

import { useCallback, useEffect, useRef, useState } from 'react';

// 最小化类型（lib.dom 的 SpeechRecognition 各浏览器实现不一致，用鸭子类型）
interface SpeechRecognitionResultLike {
  isFinal: boolean;
  0: { transcript: string };
}

interface SpeechRecognitionEventLike {
  resultIndex: number;
  results: ArrayLike<SpeechRecognitionResultLike>;
}

interface SpeechRecognitionErrorLike {
  error?: string;
}

interface SpeechRecognitionLike {
  lang: string;
  continuous: boolean;
  interimResults: boolean;
  maxAlternatives: number;
  onresult: ((event: SpeechRecognitionEventLike) => void) | null;
  onend: (() => void) | null;
  onerror: ((event: SpeechRecognitionErrorLike) => void) | null;
  start: () => void;
  stop: () => void;
  abort: () => void;
}

type RecognitionCtor = new () => SpeechRecognitionLike;

function getRecognitionCtor(): RecognitionCtor | null {
  if (typeof window === 'undefined') return null;
  const w = window as unknown as {
    SpeechRecognition?: RecognitionCtor;
    webkitSpeechRecognition?: RecognitionCtor;
  };
  return w.SpeechRecognition ?? w.webkitSpeechRecognition ?? null;
}

export interface SpeechStartOptions {
  /** 识别到完整语句时回调（final transcript，已 trim） */
  onFinal: (text: string) => void;
  /** 识别中间结果回调（可选，实时预览；也可直接读返回的 interim 状态） */
  onInterim?: (text: string) => void;
}

interface VolumeMeter {
  ctx: AudioContext;
  stream: MediaStream;
  raf: number;
}

function getAudioCtxCtor(): typeof AudioContext | null {
  if (typeof window === 'undefined') return null;
  const w = window as unknown as {
    AudioContext?: typeof AudioContext;
    webkitAudioContext?: typeof AudioContext;
  };
  return w.AudioContext ?? w.webkitAudioContext ?? null;
}

export function useSpeechRecognition(lang: string) {
  const [supported] = useState<boolean>(() => getRecognitionCtor() !== null);
  const [listening, setListening] = useState(false);
  const [interim, setInterim] = useState('');
  const [volume, setVolume] = useState(0);
  const [error, setError] = useState<string | null>(null);
  const recognitionRef = useRef<SpeechRecognitionLike | null>(null);
  const meterRef = useRef<VolumeMeter | null>(null);
  const finalRef = useRef('');
  // 会话代际：每次启动/停止/卸载递增；旧会话迟到回调据此自弃
  const generationRef = useRef(0);

  // ── 实时音量（getUserMedia + AnalyserNode RMS；权限/环境不支持时静默降级）──

  const stopVolumeMeter = useCallback(() => {
    const meter = meterRef.current;
    if (!meter) return;
    cancelAnimationFrame(meter.raf);
    meter.stream.getTracks().forEach((track) => track.stop());
    meter.ctx.close().catch(() => {});
    meterRef.current = null;
    setVolume(0);
  }, []);

  const startVolumeMeter = useCallback(
    (generation: number) => {
      const AudioCtx = getAudioCtxCtor();
      if (!AudioCtx || !navigator.mediaDevices?.getUserMedia) return;
      navigator.mediaDevices
        .getUserMedia({ audio: true })
        .then((stream) => {
          // 会话已更换/结束（cancel/卸载/再次 start）：丢弃迟到的流
          if (generationRef.current !== generation) {
            stream.getTracks().forEach((track) => track.stop());
            return;
          }
          const ctx = new AudioCtx();
          // 自动播放策略：promise 回调中创建的 AudioContext 可能 suspended
          if (ctx.state === 'suspended') {
            ctx.resume().catch(() => {});
          }
          const source = ctx.createMediaStreamSource(stream);
          const analyser = ctx.createAnalyser();
          analyser.fftSize = 512;
          source.connect(analyser);
          const data = new Uint8Array(analyser.fftSize);
          const loop = () => {
            analyser.getByteTimeDomainData(data);
            let sum = 0;
            for (let i = 0; i < data.length; i++) {
              const v = (data[i] - 128) / 128;
              sum += v * v;
            }
            setVolume(Math.min(1, Math.sqrt(sum / data.length) * 3));
            if (meterRef.current) {
              meterRef.current.raf = requestAnimationFrame(loop);
            }
          };
          meterRef.current = { ctx, stream, raf: 0 };
          loop();
        })
        .catch(() => {
          // 权限被拒/无麦克风：音量不可用，不影响识别本身
        });
    },
    [],
  );

  const stop = useCallback(() => {
    // stop() 触发 onend（会提交 final 文本）；abort() 不触发 onend
    recognitionRef.current?.stop();
  }, []);

  const cancel = useCallback(() => {
    generationRef.current += 1;
    finalRef.current = '';
    recognitionRef.current?.abort();
    stopVolumeMeter();
    setListening(false);
    setInterim('');
  }, [stopVolumeMeter]);

  const start = useCallback(
    (opts: SpeechStartOptions) => {
      const Ctor = getRecognitionCtor();
      if (!Ctor) return;
      recognitionRef.current?.abort();
      // 防重入：先清旧音量采集（若有迟到的 getUserMedia resolve，由 generation 校验丢弃）
      stopVolumeMeter();
      const generation = generationRef.current + 1;
      generationRef.current = generation;

      const recognition = new Ctor();
      recognition.lang = lang;
      recognition.continuous = false;
      recognition.interimResults = true;
      recognition.maxAlternatives = 1;
      finalRef.current = '';

      recognition.onresult = (event) => {
        let interimText = '';
        for (let i = event.resultIndex; i < event.results.length; i++) {
          const result = event.results[i];
          if (result.isFinal) finalRef.current += result[0].transcript;
          else interimText += result[0].transcript;
        }
        if (interimText) {
          setInterim(interimText);
          opts.onInterim?.(interimText);
        }
      };
      recognition.onend = () => {
        // 旧会话迟到 onend（abort 后）不清理新会话状态
        if (generationRef.current !== generation) return;
        stopVolumeMeter();
        setListening(false);
        setInterim('');
        setError(null);
        const text = finalRef.current.trim();
        if (text) opts.onFinal(text);
      };
      recognition.onerror = (event) => {
        setError(event.error ?? 'speech-error');
      };

      recognitionRef.current = recognition;
      setError(null);
      setInterim('');
      startVolumeMeter(generation);
      recognition.start();
      setListening(true);
    },
    [lang, startVolumeMeter, stopVolumeMeter],
  );

  // 卸载时使会话失效并释放麦克风/音频上下文
  useEffect(
    () => () => {
      generationRef.current += 1;
      recognitionRef.current?.abort();
      stopVolumeMeter();
    },
    [stopVolumeMeter],
  );

  return { supported, listening, interim, volume, error, start, stop, cancel };
}

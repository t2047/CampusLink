// ──────────────────────────────────────────────
//  useSpeechRecognition — 浏览器 Web Speech API 语音输入（STT）
//  纯前端、零后端：识别结果通过 onFinal 回调返回
//  仅 Chrome / Edge 支持（webkit 前缀）；不支持时 supported=false
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
  /** 识别中间结果回调（可选，实时预览） */
  onInterim?: (text: string) => void;
}

export function useSpeechRecognition(lang: string) {
  const [supported] = useState<boolean>(() => getRecognitionCtor() !== null);
  const [listening, setListening] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const recognitionRef = useRef<SpeechRecognitionLike | null>(null);
  const finalRef = useRef('');

  const stop = useCallback(() => {
    // stop() 触发 onend（会提交 final 文本）；abort() 不触发 onend
    recognitionRef.current?.stop();
  }, []);

  const cancel = useCallback(() => {
    finalRef.current = '';
    recognitionRef.current?.abort();
    setListening(false);
  }, []);

  const start = useCallback(
    (opts: SpeechStartOptions) => {
      const Ctor = getRecognitionCtor();
      if (!Ctor) return;
      recognitionRef.current?.abort();

      const recognition = new Ctor();
      recognition.lang = lang;
      recognition.continuous = false;
      recognition.interimResults = true;
      recognition.maxAlternatives = 1;
      finalRef.current = '';

      recognition.onresult = (event) => {
        let interim = '';
        for (let i = event.resultIndex; i < event.results.length; i++) {
          const result = event.results[i];
          if (result.isFinal) finalRef.current += result[0].transcript;
          else interim += result[0].transcript;
        }
        if (interim) opts.onInterim?.(interim);
      };
      recognition.onend = () => {
        setListening(false);
        const text = finalRef.current.trim();
        if (text) opts.onFinal(text);
      };
      recognition.onerror = (event) => {
        setError(event.error ?? 'speech-error');
      };

      recognitionRef.current = recognition;
      setError(null);
      recognition.start();
      setListening(true);
    },
    [lang],
  );

  // 卸载时中止识别，避免泄漏
  useEffect(() => () => recognitionRef.current?.abort(), []);

  return { supported, listening, error, start, stop, cancel };
}

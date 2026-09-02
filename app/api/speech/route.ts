import { NextResponse } from "next/server";

type SpeechRequest = { audio?: string };

const MAX_AUDIO_DATA_URL_LENGTH = 1_000_000;

function isSafeWavDataUrl(value: string) {
  return /^data:audio\/wav;base64,[A-Za-z0-9+/=]+$/i.test(value) && value.length <= MAX_AUDIO_DATA_URL_LENGTH;
}

function isStartPhrase(value: string) {
  const normalized = value.replace(/[\s，。！？、,.!?]/g, "");
  return normalized === "开始" || normalized.includes("开始制作") || normalized.includes("开始做");
}

/** Transcribes one short glasses recording without persisting the original audio. */
export async function POST(request: Request) {
  const apiKey = process.env.VISION_API_KEY;
  const baseUrl = process.env.VISION_API_BASE_URL;
  const model = process.env.SPEECH_MODEL || "qwen3-asr-flash";
  if (!apiKey || !baseUrl) {
    return NextResponse.json({ code: "SPEECH_NOT_CONFIGURED", message: "语音识别服务尚未配置。" }, { status: 503 });
  }

  let body: SpeechRequest;
  try {
    body = await request.json() as SpeechRequest;
  } catch {
    return NextResponse.json({ code: "INVALID_REQUEST", message: "语音请求格式无效。" }, { status: 400 });
  }
  if (!body.audio || !isSafeWavDataUrl(body.audio)) {
    return NextResponse.json({ code: "INVALID_AUDIO", message: "请提供有效的短 WAV 音频。" }, { status: 400 });
  }

  try {
    const response = await fetch(`${baseUrl.replace(/\/$/, "")}/chat/completions`, {
      method: "POST",
      headers: { "Content-Type": "application/json", Authorization: `Bearer ${apiKey}` },
      body: JSON.stringify({
        model,
        stream: false,
        asr_options: { language: "zh", enable_itn: false },
        messages: [{
          role: "user",
          content: [{ type: "input_audio", input_audio: { data: body.audio } }],
        }],
      }),
    });
    if (!response.ok) {
      return NextResponse.json({ code: "SPEECH_PROVIDER_ERROR", message: "语音识别服务返回错误。" }, { status: 502 });
    }
    const result = await response.json() as { choices?: Array<{ message?: { content?: unknown } }> };
    const content = result.choices?.[0]?.message?.content;
    const transcript = typeof content === "string" ? content.replace(/[\u0000-\u001f]/g, " ").trim().slice(0, 120) : "";
    return NextResponse.json(
      { transcript, isStart: isStartPhrase(transcript), dataPolicy: "短音频仅用于本次识别；服务端不持久化原始音频。" },
      { headers: { "Cache-Control": "no-store" } },
    );
  } catch {
    return NextResponse.json({ code: "SPEECH_PROVIDER_UNAVAILABLE", message: "语音识别服务暂不可用。" }, { status: 502 });
  }
}

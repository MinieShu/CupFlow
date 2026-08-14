"use client";

import { useEffect, useRef, useState } from "react";

type Action = "cup" | "tea" | "pearls" | "wrong" | "measure" | "seal" | "label" | "wrongLabel" | "overfill";
type OrderStatus = "待制作" | "制作中" | "异常待复核" | "已完成";
type AnomalyKind = "wrongIngredient" | "extraPearls" | "overfill" | "sequence" | "wrongLabel" | null;

const steps: { id: Exclude<Action, "wrong" | "wrongLabel" | "overfill">; title: string; prompt: string }[] = [
  { id: "cup", title: "取杯", prompt: "请取用订单 A102 的杯子。" },
  { id: "pearls", title: "加入珍珠", prompt: "请加入一平勺珍珠，注意本单不加椰果。" },
  { id: "tea", title: "加入茶底", prompt: "现在加入乌龙茶底至量杯标准线。" },
  { id: "measure", title: "核对液位", prompt: "请确认液体未超过量杯红色标准线。" },
  { id: "seal", title: "封杯", prompt: "完成封杯后，请核对订单杯贴。" },
  { id: "label", title: "杯贴核验", prompt: "请贴上 A102 的正确杯贴。" },
];

const markers: { id: Action; name: string; color: string; rgb?: [number, number, number] }[] = [
  { id: "cup", name: "白色杯身标记", color: "#f5f4ec", rgb: [245, 244, 236] },
  { id: "tea", name: "乌龙茶底", color: "#d37f5e", rgb: [211, 127, 94] },
  { id: "pearls", name: "珍珠", color: "#e58aa5", rgb: [229, 138, 165] },
  { id: "wrong", name: "椰果（错料）", color: "#b5df91", rgb: [181, 223, 145] },
  { id: "seal", name: "封杯", color: "#f43f5e", rgb: [244, 63, 94] },
  { id: "label", name: "正确杯贴（浅绿色）", color: "#d9ef5b", rgb: [217, 239, 91] },
  { id: "wrongLabel", name: "错误杯贴（蓝色）", color: "#2d8baa", rgb: [45, 139, 170] },
  { id: "overfill", name: "超过标准线", color: "#ef4444" },
  { id: "measure", name: "液位合格", color: "#14b8a6" },
];

function speak(text: string) {
  if (typeof window === "undefined" || !("speechSynthesis" in window)) return;
  window.speechSynthesis.cancel();
  const utterance = new SpeechSynthesisUtterance(text);
  utterance.lang = "zh-CN";
  utterance.rate = 1.06;
  window.speechSynthesis.speak(utterance);
}

export default function Home() {
  const videoRef = useRef<HTMLVideoElement>(null);
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const intervalRef = useRef<number | undefined>(undefined);
  const mediaRecorder = useRef<MediaRecorder | null>(null);
  const rollingChunks = useRef<{ blob: Blob; at: number }[]>([]);
  const captureBefore = useRef<Blob[]>([]);
  const lastDetection = useRef<{ id: Action; at: number } | null>(null);

  const [cameraOn, setCameraOn] = useState(false);
  const [status, setStatus] = useState<OrderStatus>("待制作");
  const [stepIndex, setStepIndex] = useState(0);
  const [message, setMessage] = useState("等待订单开始");
  const [events, setEvents] = useState<string[]>([]);
  const [startedAt, setStartedAt] = useState<number | null>(null);
  const [elapsed, setElapsed] = useState(0);
  const [autoCorrections, setAutoCorrections] = useState(0);
  const [anomalies, setAnomalies] = useState(0);
  const [pendingAnomaly, setPendingAnomaly] = useState<AnomalyKind>(null);
  const [clipReady, setClipReady] = useState(false);
  const [clipUrl, setClipUrl] = useState<string | null>(null);

  useEffect(() => {
    if (!startedAt || status === "已完成") return;
    const timer = window.setInterval(() => setElapsed(Math.floor((Date.now() - startedAt) / 1000)), 1000);
    return () => window.clearInterval(timer);
  }, [startedAt, status]);

  useEffect(() => () => {
    if (intervalRef.current) window.clearInterval(intervalRef.current);
    mediaRecorder.current?.stop();
    if (clipUrl) URL.revokeObjectURL(clipUrl);
  }, [clipUrl]);

  const addEvent = (text: string) => setEvents((current) => [text, ...current].slice(0, 7));

  const prepareEventClip = () => {
    captureBefore.current = rollingChunks.current.map((item) => item.blob);
    setClipReady(false);
    window.setTimeout(() => {
      const after = rollingChunks.current.map((item) => item.blob);
      const clip = new Blob([...captureBefore.current, ...after], { type: "video/webm" });
      if (!clip.size) return;
      if (clipUrl) URL.revokeObjectURL(clipUrl);
      setClipUrl(URL.createObjectURL(clip));
      setClipReady(true);
    }, 10_000);
  };

  const raiseAnomaly = (kind: Exclude<AnomalyKind, null>, alert: string, event: string) => {
    setStatus("异常待复核");
    setPendingAnomaly(kind);
    setAnomalies((value) => value + 1);
    setMessage(alert);
    addEvent(`异常：${event}`);
    speak(alert);
    prepareEventClip();
  };

  const startOrder = () => {
    setStatus("制作中");
    setStepIndex(0);
    setElapsed(0);
    setAnomalies(0);
    setAutoCorrections(0);
    setPendingAnomaly(null);
    setEvents(["订单 A102 已下发：少糖、去冰、加珍珠"]);
    setStartedAt(Date.now());
    setMessage(steps[0].prompt);
    speak(`订单 A102 开始制作。 ${steps[0].prompt}`);
  };

  const detectAction = (id: Action, source = "视觉") => {
    if (status !== "制作中" && status !== "异常待复核") return;
    const now = Date.now();
    if (lastDetection.current?.id === id && now - lastDetection.current.at < 1300) return;
    lastDetection.current = { id, at: now };
    if (status === "异常待复核") {
      const correctionMatches =
        (pendingAnomaly === "wrongIngredient" && id === "pearls") ||
        (pendingAnomaly === "extraPearls" && id === "pearls") ||
        (pendingAnomaly === "overfill" && id === "measure") ||
        (pendingAnomaly === "sequence" && id === steps[stepIndex]?.id) ||
        (pendingAnomaly === "wrongLabel" && id === "label");
      if (!correctionMatches) return;

      const correction = {
        wrongIngredient: "已识别正确原料",
        extraPearls: "已识别撤回多余珍珠",
        overfill: "已识别液位回到标准线",
        sequence: "已识别回到正确制作步骤",
        wrongLabel: "已识别正确杯贴",
      }[pendingAnomaly ?? "sequence"];
      const correctedKind = pendingAnomaly;
      setAutoCorrections((value) => value + 1);
      setStatus("制作中");
      setPendingAnomaly(null);
      addEvent(`自动纠正：${correction}`);
      speak(`已自动纠正。 ${correction}。`);

      if (correctedKind === "extraPearls") {
        setMessage(`已自动纠正：${correction}。 ${steps[stepIndex]?.prompt ?? "请继续制作。"}`);
        return;
      }
    }

    if (id === "wrong") {
      raiseAnomaly("wrongIngredient", "原料不匹配：当前需要珍珠，检测到椰果。请取回椰果并加入珍珠。", "检测到不匹配加料“椰果”");
      return;
    }
    if (id === "overfill") {
      raiseAnomaly("overfill", "液体超过标准线，请检查用量并回到标准刻度。", "量杯液位超过标准线");
      return;
    }
    if (id === "wrongLabel" && steps[stepIndex]?.id === "label") {
      raiseAnomaly("wrongLabel", "杯贴与订单不一致：请核对少糖、去冰和珍珠选项。", "杯贴定制项不匹配");
      return;
    }
    if (id === "pearls" && stepIndex > 1) {
      raiseAnomaly("extraPearls", "疑似重复加料：本单珍珠已完成，请将多余珍珠倒回原料盒。", "检测到第二次加入珍珠");
      return;
    }

    const expected = steps[stepIndex];
    if (!expected) return;
    if (id !== expected.id) {
      raiseAnomaly("sequence", `步骤顺序异常：当前应“${expected.title}”。`, `当前识别为“${markers.find((item) => item.id === id)?.name ?? id}”`);
      return;
    }

    const nextIndex = stepIndex + 1;
    addEvent(`${source}确认：${expected.title}`);
    if (nextIndex === steps.length) {
      setStepIndex(nextIndex);
      setStatus("已完成");
      setMessage("订单 A102 已完成：少糖、去冰、加珍珠已核验。");
      addEvent("订单完成：配方与杯贴已核验");
      speak("订单 A102 已完成。少糖、去冰、加珍珠已核验。");
      return;
    }
    setStepIndex(nextIndex);
    setMessage(steps[nextIndex].prompt);
    speak(`已确认${expected.title}。 ${steps[nextIndex].prompt}`);
  };

  const scanFrame = () => {
    const video = videoRef.current;
    const canvas = canvasRef.current;
    if (!video || !canvas || video.readyState < 2) return;
    canvas.width = 160;
    canvas.height = 90;
    const context = canvas.getContext("2d", { willReadFrequently: true });
    if (!context) return;
    context.drawImage(
      video,
      video.videoWidth * 0.3,
      video.videoHeight * 0.18,
      video.videoWidth * 0.4,
      video.videoHeight * 0.64,
      0,
      0,
      canvas.width,
      canvas.height,
    );
    const pixels = context.getImageData(0, 0, canvas.width, canvas.height).data;
    const samples = pixels.length / 4;
    const activeMarkerIds: Action[][] = [
      ["cup"],
      ["pearls", "wrong"],
      ["tea", "pearls"],
      [],
      ["seal"],
      ["label", "wrongLabel"],
    ];
    const activeMarkerIdsDuringCorrection: Partial<Record<Exclude<AnomalyKind, null>, Action[]>> = {
      wrongIngredient: ["pearls"],
      extraPearls: ["pearls"],
      sequence: [steps[stepIndex]?.id].filter(Boolean) as Action[],
      wrongLabel: ["label"],
    };
    const allowedIds = status === "异常待复核" && pendingAnomaly
      ? activeMarkerIdsDuringCorrection[pendingAnomaly] ?? activeMarkerIds[stepIndex]
      : activeMarkerIds[stepIndex];

    for (const marker of markers.filter((item) => item.rgb && allowedIds.includes(item.id))) {
      let matches = 0;
      for (let index = 0; index < pixels.length; index += 16) {
        const rgb = marker.rgb as [number, number, number];
        const distance = Math.abs(pixels[index] - rgb[0]) + Math.abs(pixels[index + 1] - rgb[1]) + Math.abs(pixels[index + 2] - rgb[2]);
        if (distance < 110) matches += 1;
      }
      if (matches / (samples / 4) > 0.025) {
        detectAction(marker.id);
        break;
      }
    }
  };

  const enableCamera = async () => {
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ video: { facingMode: "environment" }, audio: true });
      if (!videoRef.current) return;
      videoRef.current.srcObject = stream;
      await videoRef.current.play();
      setCameraOn(true);
      intervalRef.current = window.setInterval(scanFrame, 700);
      const recorder = new MediaRecorder(stream);
      recorder.ondataavailable = (event) => {
        if (!event.data.size) return;
        const now = Date.now();
        rollingChunks.current = [...rollingChunks.current, { blob: event.data, at: now }].filter((item) => now - item.at < 10_000);
      };
      recorder.start(1000);
      mediaRecorder.current = recorder;
      setMessage("摄像头已连接。请将原料标签或操作动作置于画面中央。");
    } catch {
      setMessage("无法访问摄像头。可使用下方演示控制完成录制。");
    }
  };

  const formattedTime = `${String(Math.floor(elapsed / 60)).padStart(2, "0")}:${String(elapsed % 60).padStart(2, "0")}`;
  const progress = Math.min(100, (stepIndex / steps.length) * 100);
  const expectedStep = steps[stepIndex];

  return (
    <main className="shell">
      <canvas ref={canvasRef} className="hidden" />
      <header className="recording-header">
        <div className="recording-brand">
          <span className="recording-mark">杯</span>
          <div><h1>杯序 <em>CupFlow</em></h1><p>奶茶店第一视角操作智能体</p></div>
        </div>
        <p className="recording-slogan">让新人每一步都做对。</p>
      </header>
      <section className="grid">
        <article className="card order-card">
          <div className="card-head"><span className="overline">SIMULATED KDS</span><span className={`status status-${status}`}>{status}</span></div>
          <div className="order-number">A102 <span>· 预计 01:40</span></div>
          <h2>云朵乌龙</h2>
          <div className="tags"><span>少糖</span><span>去冰</span><span>加珍珠</span></div>
          <div className="order-actions">
            <button className="primary" onClick={startOrder}>{status === "待制作" || status === "已完成" ? "开始订单" : "重新开始"}</button>
            <button className="secondary" onClick={enableCamera} disabled={cameraOn}>{cameraOn ? "摄像头已连接" : "连接摄像头"}</button>
          </div>
          <div className="progress-track"><i style={{ width: `${progress}%` }} /></div>
          <ol className="steps">
            {steps.map((step, index) => <li key={step.id} className={index < stepIndex ? "done" : index === stepIndex && status !== "已完成" ? "active" : ""}><b>{index < stepIndex ? "✓" : index + 1}</b><span>{step.title}</span></li>)}
          </ol>
        </article>

        <article className="card vision-card">
          <div className="card-head"><span className="overline">FIRST-PERSON CAMERA</span><span className="camera-state">{cameraOn ? "● LIVE" : "○ OFFLINE"}</span></div>
          <div className="camera-frame">
            <video ref={videoRef} muted playsInline />
            {!cameraOn && <div className="camera-empty"><span>⌁</span><p>连接手机摄像头<br />模拟眼镜第一视角</p></div>}
            <div className="camera-overlay"><span>A102 · 少糖 / 去冰 / 珍珠</span><i /></div>
          </div>
          <p className="camera-hint">识别区为镜头中央。将当前原料或纠正动作移入中央；其余原料留在后方，避免误判。</p>
          <details className="demo-controls">
            <summary>打开演示控制</summary>
            <div className="markers">
              {markers.map((marker) => <button key={marker.id} onClick={() => detectAction(marker.id, "演示")} disabled={status === "待制作" || status === "已完成"}><i style={{ background: marker.color }} />{marker.name}</button>)}
            </div>
          </details>
        </article>

        <article className="card agent-card">
          <div className="card-head"><span className="overline">AGENT DECISION</span><button className="quiet" onClick={() => speak(message)}>↗ 重播</button></div>
          <div className="agent-orb"><div /><span>AI</span></div>
          <p className="agent-label">杯序正在协助</p>
          <h3>{message}</h3>
          <div className="current-check">当前核验：<b>{expectedStep?.title ?? "订单已完成"}</b></div>
          <div className="agent-actions">
            <button onClick={() => speak(expectedStep?.prompt ?? "订单已完成")}>重复当前提示</button>
          </div>
          <div className="privacy-note">🔒 默认不连续保存视频。仅在异常时保留事件前后 10 秒片段。</div>
        </article>

        <article className="card metrics-card">
          <div className="card-head"><span className="overline">MANAGER VIEW</span><span>今日 · 模拟数据</span></div>
          <div className="metrics">
            <div><strong>{formattedTime}</strong><span>本单耗时</span></div>
            <div><strong>{anomalies}</strong><span>识别异常</span></div>
            <div><strong>{autoCorrections}</strong><span>自动纠正</span></div>
          </div>
          <div className="event-clip"><div><b>{clipReady ? "异常片段已就绪" : anomalies ? "正在补全异常后片段" : "暂无异常事件片段"}</b><span>{clipReady ? "已保留取料前后完整上下文" : "默认仅在内存中滚动缓存"}</span></div>{clipReady && clipUrl && <a href={clipUrl} download="A102-event.webm">下载片段</a>}</div>
          <div className="timeline"><b className="timeline-title">异常记录</b>{events.length ? events.map((event, index) => <p key={`${event}-${index}`}><i />{event}</p>) : <p className="muted">开始订单后将显示结构化事件。</p>}</div>
        </article>
      </section>
    </main>
  );
}

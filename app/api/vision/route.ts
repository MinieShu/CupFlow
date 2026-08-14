import { NextResponse } from "next/server";

const allowedEvents = new Set([
  "cup",
  "tea",
  "pearls",
  "wrong",
  "measure",
  "seal",
  "label",
  "wrongLabel",
  "overfill",
  "unknown",
]);

type VisionRequest = {
  image?: string;
  expectedStep?: { id?: string; title?: string };
  order?: { id?: string; drink?: string; options?: string[] };
  mode?: "operation" | "label";
};

function jsonFromModel(content: unknown) {
  const raw = typeof content === "string" ? content : "";
  const cleaned = raw.replace(/^```(?:json)?\s*/i, "").replace(/\s*```$/i, "").trim();
  const parsed = JSON.parse(cleaned) as Record<string, unknown>;
  const event = typeof parsed.event === "string" && allowedEvents.has(parsed.event)
    ? parsed.event
    : "unknown";
  const confidence = typeof parsed.confidence === "number"
    ? Math.max(0, Math.min(1, parsed.confidence))
    : 0;
  return {
    event,
    confidence,
    reason: typeof parsed.reason === "string" ? parsed.reason.slice(0, 120) : "",
    ticket: typeof parsed.ticket === "object" && parsed.ticket !== null ? parsed.ticket : null,
  };
}

export async function POST(request: Request) {
  const apiKey = process.env.VISION_API_KEY;
  const baseUrl = process.env.VISION_API_BASE_URL;
  const model = process.env.VISION_MODEL;

  if (!apiKey || !baseUrl || !model) {
    return NextResponse.json(
      { code: "VISION_NOT_CONFIGURED", message: "视觉模型尚未配置，已保留本地演示兜底。" },
      { status: 503 },
    );
  }

  const body = await request.json() as VisionRequest;
  if (!body.image?.startsWith("data:image/")) {
    return NextResponse.json({ code: "INVALID_IMAGE", message: "请提供有效的摄像头图像。" }, { status: 400 });
  }

  const expected = body.expectedStep?.title ?? "未指定";
  const orderText = `${body.order?.id ?? "A102"}｜${body.order?.drink ?? "云朵乌龙奶茶"}｜${body.order?.options?.join("、") ?? "少糖、去冰、加珍珠"}`;
  const prompt = body.mode === "label"
    ? `识别画面中的奶茶杯贴或订单纸条。当前订单为：${orderText}。判断杯贴是否匹配当前订单，并提取订单号、饮品、糖度、冰量和小料。`
    : `判断奶茶制作台画面中刚发生的关键操作。当前订单为：${orderText}；当前应执行步骤是：${expected}。只根据画面中可见事实判断，不确定时返回 unknown。`;

  const system = `你是 CupFlow 奶茶制作流程 Agent 的视觉感知工具。只返回一行 JSON，不要 Markdown，不要解释文字。
JSON schema: {"event":"cup|tea|pearls|wrong|measure|seal|label|wrongLabel|overfill|unknown","confidence":0至1,"reason":"不超过30个中文字符","ticket":{"orderId":"string|null","drink":"string|null","sugar":"string|null","ice":"string|null","topping":"string|null","matchesCurrentOrder":true|false|null}}
事件含义：cup=取到制作杯；tea=加入茶底；pearls=加入珍珠；wrong=加入椰果或其他不匹配小料；measure=液位合格；seal=完成封杯；label=正确杯贴；wrongLabel=与当前订单不匹配的杯贴；overfill=液位超过标准线；unknown=无法可靠判断。`;

  try {
    const response = await fetch(`${baseUrl.replace(/\/$/, "")}/chat/completions`, {
      method: "POST",
      headers: { "Content-Type": "application/json", Authorization: `Bearer ${apiKey}` },
      body: JSON.stringify({
        model,
        temperature: 0,
        response_format: { type: "json_object" },
        messages: [
          { role: "system", content: system },
          {
            role: "user",
            content: [
              { type: "text", text: prompt },
              { type: "image_url", image_url: { url: body.image } },
            ],
          },
        ],
      }),
    });

    if (!response.ok) {
      const detail = await response.text();
      return NextResponse.json({ code: "VISION_PROVIDER_ERROR", message: detail.slice(0, 180) }, { status: 502 });
    }

    const result = await response.json() as { choices?: Array<{ message?: { content?: unknown } }> };
    const content = result.choices?.[0]?.message?.content;
    return NextResponse.json(jsonFromModel(content));
  } catch {
    return NextResponse.json({ code: "VISION_PROVIDER_UNAVAILABLE", message: "视觉模型服务暂不可用。" }, { status: 502 });
  }
}

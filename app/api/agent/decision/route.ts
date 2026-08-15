import { NextResponse } from "next/server";
import { runCupFlowWorkflow, type WorkflowInput } from "@/lib/cupflow-agent";

const allowedEvents = new Set(["cup", "tea", "pearls", "wrong", "measure", "seal", "label", "wrongLabel", "overfill", "unknown"]);
const allowedSteps = new Set(["cup", "tea", "pearls", "measure", "seal", "label"]);

function cleanText(value: unknown, limit: number) {
  return typeof value === "string" ? value.replace(/[\u0000-\u001f]/g, " ").trim().slice(0, limit) : "";
}

function parseInput(value: unknown): WorkflowInput | null {
  if (!value || typeof value !== "object") return null;
  const input = value as Partial<WorkflowInput>;
  const event = input.observation?.event;
  const stepId = input.expectedStep?.id;
  if (!input.order || !input.observation || !allowedEvents.has(event ?? "") || !allowedSteps.has(stepId ?? "")) return null;
  return {
    sessionId: cleanText(input.sessionId, 80) || "anonymous-session",
    order: {
      id: cleanText(input.order.id, 32) || "A102",
      drink: cleanText(input.order.drink, 64) || "云朵乌龙奶茶",
      options: Array.isArray(input.order.options) ? input.order.options.map((item) => cleanText(item, 24)).filter(Boolean).slice(0, 8) : [],
    },
    expectedStep: { id: stepId as WorkflowInput["expectedStep"]["id"], title: cleanText(input.expectedStep?.title, 32) },
    stepIndex: Number.isInteger(input.stepIndex) ? Math.max(0, Math.min(20, input.stepIndex as number)) : 0,
    observation: {
      event: event as WorkflowInput["observation"]["event"],
      confidence: typeof input.observation.confidence === "number" ? Math.max(0, Math.min(1, input.observation.confidence)) : 0,
      reason: cleanText(input.observation.reason, 160),
      ticket: input.observation.ticket ?? null,
    },
    recentEvents: Array.isArray(input.recentEvents) ? input.recentEvents.map((item) => cleanText(item, 120)).filter(Boolean).slice(0, 8) : [],
  };
}

export async function POST(request: Request) {
  try {
    const input = parseInput(await request.json());
    if (!input) return NextResponse.json({ code: "INVALID_AGENT_INPUT", message: "Agent 输入不完整或不符合安全约束。" }, { status: 400 });
    const result = await runCupFlowWorkflow(input);
    return NextResponse.json(result, { headers: { "Cache-Control": "no-store" } });
  } catch {
    return NextResponse.json({ code: "AGENT_WORKFLOW_UNAVAILABLE", message: "工作流暂不可用，请保持当前步骤并人工确认。" }, { status: 503 });
  }
}

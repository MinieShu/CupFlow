import { NextResponse } from "next/server";
import { completeDispatchedOrder } from "@/lib/dispatch";

export async function POST(request: Request) {
  try {
    const body = await request.json() as { orderId?: unknown; workerId?: unknown };
    const orderId = typeof body.orderId === "string" ? body.orderId.trim().slice(0, 32) : "";
    const workerId = typeof body.workerId === "string" ? body.workerId.trim().slice(0, 32) : "";
    if (!orderId || !workerId) return NextResponse.json({ code: "INVALID_COMPLETION_INPUT", message: "请提供订单号与员工号。" }, { status: 400 });
    return NextResponse.json({ trace: completeDispatchedOrder(orderId, workerId) }, { headers: { "Cache-Control": "no-store" } });
  } catch (error) {
    const code = error instanceof Error ? error.message : "DISPATCH_UNAVAILABLE";
    return NextResponse.json({ code, message: "订单完成状态无法更新。" }, { status: code === "ASSIGNMENT_MISMATCH" ? 409 : 503 });
  }
}

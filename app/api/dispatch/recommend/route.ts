import { NextResponse } from "next/server";
import { recommendOrDispatch, type DispatchMode } from "@/lib/dispatch";

export async function POST(request: Request) {
  try {
    const body = await request.json() as { orderId?: unknown; mode?: unknown };
    const orderId = typeof body.orderId === "string" ? body.orderId.trim().slice(0, 32) : "";
    const mode: DispatchMode = body.mode === "auto" ? "auto" : body.mode === "semi_auto" ? "semi_auto" : "semi_auto";
    if (!orderId) return NextResponse.json({ code: "INVALID_DISPATCH_INPUT", message: "请提供订单号。" }, { status: 400 });
    return NextResponse.json({ trace: recommendOrDispatch(orderId, mode) }, { headers: { "Cache-Control": "no-store" } });
  } catch (error) {
    const code = error instanceof Error ? error.message : "DISPATCH_UNAVAILABLE";
    return NextResponse.json({ code, message: "派单暂不可用。" }, { status: code === "ORDER_NOT_FOUND" ? 404 : 503 });
  }
}

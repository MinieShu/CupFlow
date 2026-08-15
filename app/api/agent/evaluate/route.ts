import { NextResponse } from "next/server";
import { runPolicyEvaluation } from "@/lib/cupflow-agent";

export async function GET() {
  try {
    return NextResponse.json(await runPolicyEvaluation(), { headers: { "Cache-Control": "no-store" } });
  } catch {
    return NextResponse.json({ code: "EVALUATION_UNAVAILABLE", message: "评测套件暂不可用。" }, { status: 503 });
  }
}

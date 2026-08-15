import { NextResponse } from "next/server";
import { evaluateDispatchPolicy } from "@/lib/dispatch";

export async function GET() {
  return NextResponse.json(evaluateDispatchPolicy(), { headers: { "Cache-Control": "no-store" } });
}

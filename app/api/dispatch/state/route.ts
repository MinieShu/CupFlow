import { NextResponse } from "next/server";
import { getDispatchState } from "@/lib/dispatch";

export async function GET() {
  return NextResponse.json(getDispatchState(), { headers: { "Cache-Control": "no-store" } });
}

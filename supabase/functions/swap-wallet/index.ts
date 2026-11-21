import { serve } from "https://deno.land/std@0.168.0/http/server.ts";

const headers = {
  "Content-Type": "application/json",
  "Cache-Control": "no-store",
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers":
    "authorization, apikey, content-type, x-client-info",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};

serve((req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers });
  if (req.method !== "POST") {
    return new Response(JSON.stringify({ error: "Method not allowed" }), {
      status: 405,
      headers,
    });
  }
  return new Response(
    JSON.stringify({
      error:
        "This feature is temporarily disabled while its safeguards are completed.",
      disabled: true,
      no_money_moved: true,
    }),
    { status: 503, headers },
  );
});

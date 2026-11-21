import { serve } from "https://deno.land/std@0.168.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";
const h = {
  "Content-Type": "application/json",
  "Cache-Control": "no-store",
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers":
    "authorization, apikey, content-type, x-client-info",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};
const out = (d: unknown, s = 200) =>
  new Response(JSON.stringify(d), { status: s, headers: h });
serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: h });
  if (req.method !== "POST") return out({ error: "Method not allowed" }, 405);
  const authorization = req.headers.get("Authorization") ?? "";
  if (!authorization.startsWith("Bearer ")) {
    return out({ error: "Authentication required" }, 401);
  }
  try {
    const url = Deno.env.get("SUPABASE_URL")!,
      anon = Deno.env.get("SUPABASE_ANON_KEY")!;
    const client = createClient(url, anon, {
      global: { headers: { Authorization: authorization } },
      auth: { persistSession: false },
    });
    const { data, error } = await client.auth.getUser();
    if (error || !data.user) return out({ error: "Invalid session" }, 401);
    const environment = Deno.env.get("FLW_ENVIRONMENT"),
      enabled = Deno.env.get("FLW_LIVE_ENABLED") === "true";
    const secret = Deno.env.get("FLUTTERWAVE_SECRET_KEY") ?? "";
    if (
      environment !== "live" || !enabled || !secret.startsWith("FLWSECK-") ||
      secret.startsWith("FLWSECK_TEST-")
    ) {
      return out({
        error: "Flutterwave Live account lookup is not enabled on the server.",
      }, 503);
    }
    const body = await req.json();
    const account = String(body?.account_number ?? "").replace(/\s/g, "");
    const bank = String(body?.account_bank ?? "").trim();
    if (!/^\d{10}$/.test(account) || !/^\d{3,6}$/.test(bank)) {
      return out({
        error: "A 10-digit account number and valid bank code are required.",
      }, 400);
    }
    const response = await fetch(
      "https://api.flutterwave.com/v3/accounts/resolve",
      {
        method: "POST",
        headers: {
          Authorization: "Bearer " + secret,
          "Content-Type": "application/json",
          Accept: "application/json",
        },
        body: JSON.stringify({ account_number: account, account_bank: bank }),
        signal: AbortSignal.timeout(15000),
      },
    );
    const result = await response.json();
    if (
      !response.ok || result?.status !== "success" ||
      !result?.data?.account_name
    ) {
      return out({
        error:
          "Could not verify the bank account. Check the details and try again.",
      }, 400);
    }
    return out({
      account_name: String(result.data.account_name),
      account_number: String(result.data.account_number ?? account),
      bank: String(result.data.bank_name ?? ""),
    });
  } catch (_) {
    return out({ error: "Account lookup failed. Please try again." }, 500);
  }
});

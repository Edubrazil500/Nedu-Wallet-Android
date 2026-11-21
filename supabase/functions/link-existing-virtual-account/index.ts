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
  const auth = req.headers.get("Authorization") ?? "";
  if (!auth.startsWith("Bearer ")) {
    return out({ error: "Authentication required" }, 401);
  }
  try {
    const url = Deno.env.get("SUPABASE_URL")!,
      anon = Deno.env.get("SUPABASE_ANON_KEY")!,
      service = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
    const userClient = createClient(url, anon, {
      global: { headers: { Authorization: auth } },
      auth: { persistSession: false },
    });
    const { data, error } = await userClient.auth.getUser();
    if (error || !data.user) return out({ error: "Invalid session" }, 401);
    const body = await req.json();
    const account = String(body?.account_number ?? "").trim();
    if (!/^\d{10}$/.test(account)) {
      return out({ error: "Enter a valid 10-digit account number." }, 400);
    }
    const admin = createClient(url, service, {
      auth: { persistSession: false, autoRefreshToken: false },
    });
    const { data: profile, error: profileError } = await admin.from("profiles")
      .select("virtual_account_number,virtual_bank_name,virtual_order_ref").eq("id", data.user.id)
      .single();
    if (profileError || !profile) {
      return out({ error: "Wallet profile not found." }, 404);
    }
    if (profile.virtual_account_number === account && profile.virtual_order_ref) {
      return out({
        ok: true,
        account_number: account,
        bank_name: profile.virtual_bank_name ?? "",
        already_linked: true,
      });
    }
    if (profile.virtual_account_number === account && !profile.virtual_order_ref) {
      return out({
        error:
          "This legacy account needs Flutterwave reconciliation before it can be treated as verified. No KYC status was changed.",
      }, 409);
    }
    // A bank account number supplied by a client is not proof of ownership or a Flutterwave virtual account.
    // Do not bind it to a wallet or make it eligible for funding credits.
    return out({
      error:
        "For security, an existing account cannot be linked from its number alone. Use the Nedu Wallet-issued virtual account or contact support for manual ownership verification.",
    }, 409);
  } catch (_) {
    return out({ error: "Account-link request failed." }, 500);
  }
});

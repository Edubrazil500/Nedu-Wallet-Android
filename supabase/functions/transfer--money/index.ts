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
      anon = Deno.env.get("SUPABASE_ANON_KEY")!,
      service = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
    const client = createClient(url, anon, {
      global: { headers: { Authorization: authorization } },
      auth: { persistSession: false },
    });
    const { data: authData, error: authError } = await client.auth.getUser();
    if (authError || !authData.user) {
      return out({ error: "Invalid session" }, 401);
    }
    const body = await req.json();
    const currency = String(body?.from_currency ?? "NGN").toUpperCase();
    const amount = Number(body?.amount);
    const recipient = String(body?.receiver_name ?? "").trim();
    const bank = String(body?.bank_name ?? "").trim();
    const bankCode = String(body?.bank_code ?? "").trim();
    const account = String(body?.account_number ?? "").replace(/\s/g, "");
    const country = String(body?.dest_country ?? "Nigeria").trim();
    if (currency !== "NGN") {
      return out({
        error: "Only NGN bank-transfer requests are currently supported.",
      }, 400);
    }
    if (
      !Number.isFinite(amount) || amount <= 0 || amount > 5000000 ||
      !recipient || !bank || !/^\d{3,6}$/.test(bankCode) ||
      !/^\d{10}$/.test(account)
    ) {
      return out({
        error:
          "Check the amount, recipient, bank code and 10-digit account number.",
      }, 400);
    }
    const admin = createClient(url, service, {
      auth: { persistSession: false, autoRefreshToken: false },
    });
    const { data: profile, error: profileError } = await admin.from("profiles")
      .select("kyc_status,kyc_level").eq("id", authData.user.id).single();
    if (profileError || !profile) {
      return out({ error: "Wallet profile not found." }, 404);
    }
    if (
      String(profile.kyc_status).toLowerCase() !== "verified" ||
      Number(profile.kyc_level ?? 0) < 2
    ) {
      return out({
        error:
          "Complete approved Level 2 identity verification before requesting a bank transfer.",
      }, 403);
    }
    const { data: wallet, error: walletError } = await admin.from("wallets")
      .select("balance").eq("user_id", authData.user.id).eq("code", "NGN")
      .single();
    if (walletError || !wallet || Number(wallet.balance) < amount) {
      return out({ error: "Insufficient available NGN balance." }, 400);
    }
    const { data: request, error: insertError } = await admin.from(
      "wallet_transfer_requests",
    ).insert({
      user_id: authData.user.id,
      currency: "NGN",
      amount,
      recipient_name: recipient,
      bank_name: bank,
      bank_code: bankCode,
      account_number: account,
      country,
      status: "pending_review",
    }).select("id,status,created_at").single();
    if (insertError) throw insertError;
    // This is a request only: no wallet debit and no payout are initiated by this endpoint.
    return out({
      ok: true,
      request_id: request.id,
      status: request.status,
      message:
        "Transfer request submitted for review. No money has been sent or deducted.",
    }, 202);
  } catch (_) {
    return out({ error: "Transfer request could not be submitted." }, 500);
  }
});

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
    const { data: authData, error: authError } = await userClient.auth
      .getUser();
    if (authError || !authData.user) {
      return out({ error: "Invalid session" }, 401);
    }
    const environment = Deno.env.get("FLW_ENVIRONMENT"),
      enabled = Deno.env.get("FLW_LIVE_ENABLED") === "true";
    const secret = Deno.env.get("FLUTTERWAVE_SECRET_KEY") ?? "";
    if (
      environment !== "live" || !enabled || !secret.startsWith("FLWSECK-") ||
      secret.startsWith("FLWSECK_TEST-")
    ) {
      return out({
        error: "Flutterwave Live sync is not enabled on the server.",
      }, 503);
    }
    const admin = createClient(url, service, {
      auth: { persistSession: false, autoRefreshToken: false },
    });
    const { data: profile, error: profileError } = await admin.from("profiles")
      .select("virtual_account_number,virtual_order_ref").eq(
        "id",
        authData.user.id,
      ).single();
    if (
      profileError || !profile?.virtual_account_number ||
      !profile?.virtual_order_ref
    ) return out({ error: "No Nedu Wallet virtual account is linked." }, 404);
    const listRes = await fetch(
      "https://api.flutterwave.com/v3/virtual-account-numbers/" +
        encodeURIComponent(profile.virtual_account_number) + "/transactions",
      {
        headers: {
          Authorization: "Bearer " + secret,
          Accept: "application/json",
        },
        signal: AbortSignal.timeout(20000),
      },
    );
    const list = await listRes.json();
    if (!listRes.ok || list.status !== "success" || !Array.isArray(list.data)) {
      return out({
        error: "Flutterwave could not retrieve account transactions.",
      }, 502);
    }
    let credited = 0, duplicates = 0, checked = 0;
    for (const item of list.data) {
      const id = String(item?.id ?? "");
      if (!/^\d+$/.test(id) || item?.status !== "successful") continue;
      const vr = await fetch(
        "https://api.flutterwave.com/v3/transactions/" +
          encodeURIComponent(id) + "/verify",
        {
          headers: {
            Authorization: "Bearer " + secret,
            Accept: "application/json",
          },
          signal: AbortSignal.timeout(15000),
        },
      );
      const v = await vr.json(), tx = v?.data;
      if (
        !vr.ok || v?.status !== "success" || !tx || String(tx.id) !== id ||
        tx.status !== "successful" ||
        String(tx.currency).toUpperCase() !== "NGN" ||
        String(tx.payment_type ?? "").toLowerCase() !== "bank_transfer" ||
        String(tx.tx_ref ?? "") !== String(profile.virtual_order_ref) ||
        !Number.isFinite(Number(tx.amount)) || Number(tx.amount) <= 0
      ) continue;
      checked++;
      const { data: wasCredited, error: creditError } = await admin.rpc(
        "credit_verified_flutterwave_funding",
        {
          p_user_id: authData.user.id,
          p_transaction_id: id,
          p_tx_ref: String(tx.tx_ref),
          p_amount: Number(tx.amount),
          p_currency: "NGN",
          p_payload: {
            flw_ref: tx.flw_ref ?? null,
            payment_type: tx.payment_type ?? null,
          },
        },
      );
      if (creditError) throw creditError;
      if (wasCredited === true) credited += Number(tx.amount);
      else duplicates++;
    }
    return out({
      ok: true,
      verified: checked,
      credited,
      credited_amount: credited,
      duplicates,
    });
  } catch (_) {
    return out({ error: "Payment sync failed. Please retry later." }, 500);
  }
});

import { serve } from "https://deno.land/std@0.168.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

const headers = {
  "Content-Type": "application/json",
  "Cache-Control": "no-store",
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, apikey, content-type, x-client-info",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};
const reply = (data: unknown, status = 200) =>
  new Response(JSON.stringify(data), { status, headers });

serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers });
  if (req.method !== "POST") return reply({ error: "Method not allowed" }, 405);
  const authorization = req.headers.get("Authorization") ?? "";
  if (!authorization.startsWith("Bearer ")) {
    return reply({ error: "Authentication required" }, 401);
  }

  try {
    const url = Deno.env.get("SUPABASE_URL")!;
    const anon = Deno.env.get("SUPABASE_ANON_KEY")!;
    const service = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
    const userClient = createClient(url, anon, {
      global: { headers: { Authorization: authorization } },
      auth: { persistSession: false },
    });
    const { data: authData, error: authError } = await userClient.auth.getUser();
    if (authError || !authData.user) return reply({ error: "Invalid session" }, 401);

    const environment = Deno.env.get("FLW_ENVIRONMENT");
    const liveEnabled = Deno.env.get("FLW_LIVE_ENABLED") === "true";
    const secret = Deno.env.get("FLUTTERWAVE_SECRET_KEY") ?? "";
    if (
      environment !== "live" || !liveEnabled ||
      !secret.startsWith("FLWSECK-") || secret.startsWith("FLWSECK_TEST-")
    ) {
      return reply({ error: "Flutterwave Live sync is not enabled on the server." }, 503);
    }

    const admin = createClient(url, service, {
      auth: { persistSession: false, autoRefreshToken: false },
    });
    const { data: profile, error: profileError } = await admin
      .from("profiles")
      .select("virtual_account_number, virtual_order_ref, created_at")
      .eq("id", authData.user.id)
      .single();
    if (
      profileError || !profile?.virtual_account_number ||
      !profile?.virtual_order_ref
    ) {
      return reply({ error: "This virtual account needs provider reconciliation before payment sync." }, 409);
    }

    const fromDate = new Date(profile.created_at || Date.now() - 365 * 86400000)
      .toISOString().slice(0, 10);
    const toDate = new Date().toISOString().slice(0, 10);
    const transactions: Record<string, unknown>[] = [];
    let totalPages = 1;

    for (let page = 1; page <= totalPages; page++) {
      const endpoint = new URL("https://api.flutterwave.com/v3/transactions");
      endpoint.searchParams.set("from", fromDate);
      endpoint.searchParams.set("to", toDate);
      endpoint.searchParams.set("page", String(page));
      endpoint.searchParams.set("tx_ref", String(profile.virtual_order_ref));
      endpoint.searchParams.set("status", "successful");
      endpoint.searchParams.set("currency", "NGN");

      const listResponse = await fetch(endpoint, {
        headers: { Authorization: "Bearer " + secret, Accept: "application/json" },
        signal: AbortSignal.timeout(20000),
      });
      const list = await listResponse.json();
      if (!listResponse.ok || list?.status !== "success" || !Array.isArray(list.data)) {
        return reply({ error: "Flutterwave could not retrieve transactions for this account reference." }, 502);
      }
      transactions.push(...list.data);
      totalPages = Number(list?.meta?.page_info?.total_pages ?? 1);
      if (!Number.isInteger(totalPages) || totalPages < 1 || totalPages > 100) {
        return reply({ error: "Too many transactions for automatic reconciliation. Contact support." }, 409);
      }
    }

    let credited = 0;
    let duplicates = 0;
    let verified = 0;
    for (const item of transactions) {
      const id = String(item?.id ?? "");
      if (!/^\d+$/.test(id)) continue;

      const verifyResponse = await fetch(
        "https://api.flutterwave.com/v3/transactions/" + encodeURIComponent(id) + "/verify",
        {
          headers: { Authorization: "Bearer " + secret, Accept: "application/json" },
          signal: AbortSignal.timeout(15000),
        },
      );
      const verification = await verifyResponse.json();
      const tx = verification?.data;
      if (
        !verifyResponse.ok || verification?.status !== "success" ||
        !tx || String(tx.id) !== id || tx.status !== "successful" ||
        String(tx.currency).toUpperCase() !== "NGN" ||
        String(tx.payment_type ?? "").toLowerCase() !== "bank_transfer" ||
        String(tx.tx_ref ?? "") !== String(profile.virtual_order_ref) ||
        !Number.isFinite(Number(tx.amount)) || Number(tx.amount) <= 0
      ) continue;

      verified++;
      const { data: wasCredited, error: creditError } = await admin.rpc(
        "credit_verified_flutterwave_funding",
        {
          p_user_id: authData.user.id,
          p_transaction_id: id,
          p_tx_ref: String(tx.tx_ref),
          p_amount: Number(tx.amount),
          p_currency: "NGN",
          p_payload: { flw_ref: tx.flw_ref ?? null, payment_type: tx.payment_type ?? null },
        },
      );
      if (creditError) throw creditError;
      if (wasCredited === true) credited += Number(tx.amount);
      else duplicates++;
    }

    return reply({
      ok: true,
      verified,
      credited,
      credited_amount: credited,
      duplicates,
    });
  } catch (_) {
    return reply({ error: "Payment sync failed. Please retry later." }, 500);
  }
});
import { serve } from "https://deno.land/std@0.168.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

const headers = {
  "Content-Type": "application/json",
  "Cache-Control": "no-store",
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers":
    "authorization, apikey, content-type, x-client-info",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};

const reply = (data: unknown, status = 200) =>
  new Response(JSON.stringify(data), { status, headers });

serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers });
  if (req.method !== "POST") {
    return reply({ error: "Method not allowed" }, 405);
  }

  try {
    const url = Deno.env.get("SUPABASE_URL")!;
    const anon = Deno.env.get("SUPABASE_ANON_KEY")!;
    const service = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
    const secret = Deno.env.get("FLUTTERWAVE_SECRET_KEY") ?? "";
    const authorization = req.headers.get("Authorization") ?? "";

    if (!authorization.startsWith("Bearer ")) {
      return reply({ error: "Authentication required" }, 401);
    }

    const userClient = createClient(url, anon, {
      global: { headers: { Authorization: authorization } },
      auth: { persistSession: false, autoRefreshToken: false },
    });
    const { data: auth, error: authError } = await userClient.auth.getUser();

    if (authError || !auth.user) {
      return reply({ error: "Unauthorized" }, 401);
    }

    const { purchase_id } = await req.json();
    if (typeof purchase_id !== "string") {
      return reply({ error: "A purchase ID is required." }, 400);
    }

    const admin = createClient(url, service, {
      auth: { persistSession: false, autoRefreshToken: false },
    });
    const { data: purchase, error } = await admin
      .from("purchase_transactions")
      .select("*")
      .eq("id", purchase_id)
      .eq("user_id", auth.user.id)
      .maybeSingle();

    if (error || !purchase) {
      return reply({ error: "Purchase not found." }, 404);
    }

    if (
      purchase.type === "bills" &&
      purchase.wallet_debit_status === "reserved"
    ) {
      if (
        !secret.startsWith("FLWSECK-") ||
        secret.startsWith("FLWSECK_TEST-")
      ) {
        return reply({
          error: "Provider status unavailable. Funds remain reserved.",
        }, 503);
      }

      const reference = String(purchase.provider_reference ?? "");
      if (!/^NEDU-BILL-[A-F0-9]{32}$/.test(reference)) {
        return reply({
          error:
            "Purchase reference needs manual review. Funds remain reserved.",
        }, 409);
      }

      const response = await fetch(
        "https://api.flutterwave.com/v3/bills/" +
          encodeURIComponent(reference) +
          "?verbose=1",
        {
          headers: {
            Authorization: "Bearer " + secret,
            Accept: "application/json",
          },
          signal: AbortSignal.timeout(18000),
        },
      );
      const result = await response.json().catch(() => ({}));

      if (!response.ok || result?.status !== "success" || !result?.data) {
        return reply({
          purchase,
          pending_reconciliation: true,
          message:
            "Flutterwave has not confirmed a final result. Funds remain reserved.",
        }, 202);
      }

      const data = result.data;
      const providerStatus = String(
        data.status ?? data.transaction_status ?? data.bill_status ?? "",
      ).toLowerCase();
      const finalStatus = ["successful", "success", "completed"].includes(
          providerStatus,
        )
        ? "successful"
        : ["failed", "reversed", "cancelled"].includes(providerStatus)
        ? "failed"
        : "pending";

      const { error: settleError } = await admin.rpc(
        "settle_wallet_purchase",
        {
          p_purchase_id: purchase.id,
          p_user_id: auth.user.id,
          p_provider_status: finalStatus,
          p_provider_reference: String(data.flw_ref ?? ""),
          p_delivery_code: String(data.extra ?? data.token ?? ""),
          p_provider_payload: {
            flw_ref: data.flw_ref ?? null,
            provider_status: data.status ?? null,
            status_checked_at: new Date().toISOString(),
          },
        },
      );

      if (settleError) {
        return reply({
          error:
            "Settlement update failed safely. Funds remain reserved for review.",
        }, 500);
      }

      const { data: latest } = await admin
        .from("purchase_transactions")
        .select("*")
        .eq("id", purchase.id)
        .single();

      return reply({
        purchase: latest,
        pending_reconciliation: finalStatus === "pending",
      }, finalStatus === "pending" ? 202 : 200);
    }

    let extra = null;
    if (purchase.type === "esim") {
      const { data } = await admin
        .from("esim_orders")
        .select("*")
        .eq("purchase_id", purchase_id)
        .maybeSingle();
      extra = data;
    }
    if (purchase.type === "gift_cards") {
      const { data } = await admin
        .from("gift_card_orders")
        .select("*")
        .eq("purchase_id", purchase_id)
        .maybeSingle();
      extra = data;
    }

    return reply({ purchase, extra });
  } catch (_) {
    return reply({
      error: "Purchase status could not be checked. Please retry.",
    }, 500);
  }
});

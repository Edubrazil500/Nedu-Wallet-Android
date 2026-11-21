import { serve } from "https://deno.land/std@0.168.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

function json(data: unknown, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: {
      "Content-Type": "application/json",
      "Cache-Control": "no-store",
    },
  });
}
function safeEqual(a: string, b: string): boolean {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

serve(async (req) => {
  if (req.method !== "POST") return json({ error: "Method not allowed" }, 405);

  const expectedHash = Deno.env.get("FLW_WEBHOOK_SECRET_HASH");
  const suppliedHash = req.headers.get("verif-hash") ?? "";
  if (!expectedHash || !safeEqual(suppliedHash, expectedHash)) {
    return json({ error: "Webhook authentication failed" }, 401);
  }

  const secret = Deno.env.get("FLUTTERWAVE_SECRET_KEY");
  const supabaseUrl = Deno.env.get("SUPABASE_URL");
  const serviceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
  if (!secret || !supabaseUrl || !serviceKey) {
    return json({ error: "Server configuration incomplete" }, 503);
  }

  try {
    const body = await req.json();
    if (body?.event !== "charge.completed") {
      return json({ received: true, ignored: true });
    }

    const eventData = body?.data ?? {};
    const transactionId = String(eventData.id ?? "").trim();
    if (!/^\d+$/.test(transactionId) || eventData.status !== "successful") {
      return json({ received: true, ignored: true });
    }

    // Never trust webhook fields for crediting. Re-query Flutterwave's authoritative API.
    const verifyResponse = await fetch(
      "https://api.flutterwave.com/v3/transactions/" +
        encodeURIComponent(transactionId) + "/verify",
      {
        method: "GET",
        headers: {
          Authorization: "Bearer " + secret,
          Accept: "application/json",
        },
        signal: AbortSignal.timeout(15000),
      },
    );
    const verified = await verifyResponse.json();
    const tx = verified?.data;
    if (
      !verifyResponse.ok || verified?.status !== "success" || !tx ||
      String(tx.id) !== transactionId || tx.status !== "successful" ||
      String(tx.currency).toUpperCase() !== "NGN" ||
      String(tx.payment_type ?? "").toLowerCase() !== "bank_transfer" ||
      !Number.isFinite(Number(tx.amount)) || Number(tx.amount) <= 0 ||
      String(tx.tx_ref ?? "") !== String(eventData.tx_ref ?? "") ||
      Math.abs(Number(tx.amount) - Number(eventData.amount)) > 0.01
    ) {
      return json({ received: true, rejected: true });
    }

    // Static virtual-account references are created by this backend and stored per profile.
    const supabase = createClient(supabaseUrl, serviceKey, {
      auth: { persistSession: false, autoRefreshToken: false },
    });
    const { data: profile, error: profileError } = await supabase
      .from("profiles").select("id").eq("virtual_order_ref", String(tx.tx_ref))
      .maybeSingle();
    if (profileError) throw profileError;
    if (!profile) return json({ received: true, pending_reconciliation: true });

    const { data: credited, error: creditError } = await supabase.rpc(
      "credit_verified_flutterwave_funding",
      {
        p_user_id: profile.id,
        p_transaction_id: transactionId,
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

    return json({
      received: true,
      credited: credited === true,
      duplicate: credited === false,
    });
  } catch (_error) {
    // Do not leak provider responses, account data, or secrets to the caller.
    return json({ error: "Webhook processing temporarily failed" }, 500);
  }
});

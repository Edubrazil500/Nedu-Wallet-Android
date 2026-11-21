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
  if (!a || a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}
async function validSignature(
  rawBody: string,
  secretHash: string,
  req: Request,
) {
  const signature = req.headers.get("flutterwave-signature") ?? "";
  if (signature) {
    const key = await crypto.subtle.importKey(
      "raw",
      new TextEncoder().encode(secretHash),
      { name: "HMAC", hash: "SHA-256" },
      false,
      ["sign"],
    );
    const digest = await crypto.subtle.sign(
      "HMAC",
      key,
      new TextEncoder().encode(rawBody),
    );
    const expected = btoa(String.fromCharCode(...new Uint8Array(digest)));
    if (safeEqual(signature, expected)) return true;
  }
  const legacyHash = req.headers.get("verif-hash") ?? "";
  return safeEqual(legacyHash, secretHash);
}
function obj(value: unknown): Record<string, unknown> {
  if (value && typeof value === "object" && !Array.isArray(value)) {
    return value as Record<string, unknown>;
  }
  return {};
}
function normalizeAccount(value: unknown): string {
  return String(value ?? "").replace(/\D/g, "");
}
function metaObject(value: unknown): Record<string, unknown> {
  if (value && typeof value === "object" && !Array.isArray(value)) {
    return value as Record<string, unknown>;
  }
  if (typeof value === "string") {
    try {
      return obj(JSON.parse(value));
    } catch (_) {}
  }
  return {};
}

serve(async (req) => {
  if (req.method !== "POST") return json({ error: "Method not allowed" }, 405);
  const secretHash = Deno.env.get("FLW_WEBHOOK_SECRET_HASH");
  if (!secretHash) {
    return json({ error: "Webhook authentication is not configured" }, 503);
  }
  const rawBody = await req.text();
  if (!await validSignature(rawBody, secretHash, req)) {
    return json({ error: "Webhook authentication failed" }, 401);
  }

  const secret = Deno.env.get("FLUTTERWAVE_SECRET_KEY") ?? "";
  const supabaseUrl = Deno.env.get("SUPABASE_URL");
  const serviceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
  if (
    !secret.startsWith("FLWSECK-") || secret.startsWith("FLWSECK_TEST-") ||
    !supabaseUrl || !serviceKey
  ) {
    return json({ error: "Server configuration incomplete" }, 503);
  }

  try {
    const body = JSON.parse(rawBody);
    const eventData = obj(body?.data);
    const nestedEvent = obj(body?.event);
    const eventType = String(
      nestedEvent?.type ?? body?.["event.type"] ?? body?.event_type ??
      (typeof body?.event === "string" ? body.event : (body?.type ?? "")),
    ).toUpperCase();

    // Flutterwave virtual-account transfers may arrive as BANK_TRANSFER_TRANSACTION
    // or as charge.completed with the bank-transfer event type in the payload.
    const paymentType = String(
      eventData.payment_type ?? eventData.paymentType ?? ""
    ).toLowerCase();
    const isBankTransferEvent =
      eventType === "BANK_TRANSFER_TRANSACTION" ||
      (eventType === "CHARGE.COMPLETED" && paymentType === "bank_transfer");
    if (!isBankTransferEvent) return json({ received: true, ignored: true });

    const txRef = String(eventData.tx_ref ?? "").trim();
    const webhookId = String(eventData.id ?? "").trim();
    const webhookFlwRef = String(eventData.flw_ref ?? "").trim();
    const webhookAmount = Number(eventData.amount);
    const metadata = metaObject(
      body?.meta_data ?? eventData?.meta_data ?? body?.metadata,
    );
    const receivingAccount = normalizeAccount(
      metadata.virtualaccountnumber ??
      metadata.virtual_account_number ?? metadata.account_number ??
      metadata.virtualAccountNumber,
    );
    if (
      !txRef || txRef.length > 128 ||
      (receivingAccount.length > 0 && !/^\d{10}$/.test(receivingAccount))
    ) {
      return json({
        received: true,
        pending_reconciliation: true,
        reason: "Missing transaction or receiving-account metadata",
      });
    }

    // The signed webhook identifies the receiving virtual account. Independently verify
    // the payment with Flutterwave before any wallet credit.
    const byRefUrl = new URL(
      "https://api.flutterwave.com/v3/transactions/verify_by_reference",
    );
    byRefUrl.searchParams.set("tx_ref", txRef);
    const byRefResponse = await fetch(byRefUrl, {
      headers: {
        Authorization: "Bearer " + secret,
        Accept: "application/json",
      },
      signal: AbortSignal.timeout(15000),
    });
    const byRef = await byRefResponse.json();
    const listedTx = byRef?.data;
    const transactionId = String(listedTx?.id ?? "");
    if (
      !byRefResponse.ok || byRef?.status !== "success" ||
      !/^\d+$/.test(transactionId)
    ) {
      return json({
        received: true,
        pending_reconciliation: true,
        reason:
          "Flutterwave reference verification did not return a transaction ID",
      });
    }

    const verifyResponse = await fetch(
      "https://api.flutterwave.com/v3/transactions/" +
        encodeURIComponent(transactionId) + "/verify",
      {
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
      String(tx.tx_ref ?? "") !== txRef ||
      String(tx.currency ?? "").toUpperCase() !== "NGN" ||
      String(tx.payment_type ?? "").toLowerCase() !== "bank_transfer" ||
      !Number.isFinite(Number(tx.amount)) || Number(tx.amount) <= 0 ||
      (webhookId && webhookId !== transactionId) ||
      (webhookFlwRef && String(tx.flw_ref ?? "") !== webhookFlwRef) ||
      (Number.isFinite(webhookAmount) && webhookAmount > 0 &&
        Math.abs(Number(tx.amount) - webhookAmount) > 0.01)
    ) {
      return json({
        received: true,
        rejected: true,
        reason: "Provider verification did not match the signed webhook",
      });
    }

    const supabase = createClient(supabaseUrl, serviceKey, {
      auth: { persistSession: false, autoRefreshToken: false },
    });
    const profileQuery = supabase
      .from("profiles")
      .select("id, virtual_account_number, virtual_order_ref");
    const { data: profile, error: profileError } = receivingAccount
      ? await profileQuery
        .eq("virtual_account_number", receivingAccount)
        .maybeSingle()
      : await profileQuery.eq("virtual_order_ref", txRef).maybeSingle();
    if (profileError) throw profileError;
    if (
      !profile ||
      (receivingAccount &&
        normalizeAccount(profile.virtual_account_number) !== receivingAccount) ||
      (!receivingAccount && String(profile.virtual_order_ref ?? "") !== txRef)
    ) {
      return json({
        received: true,
        pending_reconciliation: true,
        reason:
          "No unique wallet profile matches the verified transaction reference or signed receiving account",
      });
    }
    if (
      profile.virtual_order_ref &&
      String(profile.virtual_order_ref) !== txRef
    ) {
      return json({
        received: true,
        pending_reconciliation: true,
        reason:
          "Transaction reference conflicts with the profile's saved virtual-account reference",
      });
    }

    const { data: credited, error: creditError } = await supabase.rpc(
      "credit_verified_flutterwave_funding",
      {
        p_user_id: profile.id,
        p_transaction_id: transactionId,
        p_tx_ref: txRef,
        p_amount: Number(tx.amount),
        p_currency: "NGN",
        p_payload: {
          flw_ref: tx.flw_ref ?? null,
          payment_type: tx.payment_type ?? null,
          receiving_account_number: receivingAccount,
          originator_name: metadata.originatorname ?? null,
          originator_bank: metadata.bankname ?? null,
          reconciliation_method:
            "signed_webhook_plus_provider_reference_and_id_verification",
        },
      },
    );
    if (creditError) throw creditError;
    return json({
      received: true,
      verified: true,
      credited: credited === true,
      duplicate: credited === false,
      transaction_id: transactionId,
      amount: Number(tx.amount),
      currency: "NGN",
      receiving_account_confirmed: Boolean(receivingAccount),
      profile_mapping_method: receivingAccount
        ? "receiving_account"
        : "stored_virtual_order_ref",
    });
  } catch (_) {
    return json({ error: "Webhook processing temporarily failed" }, 500);
  }
});

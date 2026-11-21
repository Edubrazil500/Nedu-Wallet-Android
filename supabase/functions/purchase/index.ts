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
const reply = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers });

function validCode(value: unknown, pattern: RegExp): string {
  const v = String(value ?? "").trim();
  if (!pattern.test(v)) throw new Error("Invalid bill provider code.");
  return v;
}
async function flwRequest(
  path: string,
  secret: string,
  init: RequestInit = {},
) {
  const response = await fetch("https://api.flutterwave.com/v3" + path, {
    ...init,
    headers: {
      Authorization: "Bearer " + secret,
      Accept: "application/json",
      "Content-Type": "application/json",
      ...(init.headers || {}),
    },
    signal: AbortSignal.timeout(18000),
  });
  const body = await response.json().catch(() => ({}));
  if (!response.ok || body?.status !== "success") {
    const error = new Error(
      String(
        body?.message || "Flutterwave bill service is temporarily unavailable.",
      ),
    );
    (error as any).httpStatus = response.status;
    throw error;
  }
  return body;
}
function normalizedBillStatus(data: any): "successful" | "failed" | "pending" {
  const s = String(
    data?.status ?? data?.transaction_status ?? data?.bill_status ?? "",
  ).toLowerCase();
  if (["successful", "success", "completed"].includes(s)) return "successful";
  if (["failed", "reversed", "cancelled"].includes(s)) return "failed";
  return "pending";
}

serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers });
  if (req.method !== "POST") return reply({ error: "Method not allowed" }, 405);

  try {
    const url = Deno.env.get("SUPABASE_URL");
    const anon = Deno.env.get("SUPABASE_ANON_KEY");
    const service = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
    const secret = Deno.env.get("FLUTTERWAVE_SECRET_KEY") ?? "";
    if (!url || !anon || !service) {
      return reply({ error: "Server configuration is incomplete." }, 503);
    }

    const authorization = req.headers.get("Authorization") ?? "";
    if (!authorization.startsWith("Bearer ")) {
      return reply({ error: "Authentication required." }, 401);
    }
    const userClient = createClient(url, anon, {
      global: { headers: { Authorization: authorization } },
      auth: { persistSession: false, autoRefreshToken: false },
    });
    const { data: auth, error: authError } = await userClient.auth.getUser();
    if (authError || !auth.user) {
      return reply({ error: "Invalid session." }, 401);
    }

    if (!secret.startsWith("FLWSECK-") || secret.startsWith("FLWSECK_TEST-")) {
      return reply({
        error:
          "Flutterwave Live bill services are not configured on the server.",
      }, 503);
    }

    const body = await req.json();
    const action = String(body?.action ?? "").toLowerCase();
    const admin = createClient(url, service, {
      auth: { persistSession: false, autoRefreshToken: false },
    });

    // Provider catalog is read-only and never debits a wallet.
    if (action === "categories") {
      const result = await flwRequest(
        "/top-bill-categories?country=NG",
        secret,
      );
      return reply({ categories: result.data ?? [] });
    }
    if (action === "billers") {
      const category = validCode(body?.category_code, /^[A-Z0-9_]{2,32}$/);
      const result = await flwRequest(
        "/bills/" + encodeURIComponent(category) + "/billers?country=NG",
        secret,
      );
      return reply({ billers: result.data ?? [] });
    }
    if (action === "items") {
      const biller = validCode(body?.biller_code, /^BIL\d{2,6}$/);
      const result = await flwRequest(
        "/billers/" + encodeURIComponent(biller) + "/items",
        secret,
      );
      return reply({ items: result.data ?? [] });
    }

    const { data: profile, error: profileError } = await admin.from("profiles")
      .select("kyc_status,kyc_bvn_verified_at,kyc_nin_verified_at")
      .eq("id", auth.user.id).maybeSingle();
    if (profileError || !profile) {
      return reply({ error: "Wallet profile not found." }, 404);
    }
    if (
      profile.kyc_status !== "Verified" || !profile.kyc_bvn_verified_at ||
      !profile.kyc_nin_verified_at
    ) {
      return reply({
        error: "Complete both BVN and NIN verification before paying bills.",
        code: "KYC_REQUIRED",
      }, 403);
    }

    if (action === "validate") {
      const item = validCode(body?.item_code, /^[A-Z0-9_-]{2,32}$/);
      const customer = String(body?.customer_id ?? "").trim();
      if (
        customer.length < 5 || customer.length > 40 ||
        !/^[A-Za-z0-9+_-]+$/.test(customer)
      ) {
        return reply(
          { error: "Enter a valid customer or account number." },
          400,
        );
      }
      const result = await flwRequest(
        "/bill-items/" + encodeURIComponent(item) +
            "/validate?customer=" + encodeURIComponent(customer),
        secret,
      );
      const data = result.data ?? {};
      if (data.response_code && String(data.response_code) !== "00") {
        return reply({
          valid: false,
          message: String(
            data.response_message || "Customer details could not be validated.",
          ),
        }, 422);
      }
      return reply({ valid: true, customer: data });
    }

    if (action !== "buy") {
      return reply({ error: "Unsupported bill action." }, 400);
    }

    // Live purchases stay off until the provider has confirmed source-account
    // funding and whitelisted the actual egress IP used by this Edge Function.
    if (
      Deno.env.get("FLW_ENVIRONMENT") !== "live" ||
      Deno.env.get("FLW_LIVE_ENABLED") !== "true" ||
      Deno.env.get("FLW_BILLS_LIVE_ENABLED") !== "true" ||
      Deno.env.get("FLW_BILLS_IP_WHITELIST_CONFIRMED") !== "true"
    ) {
      return reply({
        error:
          "Bill purchases are not enabled. Complete Flutterwave bill-payment " +
          "activation and IP allowlisting first.",
        disabled: true,
        no_money_moved: true,
      }, 503);
    }

    const idempotencyKey = String(body?.idempotency_key ?? "").trim();
    if (
      !/^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i
        .test(idempotencyKey)
    ) {
      return reply(
        { error: "A valid unique purchase request ID is required." },
        400,
      );
    }
    const category = validCode(body?.category_code, /^[A-Z0-9_]{2,32}$/);
    const biller = validCode(body?.biller_code, /^BIL\d{2,6}$/);
    const itemCode = validCode(body?.item_code, /^[A-Z0-9_-]{2,32}$/);
    const customer = String(body?.customer_id ?? "").trim();
    const amount = Number(body?.amount);
    if (
      customer.length < 5 || customer.length > 40 ||
      !/^[A-Za-z0-9+_-]+$/.test(customer) ||
        !Number.isFinite(amount) || amount <= 0 || amount > 10000000
    ) {
      return reply(
        { error: "Enter a valid customer number and bill amount." },
        400,
      );
    }

    // Never trust a client-supplied price. Resolve the item from Flutterwave.
    const itemList = await flwRequest(
      "/billers/" + encodeURIComponent(biller) + "/items",
      secret,
    );
    const item = (itemList.data ?? []).find(
      (x: any) =>
        String(x.item_code) === itemCode && String(x.biller_code) === biller,
    );
    if (!item) {
      return reply({
        error:
          "This bill package is no longer available. Refresh the bill list.",
      }, 409);
    }
    if (
      Number(item.amount) <= 0 || Math.abs(Number(item.amount) - amount) > 0.01
    ) {
      return reply({
        error:
          "The amount does not match the current provider price. Refresh and try again.",
      }, 409);
    }
    if (String(item.country ?? "NG").toUpperCase() !== "NG") {
      return reply({
        error: "Only Nigerian billers are supported by this wallet flow.",
      }, 400);
    }

    // Validate meter, decoder and account identifiers when Flutterwave marks
    // the selected item as resolvable. Airtime items do not require validation.
    if (item.is_resolvable === true && item.is_airtime !== true) {
      const validation = await flwRequest(
        "/bill-items/" + encodeURIComponent(itemCode) +
        "/validate?customer=" + encodeURIComponent(customer),
        secret,
      );
      if (
        validation.data?.response_code &&
        String(validation.data.response_code) !== "00"
      ) {
        return reply({
          error: String(
            validation.data.response_message ||
              "Customer details could not be validated.",
          ),
        }, 422);
      }
    }

    const txRef = "NEDU-BILL-" +
      crypto.randomUUID().replace(/-/g, "").toUpperCase();
    let purchase: any = null;
    const { data: inserted, error: insertError } = await admin
      .from("purchase_transactions")
      .insert({
        user_id: auth.user.id,
        type: "bills",
        category,
        provider: "Flutterwave",
        provider_code: biller,
        account_number: customer,
        amount: Number(amount.toFixed(2)),
        currency: "NGN",
        pay_from_wallet: "NGN",
        status: "pending",
        provider_reference: txRef,
        idempotency_key: idempotencyKey,
        metadata: {
          item_code: itemCode,
          biller_name: String(item.biller_name || item.name || ""),
        },
      })
      .select("*")
      .single();

    if (insertError) {
      if (insertError.code === "23505") {
        const { data: existing } = await admin
          .from("purchase_transactions")
          .select("*")
          .eq("user_id", auth.user.id)
          .eq("idempotency_key", idempotencyKey)
          .maybeSingle();
        if (existing) {
          const oldMeta = existing.metadata ?? {};
          const same = Number(existing.amount) === Number(amount) &&
            String(existing.category ?? "") === category &&
            String(existing.provider_code ?? "") === biller &&
            String(existing.account_number ?? "") === customer &&
            String(oldMeta.item_code ?? "") === itemCode;
          if (!same) {
            return reply({
              error: "This request ID was already used for a different bill. " +
                "Generate a new request ID.",
            }, 409);
          }
          return reply({ purchase: existing, duplicate_request: true }, 200);
        }
      }
      return reply({
        error:
          "We could not create the bill request. No wallet debit was made.",
      }, 409);
    }
    purchase = inserted;

    const { error: reserveError } = await admin.rpc("reserve_wallet_purchase", {
      p_purchase_id: purchase.id,
      p_user_id: auth.user.id,
      p_wallet_code: "NGN",
      p_amount: amount,
    });
    if (reserveError) {
      await admin.from("purchase_transactions").update({
        status: "failed",
        error_message: reserveError.message,
        updated_at: new Date().toISOString(),
      }).eq("id", purchase.id).eq("user_id", auth.user.id);
      return reply({
        error: reserveError.message.includes("Insufficient")
          ? "Insufficient NGN wallet balance."
          : "Bill purchase could not be reserved. No provider payment " +
            "was sent.",
      }, 409);
    }

    // Once this request is sent, network failures are ambiguous. Keep funds
    // until status polling proves success or failure; never auto-refund
    // a timeout that might have resulted in a provider-side payment.
    try {
      await flwRequest(
        "/billers/" + encodeURIComponent(biller) + "/items/" +
          encodeURIComponent(itemCode) + "/payment",
        secret,
        {
          method: "POST",
          body: JSON.stringify({
            country: "NG",
            customer_id: customer,
            amount,
            reference: txRef,
          }),
        },
      );
      const statusResult = await flwRequest(
        "/bills/" + encodeURIComponent(txRef) + "?verbose=1",
        secret,
      );
      const providerData = statusResult.data ?? {};
      const finalStatus = normalizedBillStatus(providerData);
      const settled = await admin.rpc("settle_wallet_purchase", {
        p_purchase_id: purchase.id,
        p_user_id: auth.user.id,
        p_provider_status: finalStatus,
        p_provider_reference: String(providerData.flw_ref ?? ""),
        p_delivery_code: String(providerData.extra ?? providerData.token ?? ""),
        p_provider_payload: {
          flw_ref: providerData.flw_ref ?? null,
          provider_status: providerData.status ?? null,
          item_code: itemCode,
        }
      });
      const { data: latest } = await admin
        .from("purchase_transactions")
        .select("*")
        .eq("id", purchase.id).single();
      return reply({
        purchase: latest,
        settlement: settled.data,
        provider_status: finalStatus,
        pending_reconciliation: finalStatus === "pending",
      }, finalStatus === "pending" ? 202 : 200);
    } catch (providerError) {
      const rejectedCode = Number((providerError as any)?.httpStatus);
      let confirmedFailure = false;
      if ([400, 401, 403, 422].includes(rejectedCode)) {
        try {
          const probe = await fetch(
            "https://api.flutterwave.com/v3/bills/" +
              encodeURIComponent(txRef) +
              "?verbose=1",
            {
              headers: {
                Authorization: "Bearer " + secret,
                Accept: "application/json",
              },
              signal: AbortSignal.timeout(12000),
            },
          );
          if (probe.status === 404) confirmedFailure = true;
        } catch (_) {}
      }
      if (confirmedFailure) {
        await admin.rpc("settle_wallet_purchase", {
          p_purchase_id: purchase.id,
          p_user_id: auth.user.id,
          p_provider_status: "failed",
          p_provider_reference: null,
          p_delivery_code: null,
          p_provider_payload: {
            state: "provider_rejected_and_reference_not_found",
            item_code: itemCode,
          }
        });
        const { data: latest } = await admin
          .from("purchase_transactions")
          .select("*")
          .eq("id", purchase.id).single();
        return reply({
          purchase: latest,
          refunded: true,
          message: "Flutterwave rejected the request. The reserved amount " +
            "has been returned to your NGN wallet.",
        }, 200);
      }
      await admin.rpc("settle_wallet_purchase", {
        p_purchase_id: purchase.id,
        p_user_id: auth.user.id,
        p_provider_status: "pending",
        p_provider_reference: null,
        p_delivery_code: null,
        p_provider_payload: {
          state: "provider_result_unknown",
          item_code: itemCode,
        }
      });
      const { data: latest } = await admin
        .from("purchase_transactions")
        .select("*")
        .eq("id", purchase.id).single();
      return reply({
        purchase: latest,
        pending_reconciliation: true,
        message: "The provider response is still being confirmed. Your funds " +
          "remain reserved; do not pay again.",
      }, 202);
    }
  } catch (error) {
    const status = Number((error as any)?.httpStatus) === 400 ? 400 : 502;
    return reply({
      error: error instanceof Error
        ? error.message
        : "Bill service is temporarily unavailable.",
    }, status);
  }
});
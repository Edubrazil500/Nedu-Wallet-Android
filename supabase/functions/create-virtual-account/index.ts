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
function reply(data: unknown, status = 200) {
  return new Response(JSON.stringify(data), { status, headers });
}

serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers });
  if (req.method !== "POST") return reply({ error: "Method not allowed" }, 405);
  try {
    const authHeader = req.headers.get("Authorization") ?? "";
    if (!authHeader.startsWith("Bearer ")) {
      return reply({ error: "Authentication required" }, 401);
    }
    const url = Deno.env.get("SUPABASE_URL")!;
    const anon = Deno.env.get("SUPABASE_ANON_KEY")!;
    const service = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
    const userClient = createClient(url, anon, {
      global: { headers: { Authorization: authHeader } },
      auth: { persistSession: false },
    });
    const { data: authData, error: authError } = await userClient.auth
      .getUser();
    if (authError || !authData.user) {
      return reply({ error: "Invalid session" }, 401);
    }

    const environment = Deno.env.get("FLW_ENVIRONMENT");
    const liveEnabled = Deno.env.get("FLW_LIVE_ENABLED") === "true";
    const secret = Deno.env.get("FLUTTERWAVE_SECRET_KEY") ?? "";
    if (
      environment !== "live" || !liveEnabled ||
      !secret.startsWith("FLWSECK-") || secret.startsWith("FLWSECK_TEST-")
    ) {
      return reply({
        error:
          "Flutterwave Live account creation is not enabled. Complete the server-side Live configuration first.",
      }, 503);
    }

    const body = await req.json();
    const currency = String(body?.currency ?? "NGN").toUpperCase();
    if (currency !== "NGN") {
      return reply({ error: "Only NGN virtual accounts are supported by the configured Flutterwave product. No foreign-currency account was created." }, 400);
    }
    const bvn = String(body?.bvn ?? "").replace(/\s/g, "");
    if (!/^\d{11}$/.test(bvn)) {
      return reply({
        error:
          "A valid 11-digit BVN is required by Flutterwave for a permanent NGN virtual account.",
      }, 400);
    }

    const admin = createClient(url, service, {
      auth: { persistSession: false, autoRefreshToken: false },
    });
    const { data: profile, error: profileError } = await admin.from("profiles")
      .select(
        "id,email,name,full_name,phone,virtual_account_number,virtual_bank_name,virtual_order_ref",
      )
      .eq("id", authData.user.id).single();
    if (profileError || !profile) {
      return reply({ error: "Your wallet profile could not be loaded." }, 404);
    }
    if (profile.virtual_account_number) {
      return reply({
        account_number: profile.virtual_account_number,
        bank_name: profile.virtual_bank_name ?? "",
        order_ref: profile.virtual_order_ref ?? "",
        currency: "NGN",
        existing: true,
        reconciliation_required: !profile.virtual_order_ref,
      });
    }

    const email = profile.email || authData.user.email;
    const fullName = String(
      profile.full_name || profile.name ||
        authData.user.user_metadata?.full_name || "",
    ).trim();
    const parts = fullName.split(/\s+/).filter(Boolean);
    const first = parts.shift() || "Nedu";
    const last = parts.join(" ") || "Wallet";
    if (!email) {
      return reply({ error: "A verified email address is required." }, 400);
    }
    const txRef = "NEDUVA-" + crypto.randomUUID().replace(/-/g, "");
    const payload = {
      email,
      currency: "NGN",
      firstname: first,
      lastname: last,
      tx_ref: txRef,
      is_permanent: true,
      bvn,
      phonenumber: profile.phone || undefined,
      narration: "Nedu Wallet",
    };
    const response = await fetch(
      "https://api.flutterwave.com/v3/virtual-account-numbers",
      {
        method: "POST",
        headers: {
          Authorization: "Bearer " + secret,
          "Content-Type": "application/json",
          Accept: "application/json",
        },
        body: JSON.stringify(payload),
        signal: AbortSignal.timeout(20000),
      },
    );
    const result = await response.json();
    const account = result?.data;
    if (
      !response.ok || result?.status !== "success" ||
      !account?.account_number || !account?.bank_name
    ) {
      return reply({
        error:
          "Flutterwave could not create the virtual account. No wallet or KYC status was changed.",
      }, 502);
    }

    const { error: saveError } = await admin.from("profiles").update({
      virtual_account_number: String(account.account_number),
      virtual_bank_name: String(account.bank_name),
      virtual_order_ref: txRef,
    }).eq("id", authData.user.id);
    if (saveError) {
      return reply({
        error:
          "The provider created an account, but saving it failed. Contact support before retrying.",
      }, 500);
    }

    return reply({
      account_number: String(account.account_number),
      bank_name: String(account.bank_name),
      order_ref: txRef,
      currency: "NGN",
    });
  } catch (_error) {
    return reply({
      error: "Virtual account request failed. Please try again later.",
    }, 500);
  }
});

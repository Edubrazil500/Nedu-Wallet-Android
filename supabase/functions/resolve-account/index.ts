import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers":
    "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...corsHeaders, "Content-Type": "application/json" },
  });
}

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") {
    return new Response("ok", { headers: corsHeaders });
  }
  if (req.method !== "POST") {
    return json({ error: "Method not allowed" }, 405);
  }

  const authorization = req.headers.get("Authorization") ?? "";
  const token = authorization.match(/^Bearer\s+(.+)$/i)?.[1];
  if (!token) {
    return json({ error: "Authentication required" }, 401);
  }

  const supabaseUrl = Deno.env.get("SUPABASE_URL");
  const anonKey = Deno.env.get("SUPABASE_ANON_KEY");
  const flutterwaveSecret = Deno.env.get("FLW_SECRET_KEY");
  const flutterwaveEnvironment = Deno.env.get("FLW_ENVIRONMENT")
    ?.trim()
    .toLowerCase();

  if (!supabaseUrl || !anonKey || !flutterwaveSecret) {
    return json(
      { error: "Account verification service is not configured" },
      503,
    );
  }

  // This feature branch is deliberately sandbox-only. A live secret must never
  // be used by development or staging code. Live-provider support requires a
  // separately reviewed production release, not just an environment toggle.
  if (
    flutterwaveEnvironment !== "test" ||
    !flutterwaveSecret.startsWith("FLWSECK_TEST-")
  ) {
    return json(
      {
        error:
          "Bank verification is disabled until a Flutterwave Test key is configured.",
      },
      503,
    );
  }

  const auth = createClient(supabaseUrl, anonKey, {
    auth: { persistSession: false, autoRefreshToken: false },
  });
  const { data: authData, error: authError } = await auth.auth.getUser(token);
  if (authError || !authData.user) {
    return json({ error: "Invalid or expired session" }, 401);
  }

  let body: unknown;
  try {
    const raw = await req.text();
    if (raw.length > 2048) {
      return json({ error: "Request is too large" }, 413);
    }
    body = JSON.parse(raw);
  } catch {
    return json({ error: "Invalid request body" }, 400);
  }
  if (!body || typeof body !== "object" || Array.isArray(body)) {
    return json({ error: "Invalid account details" }, 400);
  }

  const input = body as Record<string, unknown>;
  const accountNumber = typeof input.account_number === "string"
    ? input.account_number.trim()
    : "";
  const accountBank = typeof input.account_bank === "string"
    ? input.account_bank.trim()
    : "";
  if (!/^\d{10}$/.test(accountNumber)) {
    return json(
      { error: "Enter a valid 10-digit Nigerian account number" },
      400,
    );
  }
  if (!/^\d{3,6}$/.test(accountBank)) {
    return json({ error: "Select a valid bank" }, 400);
  }

  let providerResponse: Response;
  try {
    providerResponse = await fetch(
      "https://api.flutterwave.com/v3/accounts/resolve",
      {
        method: "POST",
        headers: {
          Authorization: `Bearer ${flutterwaveSecret}`,
          "Content-Type": "application/json",
          Accept: "application/json",
        },
        body: JSON.stringify({
          account_number: accountNumber,
          account_bank: accountBank,
        }),
        signal: AbortSignal.timeout(15000),
      },
    );
  } catch {
    return json(
      {
        error:
          "Bank verification is temporarily unavailable. Please try again.",
      },
      502,
    );
  }

  if (!providerResponse.ok) {
    // Never return provider response bodies, which may contain internal details.
    return json(
      {
        error: providerResponse.status === 429
          ? "Too many verification attempts. Please wait and try again."
          : "The bank could not verify these account details.",
      },
      providerResponse.status === 429 ? 429 : 422,
    );
  }

  let provider: Record<string, unknown>;
  try {
    provider = await providerResponse.json();
  } catch {
    return json(
      { error: "Invalid response from bank verification service" },
      502,
    );
  }
  const data = provider.data && typeof provider.data === "object"
    ? provider.data as Record<string, unknown>
    : {};
  const returnedNumber = typeof data.account_number === "string"
    ? data.account_number
    : "";
  const accountName = typeof data.account_name === "string"
    ? data.account_name.trim()
    : "";
  if (
    provider.status !== "success" || returnedNumber !== accountNumber ||
    accountName.length < 2
  ) {
    return json(
      { error: "The bank could not verify these account details." },
      422,
    );
  }

  return json({ account_name: accountName, account_number: returnedNumber });
});

import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers":
    "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "GET, OPTIONS",
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
  if (req.method !== "GET") {
    return json({ error: "Method not allowed" }, 405);
  }

  const authorization = req.headers.get("Authorization");
  if (!authorization?.startsWith("Bearer ")) {
    return json({ error: "Authentication required" }, 401);
  }

  const supabaseUrl = Deno.env.get("SUPABASE_URL");
  const anonKey = Deno.env.get("SUPABASE_ANON_KEY");
  if (!supabaseUrl || !anonKey) {
    return json({ error: "Server configuration error" }, 500);
  }

  // Use the caller's JWT, not a service-role client. RLS remains the
  // authorization boundary for profile and wallet reads.
  const supabase = createClient(supabaseUrl, anonKey, {
    global: { headers: { Authorization: authorization } },
    auth: { persistSession: false, autoRefreshToken: false },
  });

  const {
    data: { user },
    error: authError,
  } = await supabase.auth.getUser();

  if (authError || !user) {
    return json({ error: "Invalid or expired session" }, 401);
  }

  // This query matches the existing iOS project's profiles table. Do not
  // add columns such as first_name or is_active without a reviewed migration.
  const { data: profile, error: profileError } = await supabase
    .from("profiles")
    .select(
      "id,name,email,phone,country,kyc_status,kyc_level,full_name,avatar_url,virtual_account_number,virtual_bank_name",
    )
    .eq("id", user.id)
    .maybeSingle();

  if (profileError) {
    console.error("account-me: profile query failed", profileError.message);
    return json({ error: "Unable to load account" }, 500);
  }
  if (!profile) {
    return json({ error: "Account profile not found" }, 404);
  }

  // The existing application stores one row per currency, not one wallet
  // per user. Preserve database numeric values as strings to avoid JS
  // floating-point rounding of money.
  const { data: wallets, error: walletsError } = await supabase
    .from("wallets")
    .select("id,code,name,balance,is_crypto,address,updated_at")
    .eq("user_id", user.id)
    .order("code", { ascending: true });

  if (walletsError) {
    console.error("account-me: wallets query failed", walletsError.message);
    return json({ error: "Unable to load wallets" }, 500);
  }

  return json({
    user: {
      id: profile.id,
      name: profile.name,
      fullName: profile.full_name,
      email: profile.email,
      phone: profile.phone,
      country: profile.country,
      kycStatus: profile.kyc_status,
      kycLevel: profile.kyc_level,
      avatarUrl: profile.avatar_url,
      virtualAccount: profile.virtual_account_number
        ? {
          accountNumber: profile.virtual_account_number,
          bankName: profile.virtual_bank_name,
        }
        : null,
    },
    wallets: (wallets ?? []).map((wallet) => ({
      id: wallet.id,
      code: wallet.code,
      name: wallet.name,
      // Supabase/Postgres numeric values are intentionally not converted
      // to JavaScript Number.
      balance: wallet.balance === null ? null : String(wallet.balance),
      isCrypto: wallet.is_crypto,
      address: wallet.address,
      updatedAt: wallet.updated_at,
    })),
  });
});

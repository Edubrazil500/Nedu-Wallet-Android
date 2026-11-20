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

  const { data: profile, error: profileError } = await supabase
    .from("profiles")
    .select("id,email,phone,first_name,last_name,is_active")
    .eq("id", user.id)
    .maybeSingle();
  if (profileError) {
    return json({ error: "Unable to load account" }, 500);
  }
  if (!profile || !profile.is_active) {
    return json({ error: "Account unavailable" }, 403);
  }

  const { data: wallet, error: walletError } = await supabase
    .from("wallets")
    .select(
      "id,currency,status,ledger_accounts(id,ledger_entries(direction,amount))",
    )
    .eq("user_id", user.id)
    .maybeSingle();
  if (walletError) {
    return json({ error: "Unable to load wallet" }, 500);
  }
  if (!wallet) {
    return json({ error: "Wallet not found" }, 404);
  }

  const accounts = (wallet.ledger_accounts ?? []) as Array<{
    id: string;
    ledger_entries: Array<
      { direction: "DEBIT" | "CREDIT"; amount: number | string }
    >;
  }>;
  const balance = accounts.reduce(
    (sum, account) =>
      sum + account.ledger_entries.reduce(
        (accountSum, entry) =>
          accountSum +
          (entry.direction === "CREDIT" ? 1 : -1) * Number(entry.amount),
        0,
      ),
    0,
  );

  return json({
    user: {
      id: profile.id,
      email: profile.email,
      phone: profile.phone,
      firstName: profile.first_name,
      lastName: profile.last_name,
    },
    wallet: {
      id: wallet.id,
      currency: wallet.currency,
      status: wallet.status,
      availableBalance: balance.toFixed(2),
    },
  });
});

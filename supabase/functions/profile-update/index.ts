import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, apikey, content-type",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
  "Content-Type": "application/json",
};

function json(status: number, body: Record<string, unknown>) {
  return new Response(JSON.stringify(body), { status, headers: corsHeaders });
}

Deno.serve(async (request: Request) => {
  if (request.method === "OPTIONS") {
    return new Response("ok", { headers: corsHeaders });
  }
  if (request.method !== "POST") {
    return json(405, { error: "Method not allowed." });
  }

  const authorization = request.headers.get("Authorization") ?? "";
  const match = authorization.match(/^Bearer\\s+(.+)$/i);
  if (!match) return json(401, { error: "Please sign in again." });

  const supabaseUrl = Deno.env.get("SUPABASE_URL");
  const anonKey = Deno.env.get("SUPABASE_ANON_KEY");
  const serviceRoleKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
  if (!supabaseUrl || !anonKey || !serviceRoleKey) {
    return json(503, { error: "Profile service is not configured." });
  }

  // Verify the caller's access token before using the server-only admin client.
  const authClient = createClient(supabaseUrl, anonKey, {
    auth: { persistSession: false, autoRefreshToken: false },
  });
  const { data: authData, error: authError } = await authClient.auth.getUser(
    match[1],
  );
  if (authError || !authData.user) {
    return json(401, {
      error: "Your session is invalid or expired. Please sign in again.",
    });
  }

  const declaredLength = Number(request.headers.get("content-length") ?? "0");
  if (declaredLength > 4096) {
    return json(413, { error: "Request is too large." });
  }

  let input: unknown;
  try {
    input = await request.json();
  } catch {
    return json(400, { error: "Invalid request." });
  }
  if (!input || typeof input !== "object" || Array.isArray(input)) {
    return json(400, { error: "Invalid profile details." });
  }

  const body = input as Record<string, unknown>;
  const name = typeof body.name === "string" ? body.name.trim() : "";
  const phoneValue = body.phone;
  const phone = typeof phoneValue === "string" ? phoneValue.trim() : "";
  if (name.length < 2 || name.length > 120) {
    return json(400, { error: "Enter a name between 2 and 120 characters." });
  }
  if (
    phone.length > 24 || (phone !== "" && !/^[+0-9() .-]{7,24}$/.test(phone))
  ) {
    return json(400, {
      error: "Enter a valid phone number or leave it blank.",
    });
  }

  // Only allow profile display fields. The caller cannot choose a user ID,
  // email, KYC status, virtual-account fields, or any financial attribute.
  const update = {
    name,
    full_name: name,
    phone: phone || null,
  };

  const admin = createClient(supabaseUrl, serviceRoleKey, {
    auth: { persistSession: false, autoRefreshToken: false },
  });
  const { data, error } = await admin
    .from("profiles")
    .update(update)
    .eq("id", authData.user.id)
    .select("id")
    .maybeSingle();

  if (error) {
    // Do not expose database/provider details to the mobile client.
    return json(400, {
      error: "We could not save your profile. Check the details and try again.",
    });
  }
  if (!data) {
    return json(404, { error: "Your Nedu Wallet profile could not be found." });
  }

  return json(200, { success: true });
});

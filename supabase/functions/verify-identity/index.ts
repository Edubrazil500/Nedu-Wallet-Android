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

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers });
  if (req.method !== "POST") return reply({ error: "Method not allowed" }, 405);

  const authHeader = req.headers.get("Authorization") ?? "";
  if (!authHeader.startsWith("Bearer ")) {
    return reply({ error: "Authentication required." }, 401);
  }

  const supabaseUrl = Deno.env.get("SUPABASE_URL");
  const anonKey = Deno.env.get("SUPABASE_ANON_KEY");
  const serviceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
  const premblyKey = Deno.env.get("PREMBLY_SECRET_KEY");
  if (!supabaseUrl || !anonKey || !serviceKey) {
    return reply(
      { error: "Identity verification is temporarily unavailable." },
      503,
    );
  }
  if (!premblyKey) {
    return reply({
      error:
        "Prembly is selected but its server API key has not been configured. Verification is not available yet.",
      code: "PREMBLY_NOT_CONFIGURED",
    }, 503);
  }

  try {
    const userClient = createClient(supabaseUrl, anonKey, {
      global: { headers: { Authorization: authHeader } },
      auth: { persistSession: false, autoRefreshToken: false },
    });
    const { data: authData, error: authError } = await userClient.auth
      .getUser();
    if (authError || !authData.user) {
      return reply({ error: "Invalid session." }, 401);
    }

    const body = await req.json();
    const idType = String(body?.id_type ?? "").toLowerCase();
    const idNumber = String(body?.id_number ?? "").replace(/\s/g, "");
    const image = String(body?.selfie_base64 ?? "");
    if (!["bvn", "nin"].includes(idType)) {
      return reply({ error: "Choose BVN or NIN verification." }, 400);
    }
    if (!/^\d{11}$/.test(idNumber)) {
      return reply({ error: "Enter a valid 11-digit identity number." }, 400);
    }
    if (
      image.length < 100 || image.length > 8_000_000 ||
      !/^data:image\/(jpeg|jpg|png);base64,[A-Za-z0-9+/=\r\n]+$/.test(image)
    ) {
      return reply({
        error: "Upload a clear JPG or PNG selfie (maximum 6 MB).",
      }, 400);
    }

    const endpoint = idType === "bvn"
      ? "https://api.prembly.com/api/v1/verification/api/bvn_w_face/"
      : "https://api.prembly.com/api/v1/verification/api/nin_w_face/";
    const payload = idType === "bvn"
      ? { number: idNumber, image }
      : { number: idNumber, image };

    let providerResponse: Response;
    try {
      providerResponse = await fetch(endpoint, {
        method: "POST",
        headers: {
          "X-API-Key": premblyKey,
          "Content-Type": "application/json",
          "Accept": "application/json",
        },
        body: JSON.stringify(payload),
        signal: AbortSignal.timeout(25000),
      });
    } catch {
      return reply({
        error: "Prembly could not be reached. Please try again later.",
      }, 503);
    }

    let result: any;
    try {
      result = await providerResponse.json();
    } catch {
      return reply({
        error: "Prembly returned an unreadable response. Please try again.",
      }, 502);
    }
    if (!providerResponse.ok) {
      // Do not expose provider payloads: they can contain identity data.
      return reply({
        error: providerResponse.status === 429
          ? "Verification service is busy. Please wait and try again."
          : "The verification provider could not complete this check. Please try again.",
      }, providerResponse.status === 429 ? 429 : 502);
    }

    const data = result?.data ?? {};
    const face = data?.face_data ?? data?.nin_data?.face_data ?? {};
    const registryCode = String(
      result?.response_code ?? data?.response_code ?? "",
    );
    const faceCode = String(face?.response_code ?? "");
    const verified = result?.status === true &&
      registryCode === "00" &&
      face?.status === true &&
      faceCode === "00";

    if (!verified) {
      return reply({
        verified: false,
        id_type: idType,
        error:
          "We could not verify this identity and selfie match. Check your details and try again.",
      }, 200);
    }

    const admin = createClient(supabaseUrl, serviceKey, {
      auth: { persistSession: false, autoRefreshToken: false },
    });
    const now = new Date().toISOString();
    const update = idType === "bvn"
      ? { kyc_bvn_verified_at: now, kyc_provider: "Prembly" }
      : { kyc_nin_verified_at: now, kyc_provider: "Prembly" };

    const { data: profile, error: profileError } = await admin
      .from("profiles")
      .select("kyc_bvn_verified_at,kyc_nin_verified_at")
      .eq("id", authData.user.id)
      .single();
    if (profileError || !profile) {
      return reply({
        error: "Your wallet profile could not be updated. Contact support.",
      }, 500);
    }

    const bvnDone = idType === "bvn" || Boolean(profile.kyc_bvn_verified_at);
    const ninDone = idType === "nin" || Boolean(profile.kyc_nin_verified_at);
    const complete = bvnDone && ninDone;
    const finalUpdate: Record<string, unknown> = {
      ...update,
      kyc_status: complete ? "Verified" : "Pending",
      kyc_level: complete ? 2 : 1,
      kyc_verified_at: complete ? now : null,
    };
    const { error: saveError } = await admin
      .from("profiles")
      .update(finalUpdate)
      .eq("id", authData.user.id);
    if (saveError) {
      return reply({
        error:
          "The identity check passed, but saving its status failed. Contact support before retrying.",
      }, 500);
    }

    return reply({
      verified: true,
      id_type: idType,
      kyc_complete: complete,
      message: complete
        ? "BVN and NIN are both verified. Your fiat wallet identity checks are complete."
        : (idType === "bvn"
          ? "BVN verified. Complete NIN verification to unlock fiat wallet services."
          : "NIN verified. Complete BVN verification to unlock fiat wallet services."),
    });
  } catch {
    return reply({
      error: "Identity verification failed. Please try again later.",
    }, 500);
  }
});

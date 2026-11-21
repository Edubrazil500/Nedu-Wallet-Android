import { serve } from "https://deno.land/std@0.168.0/http/server.ts";
serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: { "Access-Control-Allow-Origin": "*", "Access-Control-Allow-Headers": "authorization, apikey, content-type, x-client-info" } });
  return new Response(JSON.stringify({ error: "This legacy endpoint has been retired. Use the authenticated create-virtual-account endpoint." }), {
    status: 410,
    headers: { "Content-Type": "application/json", "Cache-Control": "no-store", "Access-Control-Allow-Origin": "*" },
  });
});
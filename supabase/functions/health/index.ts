Deno.serve(() => {
  return Response.json({
    status: "ok",
    service: "nedu-wallet-supabase",
    timestamp: new Date().toISOString(),
  });
});

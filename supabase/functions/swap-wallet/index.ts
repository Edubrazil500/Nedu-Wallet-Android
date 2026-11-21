import { serve } from "https://deno.land/std@0.168.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";
const h={"Content-Type":"application/json","Cache-Control":"no-store","Access-Control-Allow-Origin":"*","Access-Control-Allow-Headers":"authorization, apikey, content-type, x-client-info","Access-Control-Allow-Methods":"POST, OPTIONS"};
const out=(d:unknown,s=200)=>new Response(JSON.stringify(d),{status:s,headers:h});
serve(async req=>{
 if(req.method==="OPTIONS") return new Response("ok",{headers:h});
 if(req.method!=="POST") return out({error:"Method not allowed"},405);
 const authorization=req.headers.get("Authorization")??"";
 if(!authorization.startsWith("Bearer ")) return out({error:"Authentication required"},401);
 try{
  const url=Deno.env.get("SUPABASE_URL")!,anon=Deno.env.get("SUPABASE_ANON_KEY")!,service=Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
  const userClient=createClient(url,anon,{global:{headers:{Authorization:authorization}},auth:{persistSession:false}});
  const {data,error}=await userClient.auth.getUser();
  if(error||!data.user) return out({error:"Invalid session"},401);
  const body=await req.json();
  const from=String(body?.from_code??"").toUpperCase(),to=String(body?.to_code??"").toUpperCase(),amount=Number(body?.amount);
  const allowed=["NGN","USD","EUR","GBP","CAD","USDT","USDC"];
  if(!allowed.includes(from)||!allowed.includes(to)||from===to||!Number.isFinite(amount)||amount<=0||amount>1000000000) return out({error:"Invalid swap request."},400);
  const admin=createClient(url,service,{auth:{persistSession:false,autoRefreshToken:false}});
  const {data:rates,error:rateError}=await admin.from("rates").select("code,usd_rate,updated_at").in("code",[from,to]);
  if(rateError||!rates||rates.length!==2) return out({error:"Exchange rates are unavailable."},503);
  const stale=rates.some((r:any)=>!r.updated_at||Date.now()-new Date(r.updated_at).getTime()>15*60*1000||Number(r.usd_rate)<=0);
  if(stale) return out({error:"Exchange rates are stale. No money was moved; please try again when rates refresh."},503);
  const {data:converted,error:swapError}=await admin.rpc("swap_money",{p_user_id:data.user.id,p_from_code:from,p_to_code:to,p_amount:amount});
  if(swapError) return out({error:swapError.message.includes("Insufficient")?"Insufficient wallet balance.":swapError.message.includes("stale")?"Exchange rates are stale. No money was moved.":"Swap could not be completed."},400);
  return out({ok:true,from_code:from,to_code:to,amount,converted_amount:converted,status:"completed"});
 }catch(_){return out({error:"Swap could not be completed."},500);}
});
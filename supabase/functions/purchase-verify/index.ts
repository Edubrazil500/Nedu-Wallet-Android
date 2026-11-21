import { serve } from "https://deno.land/std@0.168.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";
const headers={"Content-Type":"application/json","Cache-Control":"no-store","Access-Control-Allow-Origin":"*","Access-Control-Allow-Headers":"authorization, apikey, content-type, x-client-info","Access-Control-Allow-Methods":"POST, OPTIONS"};
const reply=(d:unknown,s=200)=>new Response(JSON.stringify(d),{status:s,headers});
serve(async(req)=>{
 if(req.method==="OPTIONS")return new Response("ok",{headers});
 if(req.method!=="POST")return reply({error:"Method not allowed"},405);
 try{
  const url=Deno.env.get("SUPABASE_URL")!,anon=Deno.env.get("SUPABASE_ANON_KEY")!,service=Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!,secret=Deno.env.get("FLUTTERWAVE_SECRET_KEY")??"",authorization=req.headers.get("Authorization")??"";
  if(!authorization.startsWith("Bearer "))return reply({error:"Authentication required"},401);
  const uc=createClient(url,anon,{global:{headers:{Authorization:authorization}},auth:{persistSession:false,autoRefreshToken:false}}),{data:auth,error:ae}=await uc.auth.getUser();
  if(ae||!auth.user)return reply({error:"Unauthorized"},401);
  const {purchase_id}=await req.json();if(typeof purchase_id!=="string")return reply({error:"A purchase ID is required."},400);
  const admin=createClient(url,service,{auth:{persistSession:false,autoRefreshToken:false}});
  const {data:p,error}=await admin.from("purchase_transactions").select("*").eq("id",purchase_id).eq("user_id",auth.user.id).maybeSingle();
  if(error||!p)return reply({error:"Purchase not found."},404);
  if(p.type==="bills"&&p.wallet_debit_status==="reserved"){
   if(!secret.startsWith("FLWSECK-")||secret.startsWith("FLWSECK_TEST-"))return reply({error:"Provider status unavailable. Funds remain reserved."},503);
   const ref=String(p.provider_reference??"");if(!/^NEDU-BILL-[A-F0-9]{32}$/.test(ref))return reply({error:"Purchase reference needs manual review. Funds remain reserved."},409);
   const response=await fetch("https://api.flutterwave.com/v3/bills/"+encodeURIComponent(ref)+"?verbose=1",{headers:{Authorization:"Bearer "+secret,Accept:"application/json"},signal:AbortSignal.timeout(18000)}),result=await response.json().catch(()=>({}));
   if(!response.ok||result?.status!=="success"||!result?.data)return reply({purchase:p,pending_reconciliation:true,message:"Flutterwave has not confirmed a final result. Funds remain reserved."},202);
   const d=result.data,ps=String(d.status??d.transaction_status??d.bill_status??"").toLowerCase(),s=["successful","success","completed"].includes(ps)?"successful":["failed","reversed","cancelled"].includes(ps)?"failed":"pending";
   const {error:se}=await admin.rpc("settle_wallet_purchase",{p_purchase_id:p.id,p_user_id:auth.user.id,p_provider_status:s,p_provider_reference:String(d.flw_ref??""),p_delivery_code:String(d.extra??d.token??""),p_provider_payload:{flw_ref:d.flw_ref??null,provider_status:d.status??null,status_checked_at:new Date().toISOString()}});
   if(se)return reply({error:"Settlement update failed safely. Funds remain reserved for review."},500);
   const {data:latest}=await admin.from("purchase_transactions").select("*").eq("id",p.id).single();
   return reply({purchase:latest,pending_reconciliation:s==="pending"},s==="pending"?202:200);
  }
  let extra=null;
  if(p.type==="esim"){const {data}=await admin.from("esim_orders").select("*").eq("purchase_id",purchase_id).maybeSingle();extra=data;}
  if(p.type==="gift_cards"){const {data}=await admin.from("gift_card_orders").select("*").eq("purchase_id",purchase_id).maybeSingle();extra=data;}
  return reply({purchase:p,extra});
 }catch(_){return reply({error:"Purchase status could not be checked. Please retry."},500);}
});
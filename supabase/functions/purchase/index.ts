import { serve } from "https://deno.land/std@0.168.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";
const headers = {"Content-Type":"application/json","Cache-Control":"no-store","Access-Control-Allow-Origin":"*","Access-Control-Allow-Headers":"authorization, apikey, content-type, x-client-info","Access-Control-Allow-Methods":"POST, OPTIONS"};
const reply=(body:unknown,status=200)=>new Response(JSON.stringify(body),{status,headers});
function code(v:unknown,re:RegExp){const s=String(v??"").trim();if(!re.test(s))throw new Error("Invalid bill provider code.");return s;}
async function flw(path:string,secret:string,init:RequestInit={}){
 const res=await fetch("https://api.flutterwave.com/v3"+path,{...init,headers:{Authorization:"Bearer "+secret,Accept:"application/json","Content-Type":"application/json",...(init.headers||{})},signal:AbortSignal.timeout(18000)});
 const body=await res.json().catch(()=>({}));
 if(!res.ok||body?.status!=="success"){const e=new Error(String(body?.message||"Flutterwave bill service is temporarily unavailable."));(e as any).httpStatus=res.status;throw e;}
 return body;
}
function finalStatus(d:any):"successful"|"failed"|"pending"{const s=String(d?.status??d?.transaction_status??d?.bill_status??"").toLowerCase();if(["successful","success","completed"].includes(s))return"successful";if(["failed","reversed","cancelled"].includes(s))return"failed";return"pending";}
serve(async(req)=>{
 if(req.method==="OPTIONS")return new Response("ok",{headers});
 if(req.method!=="POST")return reply({error:"Method not allowed"},405);
 try{
  const url=Deno.env.get("SUPABASE_URL"),anon=Deno.env.get("SUPABASE_ANON_KEY"),service=Deno.env.get("SUPABASE_SERVICE_ROLE_KEY"),secret=Deno.env.get("FLUTTERWAVE_SECRET_KEY")??"";
  if(!url||!anon||!service)return reply({error:"Server configuration is incomplete."},503);
  const authorization=req.headers.get("Authorization")??"";
  if(!authorization.startsWith("Bearer "))return reply({error:"Authentication required."},401);
  const userClient=createClient(url,anon,{global:{headers:{Authorization:authorization}},auth:{persistSession:false,autoRefreshToken:false}});
  const {data:auth,error:authError}=await userClient.auth.getUser();
  if(authError||!auth.user)return reply({error:"Invalid session."},401);
  if(!secret.startsWith("FLWSECK-")||secret.startsWith("FLWSECK_TEST-"))return reply({error:"Flutterwave Live bill services are not configured on the server."},503);
  const body=await req.json(),action=String(body?.action??"").toLowerCase();
  const admin=createClient(url,service,{auth:{persistSession:false,autoRefreshToken:false}});
  if(action==="categories"){const r=await flw("/top-bill-categories?country=NG",secret);return reply({categories:r.data??[]});}
  if(action==="billers"){const c=code(body?.category_code,/^[A-Z0-9_]{2,32}$/);const r=await flw("/bills/"+encodeURIComponent(c)+"/billers?country=NG",secret);return reply({billers:r.data??[]});}
  if(action==="items"){const b=code(body?.biller_code,/^BIL\d{2,6}$/);const r=await flw("/billers/"+encodeURIComponent(b)+"/items",secret);return reply({items:r.data??[]});}
  const {data:profile,error:pe}=await admin.from("profiles").select("kyc_status,kyc_bvn_verified_at,kyc_nin_verified_at").eq("id",auth.user.id).maybeSingle();
  if(pe||!profile)return reply({error:"Wallet profile not found."},404);
  if(profile.kyc_status!=="Verified"||!profile.kyc_bvn_verified_at||!profile.kyc_nin_verified_at)return reply({error:"Complete both BVN and NIN verification before paying bills.",code:"KYC_REQUIRED"},403);
  if(action==="validate"){
   const item=code(body?.item_code,/^[A-Z0-9_-]{2,32}$/),customer=String(body?.customer_id??"").trim();
   if(customer.length<5||customer.length>40||!/^[A-Za-z0-9+_-]+$/.test(customer))return reply({error:"Enter a valid customer or account number."},400);
   const r=await flw("/bill-items/"+encodeURIComponent(item)+"/validate?customer="+encodeURIComponent(customer),secret);
   if(r.data?.response_code&&String(r.data.response_code)!=="00")return reply({valid:false,message:String(r.data.response_message||"Customer details could not be validated.")},422);
   return reply({valid:true,customer:r.data??{}});
  }
  if(action!=="buy")return reply({error:"Unsupported bill action."},400);
  if(Deno.env.get("FLW_ENVIRONMENT")!=="live"||Deno.env.get("FLW_LIVE_ENABLED")!=="true"||Deno.env.get("FLW_BILLS_LIVE_ENABLED")!=="true"||Deno.env.get("FLW_BILLS_IP_WHITELIST_CONFIRMED")!=="true")
   return reply({error:"Bill purchases are not enabled. Complete Flutterwave bill-payment activation and IP allowlisting first.",disabled:true,no_money_moved:true},503);
  const key=String(body?.idempotency_key??"").trim();
  if(!/^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(key))return reply({error:"A valid unique purchase request ID is required."},400);
  const category=code(body?.category_code,/^[A-Z0-9_]{2,32}$/),biller=code(body?.biller_code,/^BIL\d{2,6}$/),itemCode=code(body?.item_code,/^[A-Z0-9_-]{2,32}$/),customer=String(body?.customer_id??"").trim(),amount=Number(body?.amount);
  if(customer.length<5||customer.length>40||!/^[A-Za-z0-9+_-]+$/.test(customer)||!Number.isFinite(amount)||amount<=0||amount>10000000)return reply({error:"Enter a valid customer number and bill amount."},400);
  const itemList=await flw("/billers/"+encodeURIComponent(biller)+"/items",secret),item=(itemList.data??[]).find((x:any)=>String(x.item_code)===itemCode&&String(x.biller_code)===biller);
  if(!item)return reply({error:"This bill package is no longer available. Refresh the bill list."},409);
  if(Number(item.amount)<=0||Math.abs(Number(item.amount)-amount)>0.01)return reply({error:"The amount does not match the current provider price. Refresh and try again."},409);
  if(String(item.country??"NG").toUpperCase()!=="NG")return reply({error:"Only Nigerian billers are supported."},400);
  if(item.is_resolvable===true&&item.is_airtime!==true){
   const v=await flw("/bill-items/"+encodeURIComponent(itemCode)+"/validate?customer="+encodeURIComponent(customer),secret);
   if(v.data?.response_code&&String(v.data.response_code)!=="00")return reply({error:String(v.data.response_message||"Customer details could not be validated.")},422);
  }
  const txRef="NEDU-BILL-"+crypto.randomUUID().replace(/-/g,"").toUpperCase();
  const {data:purchase,error:insertError}=await admin.from("purchase_transactions").insert({user_id:auth.user.id,type:"bills",category,provider:"Flutterwave",provider_code:biller,account_number:customer,amount:Number(amount.toFixed(2)),currency:"NGN",pay_from_wallet:"NGN",status:"pending",provider_reference:txRef,idempotency_key:key,metadata:{item_code:itemCode,biller_name:String(item.biller_name||item.name||"")}}).select("*").single();
  if(insertError){
   if(insertError.code==="23505"){const {data:existing}=await admin.from("purchase_transactions").select("*").eq("user_id",auth.user.id).eq("idempotency_key",key).maybeSingle();if(existing)return reply({purchase:existing,duplicate_request:true});}
   return reply({error:"We could not create the bill request. No wallet debit was made."},409);
  }
  const {error:reserveError}=await admin.rpc("reserve_wallet_purchase",{p_purchase_id:purchase.id,p_user_id:auth.user.id,p_wallet_code:"NGN",p_amount:amount});
  if(reserveError){await admin.from("purchase_transactions").update({status:"failed",error_message:reserveError.message,updated_at:new Date().toISOString()}).eq("id",purchase.id);return reply({error:reserveError.message.includes("Insufficient")?"Insufficient NGN wallet balance.":"Bill purchase could not be reserved. No provider payment was sent."},409);}
  try{
   await flw("/billers/"+encodeURIComponent(biller)+"/items/"+encodeURIComponent(itemCode)+"/payment",secret,{method:"POST",body:JSON.stringify({country:"NG",customer_id:customer,amount,reference:txRef})});
   const statusResult=await flw("/bills/"+encodeURIComponent(txRef)+"?verbose=1",secret),d=statusResult.data??{},s=finalStatus(d);
   const settlement=await admin.rpc("settle_wallet_purchase",{p_purchase_id:purchase.id,p_user_id:auth.user.id,p_provider_status:s,p_provider_reference:String(d.flw_ref??""),p_delivery_code:String(d.extra??d.token??""),p_provider_payload:{flw_ref:d.flw_ref??null,provider_status:d.status??null,item_code:itemCode}});
   const {data:latest}=await admin.from("purchase_transactions").select("*").eq("id",purchase.id).single();
   return reply({purchase:latest,settlement:settlement.data,provider_status:s,pending_reconciliation:s==="pending"},s==="pending"?202:200);
  }catch(_){
   await admin.rpc("settle_wallet_purchase",{p_purchase_id:purchase.id,p_user_id:auth.user.id,p_provider_status:"pending",p_provider_reference:null,p_delivery_code:null,p_provider_payload:{state:"provider_result_unknown",item_code:itemCode}});
   const {data:latest}=await admin.from("purchase_transactions").select("*").eq("id",purchase.id).single();
   return reply({purchase:latest,pending_reconciliation:true,message:"Provider result is still being confirmed. Your funds remain reserved; do not pay again."},202);
  }
 }catch(e){const s=Number((e as any)?.httpStatus)===400?400:502;return reply({error:e instanceof Error?e.message:"Bill service is temporarily unavailable."},s);}
});
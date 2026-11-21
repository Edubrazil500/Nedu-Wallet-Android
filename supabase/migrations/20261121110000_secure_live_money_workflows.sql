-- Security hardening and provider-backed funding workflows for the existing Nedu Wallet schema.
-- Additive only: does not replace existing customer or wallet tables.

ALTER FUNCTION public.set_updated_at() SET search_path = pg_catalog, public;
REVOKE EXECUTE ON FUNCTION public.set_updated_at() FROM PUBLIC, anon, authenticated;

DO $$
DECLARE r record;
BEGIN
  FOR r IN SELECT schemaname, tablename FROM pg_tables WHERE schemaname='public'
  LOOP
    EXECUTE format('REVOKE TRUNCATE, REFERENCES, TRIGGER ON TABLE %I.%I FROM PUBLIC, anon, authenticated',r.schemaname,r.tablename);
  END LOOP;
END $$;

CREATE TABLE IF NOT EXISTS public.flutterwave_funding_events (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  provider_transaction_id text NOT NULL UNIQUE,
  provider_reference text,
  user_id uuid NOT NULL REFERENCES auth.users(id),
  currency text NOT NULL CHECK(currency='NGN'),
  amount numeric(20,2) NOT NULL CHECK(amount>0),
  payload jsonb NOT NULL DEFAULT '{}'::jsonb,
  created_at timestamptz NOT NULL DEFAULT now()
);
ALTER TABLE public.flutterwave_funding_events ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.flutterwave_funding_events FROM PUBLIC, anon, authenticated;
GRANT SELECT, INSERT ON TABLE public.flutterwave_funding_events TO service_role;
DROP POLICY IF EXISTS service_role_manage_flutterwave_funding_events ON public.flutterwave_funding_events;
CREATE POLICY service_role_manage_flutterwave_funding_events ON public.flutterwave_funding_events
FOR ALL TO service_role USING(true) WITH CHECK(true);

CREATE OR REPLACE FUNCTION public.credit_verified_flutterwave_funding(
 p_user_id uuid,p_transaction_id text,p_tx_ref text,p_amount numeric,p_currency text,p_payload jsonb DEFAULT '{}'::jsonb
) RETURNS boolean LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,public AS $$
DECLARE v_event_id uuid;
BEGIN
 IF p_user_id IS NULL OR p_transaction_id IS NULL OR length(btrim(p_transaction_id))=0
    OR p_amount IS NULL OR p_amount<=0 OR upper(coalesce(p_currency,''))<>'NGN' THEN
   RAISE EXCEPTION 'Invalid verified funding details';
 END IF;
 INSERT INTO public.flutterwave_funding_events(provider_transaction_id,provider_reference,user_id,currency,amount,payload)
 VALUES(btrim(p_transaction_id),nullif(btrim(p_tx_ref),''),p_user_id,'NGN',round(p_amount,2),coalesce(p_payload,'{}'::jsonb))
 ON CONFLICT(provider_transaction_id) DO NOTHING RETURNING id INTO v_event_id;
 IF v_event_id IS NULL THEN RETURN false; END IF;
 UPDATE public.wallets SET balance=balance+round(p_amount,2),updated_at=now()
 WHERE user_id=p_user_id AND code='NGN';
 IF NOT FOUND THEN RAISE EXCEPTION 'NGN wallet not found for user'; END IF;
 INSERT INTO public.transactions(user_id,type,from_code,to_code,amount,converted_amount,to_name,to_bank,to_country,to_address,status)
 VALUES(p_user_id,'add','NGN','NGN',round(p_amount,2),round(p_amount,2),'Flutterwave Funding','Flutterwave','Nigeria','FLW:'||btrim(p_transaction_id),'completed');
 RETURN true;
END; $$;
REVOKE ALL ON FUNCTION public.credit_verified_flutterwave_funding(uuid,text,text,numeric,text,jsonb) FROM PUBLIC,anon,authenticated;
GRANT EXECUTE ON FUNCTION public.credit_verified_flutterwave_funding(uuid,text,text,numeric,text,jsonb) TO service_role;

CREATE OR REPLACE FUNCTION public.swap_money(p_user_id uuid,p_from_code text,p_to_code text,p_amount numeric)
RETURNS numeric LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,public AS $$
DECLARE v_from_balance numeric; v_from_rate numeric; v_to_rate numeric; v_from_updated timestamptz; v_to_updated timestamptz; v_converted numeric;
BEGIN
 IF p_user_id IS NULL OR p_amount IS NULL OR p_amount<=0 OR p_amount>1000000000 THEN RAISE EXCEPTION 'Invalid amount or user'; END IF;
 IF p_from_code IS NULL OR p_to_code IS NULL OR p_from_code=p_to_code THEN RAISE EXCEPTION 'Invalid currency pair'; END IF;
 IF p_from_code NOT IN('NGN','USD','EUR','GBP','CAD','USDT','USDC') OR p_to_code NOT IN('NGN','USD','EUR','GBP','CAD','USDT','USDC') THEN RAISE EXCEPTION 'Unsupported currency'; END IF;
 PERFORM 1 FROM public.wallets WHERE user_id=p_user_id AND code IN(p_from_code,p_to_code) ORDER BY code FOR UPDATE;
 IF (SELECT count(*) FROM public.wallets WHERE user_id=p_user_id AND code IN(p_from_code,p_to_code))<>2 THEN RAISE EXCEPTION 'Wallet not found'; END IF;
 SELECT balance INTO v_from_balance FROM public.wallets WHERE user_id=p_user_id AND code=p_from_code;
 IF v_from_balance<p_amount THEN RAISE EXCEPTION 'Insufficient balance'; END IF;
 SELECT usd_rate,updated_at INTO v_from_rate,v_from_updated FROM public.rates WHERE code=p_from_code;
 SELECT usd_rate,updated_at INTO v_to_rate,v_to_updated FROM public.rates WHERE code=p_to_code;
 IF v_from_rate IS NULL OR v_to_rate IS NULL OR v_from_rate<=0 OR v_to_rate<=0 OR v_from_updated<now()-interval '15 minutes' OR v_to_updated<now()-interval '15 minutes' THEN
  RAISE EXCEPTION 'Exchange rates are unavailable or stale; no funds were moved';
 END IF;
 v_converted:=round((p_amount*v_from_rate/v_to_rate)*0.995,2);
 IF v_converted<=0 THEN RAISE EXCEPTION 'Converted amount is too small'; END IF;
 UPDATE public.wallets SET balance=balance-p_amount,updated_at=now() WHERE user_id=p_user_id AND code=p_from_code;
 UPDATE public.wallets SET balance=balance+v_converted,updated_at=now() WHERE user_id=p_user_id AND code=p_to_code;
 INSERT INTO public.transactions(user_id,type,from_code,to_code,amount,converted_amount,status) VALUES(p_user_id,'swap',p_from_code,p_to_code,p_amount,v_converted,'completed');
 RETURN v_converted;
END; $$;
REVOKE ALL ON FUNCTION public.swap_money(uuid,text,text,numeric) FROM PUBLIC,anon,authenticated;
GRANT EXECUTE ON FUNCTION public.swap_money(uuid,text,text,numeric) TO service_role;

CREATE TABLE IF NOT EXISTS public.wallet_transfer_requests(
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),user_id uuid NOT NULL REFERENCES auth.users(id),
 currency text NOT NULL CHECK(currency IN('NGN','USD','EUR','GBP','CAD')),
 amount numeric(20,2) NOT NULL CHECK(amount>0),recipient_name text NOT NULL,bank_name text NOT NULL,
 bank_code text NOT NULL,account_number text NOT NULL,country text NOT NULL DEFAULT 'Nigeria',
 status text NOT NULL DEFAULT 'pending_review' CHECK(status IN('pending_review','approved','processing','completed','failed','cancelled')),
 created_at timestamptz NOT NULL DEFAULT now()
);
ALTER TABLE public.wallet_transfer_requests ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.wallet_transfer_requests FROM PUBLIC,anon,authenticated;
GRANT SELECT ON TABLE public.wallet_transfer_requests TO authenticated;
GRANT SELECT,INSERT,UPDATE ON TABLE public.wallet_transfer_requests TO service_role;
DROP POLICY IF EXISTS users_read_own_transfer_requests ON public.wallet_transfer_requests;
CREATE POLICY users_read_own_transfer_requests ON public.wallet_transfer_requests
FOR SELECT TO authenticated USING(auth.uid()=user_id);

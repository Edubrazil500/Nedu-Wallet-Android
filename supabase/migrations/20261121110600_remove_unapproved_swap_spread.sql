-- Remove the unapproved fixed spread from swaps. The backend applies only fresh stored rates.
CREATE OR REPLACE FUNCTION public.swap_money(
  p_user_id uuid, p_from_code text, p_to_code text, p_amount numeric
) RETURNS numeric
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
  v_from_balance numeric;
  v_from_rate numeric;
  v_to_rate numeric;
  v_from_updated timestamptz;
  v_to_updated timestamptz;
  v_converted numeric;
BEGIN
  IF p_user_id IS NULL OR p_amount IS NULL OR p_amount <= 0 OR p_amount > 1000000000 THEN
    RAISE EXCEPTION 'Invalid amount or user';
  END IF;
  IF p_from_code IS NULL OR p_to_code IS NULL OR p_from_code = p_to_code THEN
    RAISE EXCEPTION 'Invalid currency pair';
  END IF;
  IF p_from_code NOT IN ('NGN','USD','EUR','GBP','CAD','USDT','USDC')
     OR p_to_code NOT IN ('NGN','USD','EUR','GBP','CAD','USDT','USDC') THEN
    RAISE EXCEPTION 'Unsupported currency';
  END IF;
  PERFORM 1 FROM public.wallets
   WHERE user_id=p_user_id AND code IN (p_from_code,p_to_code)
   ORDER BY code FOR UPDATE;
  IF (SELECT count(*) FROM public.wallets WHERE user_id=p_user_id AND code IN (p_from_code,p_to_code)) <> 2 THEN
    RAISE EXCEPTION 'Wallet not found';
  END IF;
  SELECT balance INTO v_from_balance FROM public.wallets WHERE user_id=p_user_id AND code=p_from_code;
  IF v_from_balance < p_amount THEN RAISE EXCEPTION 'Insufficient balance'; END IF;
  SELECT usd_rate,updated_at INTO v_from_rate,v_from_updated FROM public.rates WHERE code=p_from_code;
  SELECT usd_rate,updated_at INTO v_to_rate,v_to_updated FROM public.rates WHERE code=p_to_code;
  IF v_from_rate IS NULL OR v_to_rate IS NULL OR v_from_rate<=0 OR v_to_rate<=0
     OR v_from_updated < now()-interval '15 minutes' OR v_to_updated < now()-interval '15 minutes' THEN
    RAISE EXCEPTION 'Exchange rates are unavailable or stale; no funds were moved';
  END IF;
  v_converted := round((p_amount * v_from_rate / v_to_rate), 2);
  IF v_converted <= 0 THEN RAISE EXCEPTION 'Converted amount is too small'; END IF;
  UPDATE public.wallets SET balance=balance-p_amount,updated_at=now()
   WHERE user_id=p_user_id AND code=p_from_code;
  UPDATE public.wallets SET balance=balance+v_converted,updated_at=now()
   WHERE user_id=p_user_id AND code=p_to_code;
  INSERT INTO public.transactions(user_id,type,from_code,to_code,amount,converted_amount,status)
  VALUES(p_user_id,'swap',p_from_code,p_to_code,p_amount,v_converted,'completed');
  RETURN v_converted;
END;
$$;
REVOKE ALL ON FUNCTION public.swap_money(uuid,text,text,numeric) FROM PUBLIC,anon,authenticated;
GRANT EXECUTE ON FUNCTION public.swap_money(uuid,text,text,numeric) TO service_role;

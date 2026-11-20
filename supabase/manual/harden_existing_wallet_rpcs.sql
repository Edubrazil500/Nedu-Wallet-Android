-- Security hardening for the existing iOS Supabase schema.
-- Additive only: preserves the existing tables and function signatures.
-- Review and apply to the existing project only after testing against a safe clone.

CREATE OR REPLACE FUNCTION public.add_money_to_wallet(
  p_user_id uuid,
  p_code text,
  p_amount numeric,
  p_tx_ref text,
  p_description text
)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $function$
BEGIN
  IF p_amount IS NULL OR p_amount <= 0 THEN
    RAISE EXCEPTION 'Amount must be greater than zero';
  END IF;
  IF p_user_id IS NULL OR p_code IS NULL OR p_tx_ref IS NULL OR length(trim(p_tx_ref)) = 0 THEN
    RAISE EXCEPTION 'Missing required funding details';
  END IF;

  -- Preserve the existing idempotency behavior; a unique provider reference
  -- should be added only after checking existing production data for duplicates.
  IF EXISTS (
    SELECT 1 FROM public.transactions WHERE to_address = p_tx_ref
  ) THEN
    RETURN;
  END IF;

  UPDATE public.wallets
  SET balance = balance + p_amount, updated_at = pg_catalog.now()
  WHERE user_id = p_user_id AND code = p_code;

  IF NOT FOUND THEN
    RAISE EXCEPTION 'Wallet not found';
  END IF;

  INSERT INTO public.transactions (
    user_id, type, from_code, to_code, amount, converted_amount,
    to_name, to_bank, to_country, to_address, status
  )
  VALUES (
    p_user_id, 'add', p_code, p_code, p_amount, p_amount,
    'Flutterwave Funding', 'Flutterwave', 'Nigeria', p_tx_ref, 'completed'
  );
END;
$function$;

CREATE OR REPLACE FUNCTION public.create_default_wallets(p_user_id uuid)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, auth
AS $function$
BEGIN
  IF p_user_id IS NULL THEN
    RAISE EXCEPTION 'User ID is required';
  END IF;
  IF auth.uid() IS NOT NULL AND auth.uid() <> p_user_id THEN
    RAISE EXCEPTION 'Not allowed to create wallets for another user';
  END IF;

  INSERT INTO public.wallets (user_id, code, name, is_crypto)
  VALUES
    (p_user_id, 'NGN', 'Naira', false),
    (p_user_id, 'USD', 'US Dollar', false),
    (p_user_id, 'EUR', 'Euro', false),
    (p_user_id, 'GBP', 'British Pound', false),
    (p_user_id, 'CAD', 'Canadian Dollar', false),
    (p_user_id, 'USDT', 'Tether', true),
    (p_user_id, 'USDC', 'USD Coin', true)
  ON CONFLICT (user_id, code) DO NOTHING;
END;
$function$;

CREATE OR REPLACE FUNCTION public.send_money(
  p_user_id uuid,
  p_from_code text,
  p_amount numeric,
  p_to_type text,
  p_to_name text,
  p_to_bank text,
  p_to_country text,
  p_to_address text
)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, auth
AS $function$
DECLARE
  v_balance numeric;
BEGIN
  IF auth.uid() IS NOT NULL AND auth.uid() <> p_user_id THEN
    RAISE EXCEPTION 'Not allowed to transfer from another user account';
  END IF;
  IF p_user_id IS NULL OR p_from_code IS NULL OR p_amount IS NULL OR p_amount <= 0 THEN
    RAISE EXCEPTION 'Invalid transfer details';
  END IF;

  SELECT balance INTO v_balance
  FROM public.wallets
  WHERE user_id = p_user_id AND code = p_from_code
  FOR UPDATE;

  IF v_balance IS NULL OR v_balance < p_amount THEN
    RAISE EXCEPTION 'Not enough balance';
  END IF;

  UPDATE public.wallets
  SET balance = balance - p_amount, updated_at = pg_catalog.now()
  WHERE user_id = p_user_id AND code = p_from_code;

  INSERT INTO public.transactions (
    user_id, type, from_code, to_code, amount, converted_amount,
    to_name, to_bank, to_country, to_address, status
  )
  VALUES (
    p_user_id,
    CASE WHEN p_to_type = 'bank' THEN 'transfer' ELSE 'send' END,
    p_from_code, p_from_code, p_amount, p_amount,
    p_to_name, p_to_bank, p_to_country, p_to_address,
    CASE WHEN p_to_type = 'bank' THEN 'pending' ELSE 'completed' END
  );
END;
$function$;

CREATE OR REPLACE FUNCTION public.swap_money(
  p_user_id uuid,
  p_from_code text,
  p_to_code text,
  p_amount numeric
)
RETURNS numeric
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, auth
AS $function$
DECLARE
  v_from_bal numeric;
  v_rate_from numeric;
  v_rate_to numeric;
  v_converted numeric;
BEGIN
  IF auth.uid() IS NOT NULL AND auth.uid() <> p_user_id THEN
    RAISE EXCEPTION 'Not allowed to swap another user account';
  END IF;
  IF p_user_id IS NULL OR p_from_code IS NULL OR p_to_code IS NULL
     OR p_from_code = p_to_code OR p_amount IS NULL OR p_amount <= 0 THEN
    RAISE EXCEPTION 'Invalid currency swap details';
  END IF;

  -- Lock both wallet rows in a stable order to reduce deadlock risk.
  PERFORM 1
  FROM public.wallets
  WHERE user_id = p_user_id AND code IN (p_from_code, p_to_code)
  ORDER BY code
  FOR UPDATE;

  SELECT balance INTO v_from_bal
  FROM public.wallets
  WHERE user_id = p_user_id AND code = p_from_code;

  IF v_from_bal IS NULL OR v_from_bal < p_amount THEN
    RAISE EXCEPTION 'Not enough balance';
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM public.wallets
    WHERE user_id = p_user_id AND code = p_to_code
  ) THEN
    RAISE EXCEPTION 'Destination wallet not found';
  END IF;

  SELECT usd_rate INTO v_rate_from
  FROM public.rates WHERE code = p_from_code;
  SELECT usd_rate INTO v_rate_to
  FROM public.rates WHERE code = p_to_code;

  IF v_rate_from IS NULL OR v_rate_from <= 0
     OR v_rate_to IS NULL OR v_rate_to <= 0 THEN
    RAISE EXCEPTION 'Exchange rate unavailable';
  END IF;

  v_converted := p_amount * v_rate_from / v_rate_to * 0.995;

  UPDATE public.wallets
  SET balance = balance - p_amount, updated_at = pg_catalog.now()
  WHERE user_id = p_user_id AND code = p_from_code;

  UPDATE public.wallets
  SET balance = balance + v_converted, updated_at = pg_catalog.now()
  WHERE user_id = p_user_id AND code = p_to_code;

  INSERT INTO public.transactions (
    user_id, type, from_code, to_code, amount, converted_amount, status
  )
  VALUES (
    p_user_id, 'swap', p_from_code, p_to_code,
    p_amount, v_converted, 'completed'
  );

  RETURN v_converted;
END;
$function$;

-- Never expose privileged RPCs to anonymous clients.
REVOKE ALL ON FUNCTION public.add_money_to_wallet(uuid, text, numeric, text, text)
  FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.add_money_to_wallet(uuid, text, numeric, text, text)
  TO service_role;

REVOKE ALL ON FUNCTION public.create_default_wallets(uuid)
  FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.create_default_wallets(uuid)
  TO authenticated, service_role;

REVOKE ALL ON FUNCTION public.send_money(uuid, text, numeric, text, text, text, text, text)
  FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.send_money(uuid, text, numeric, text, text, text, text, text)
  TO authenticated, service_role;

REVOKE ALL ON FUNCTION public.swap_money(uuid, text, text, numeric)
  FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.swap_money(uuid, text, text, numeric)
  TO authenticated, service_role;

alter table public.purchase_transactions
  add column if not exists wallet_transaction_id uuid
  references public.transactions(id);

create or replace function public.reserve_wallet_purchase(
  p_purchase_id uuid,
  p_user_id uuid,
  p_wallet_code text,
  p_amount numeric
) returns boolean
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  v_purchase public.purchase_transactions%rowtype;
  v_profile public.profiles%rowtype;
  v_wallet public.wallets%rowtype;
  v_amount numeric(18,2);
  v_transaction_id uuid;
begin
  if p_purchase_id is null or p_user_id is null or upper(coalesce(p_wallet_code,'')) <> 'NGN'
     or p_amount is null or p_amount <= 0 or p_amount > 10000000 then
    raise exception 'Invalid purchase reservation';
  end if;
  v_amount := round(p_amount, 2);

  select * into v_purchase
    from public.purchase_transactions
   where id = p_purchase_id and user_id = p_user_id
   for update;
  if not found then raise exception 'Purchase not found'; end if;
  if v_purchase.wallet_debit_status = 'reserved' then return true; end if;
  if v_purchase.wallet_debit_status <> 'not_reserved' or v_purchase.status <> 'pending'
     or round(v_purchase.amount,2) <> v_amount
     or upper(v_purchase.pay_from_wallet) <> 'NGN' then
    raise exception 'Purchase is not eligible for reservation';
  end if;

  select * into v_profile from public.profiles where id = p_user_id;
  if not found or v_profile.kyc_status <> 'Verified'
     or v_profile.kyc_bvn_verified_at is null
     or v_profile.kyc_nin_verified_at is null then
    raise exception 'Complete BVN and NIN verification before paying bills';
  end if;

  select * into v_wallet from public.wallets
   where user_id = p_user_id and code = 'NGN'
   for update;
  if not found then raise exception 'NGN wallet not found'; end if;
  if coalesce(v_wallet.balance,0) < v_amount then
    raise exception 'Insufficient NGN balance';
  end if;

  update public.wallets
     set balance = coalesce(balance,0) - v_amount, updated_at = now()
   where id = v_wallet.id;

  insert into public.transactions (
    user_id, type, from_code, to_code, amount, converted_amount,
    to_name, to_bank, to_country, to_address, status
  ) values (
    p_user_id, 'bill_payment', 'NGN', coalesce(v_purchase.provider_code, 'BILL'),
    v_amount, v_amount,
    coalesce(v_purchase.metadata->>'biller_name', 'Bill Payment'),
    'Flutterwave', 'Nigeria',
    'BILL:' || coalesce(v_purchase.provider_reference, v_purchase.id::text),
    'pending'
  )
  returning id into v_transaction_id;

  update public.purchase_transactions
     set status = 'processing',
         wallet_debit_status = 'reserved',
         wallet_transaction_id = v_transaction_id,
         reserved_at = now(),
         updated_at = now()
   where id = p_purchase_id and user_id = p_user_id;

  return true;
end;
$$;

create or replace function public.settle_wallet_purchase(
  p_purchase_id uuid,
  p_user_id uuid,
  p_provider_status text,
  p_provider_reference text default null,
  p_delivery_code text default null,
  p_provider_payload jsonb default '{}'::jsonb
) returns text
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  v_purchase public.purchase_transactions%rowtype;
  v_status text := lower(coalesce(p_provider_status,''));
begin
  if p_purchase_id is null or p_user_id is null then
    raise exception 'Invalid purchase settlement';
  end if;

  select * into v_purchase
    from public.purchase_transactions
   where id = p_purchase_id and user_id = p_user_id
   for update;
  if not found then raise exception 'Purchase not found'; end if;

  if v_purchase.wallet_debit_status in ('settled','refunded') then
    return v_purchase.wallet_debit_status;
  end if;
  if v_purchase.wallet_debit_status <> 'reserved' then
    raise exception 'Purchase has no reserved wallet funds';
  end if;

  if v_status in ('success','successful','completed') then
    update public.purchase_transactions
       set status = 'success',
           wallet_debit_status = 'settled',
           provider_status = p_provider_status,
           provider_payload = coalesce(p_provider_payload,'{}'::jsonb),
           delivery_code = coalesce(p_delivery_code, delivery_code),
           completed_at = now(),
           settled_at = now(),
           updated_at = now()
     where id = p_purchase_id and user_id = p_user_id;

    update public.transactions
       set status = 'completed'
     where id = v_purchase.wallet_transaction_id and user_id = p_user_id;
    return 'settled';
  elsif v_status in ('failed','reversed','cancelled') then
    update public.wallets
       set balance = coalesce(balance,0) + v_purchase.amount,
           updated_at = now()
     where user_id = p_user_id and code = 'NGN';
    if not found then raise exception 'NGN wallet not found during refund'; end if;

    update public.purchase_transactions
       set status = 'failed',
           wallet_debit_status = 'refunded',
           provider_status = p_provider_status,
           provider_payload = coalesce(p_provider_payload,'{}'::jsonb),
           error_message = coalesce(error_message, 'Provider reported failed bill payment'),
           refunded_at = now(),
           updated_at = now()
     where id = p_purchase_id and user_id = p_user_id;

    update public.transactions
       set status = 'refunded'
     where id = v_purchase.wallet_transaction_id and user_id = p_user_id;
    return 'refunded';
  else
    update public.purchase_transactions
       set status = 'review',
           provider_status = coalesce(p_provider_status,'pending'),
           provider_payload = coalesce(p_provider_payload,'{}'::jsonb),
           updated_at = now()
     where id = p_purchase_id and user_id = p_user_id;
    return 'reserved';
  end if;
end;
$$;

revoke all on function public.reserve_wallet_purchase(uuid,uuid,text,numeric) from public, anon, authenticated;
revoke all on function public.settle_wallet_purchase(uuid,uuid,text,text,text,jsonb) from public, anon, authenticated;
grant execute on function public.reserve_wallet_purchase(uuid,uuid,text,numeric) to service_role;
grant execute on function public.settle_wallet_purchase(uuid,uuid,text,text,text,jsonb) to service_role;

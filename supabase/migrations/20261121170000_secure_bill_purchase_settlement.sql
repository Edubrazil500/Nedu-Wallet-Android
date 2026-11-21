create table if not exists public.purchase_transactions (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  type text not null check (type in ('bills', 'airtime', 'esim', 'gift_cards')),
  category text,
  provider text not null,
  provider_code text,
  phone_number text,
  account_number text,
  country text,
  brand text,
  plan text,
  amount numeric not null check (amount > 0),
  currency text not null default 'NGN',
  pay_from_wallet text not null,
  status text not null default 'pending'
    check (status in ('pending', 'processing', 'success', 'failed', 'refunded', 'review')),
  provider_reference text,
  provider_status text,
  delivery_code text,
  delivery_qr_url text,
  delivery_pin text,
  error_message text,
  metadata jsonb default '{}'::jsonb,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  completed_at timestamptz
);

alter table public.purchase_transactions
  add column if not exists idempotency_key text,
  add column if not exists wallet_debit_status text not null default 'not_reserved',
  add column if not exists reserved_at timestamptz,
  add column if not exists settled_at timestamptz,
  add column if not exists refunded_at timestamptz,
  add column if not exists provider_payload jsonb not null default '{}'::jsonb;

alter table public.purchase_transactions
  drop constraint if exists purchase_transactions_status_check;
alter table public.purchase_transactions
  add constraint purchase_transactions_status_check
  check (status = any (array['pending','processing','success','failed','refunded','review']::text[]));

create index if not exists idx_purchase_user
  on public.purchase_transactions(user_id);
create index if not exists idx_purchase_status
  on public.purchase_transactions(status);
create index if not exists idx_purchase_type
  on public.purchase_transactions(type);
create index if not exists idx_purchase_created
  on public.purchase_transactions(created_at desc);
create unique index if not exists purchase_transactions_user_idempotency_uidx
  on public.purchase_transactions(user_id, idempotency_key)
  where idempotency_key is not null;
create index if not exists purchase_transactions_pending_reconcile_idx
  on public.purchase_transactions(status, reserved_at)
  where wallet_debit_status = 'reserved';

alter table public.purchase_transactions enable row level security;
grant all on table public.purchase_transactions to service_role;
drop policy if exists "Users can view own purchases" on public.purchase_transactions;
create policy "Users can view own purchases"
  on public.purchase_transactions
  for select
  to authenticated
  using ((select auth.uid()) = user_id);

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
begin
  if p_purchase_id is null or p_user_id is null or upper(coalesce(p_wallet_code,'')) <> 'NGN'
     or p_amount is null or p_amount <= 0 or p_amount > 10000000 then
    raise exception 'Invalid purchase reservation';
  end if;
  v_amount := round(p_amount, 2);
  select * into v_purchase from public.purchase_transactions
   where id = p_purchase_id and user_id = p_user_id for update;
  if not found then raise exception 'Purchase not found'; end if;
  if v_purchase.wallet_debit_status = 'reserved' then return true; end if;
  if v_purchase.wallet_debit_status <> 'not_reserved' or v_purchase.status <> 'pending'
     or round(v_purchase.amount,2) <> v_amount
     or upper(v_purchase.pay_from_wallet) <> 'NGN' then
    raise exception 'Purchase is not eligible for reservation';
  end if;
  select * into v_profile from public.profiles where id = p_user_id;
  if not found or v_profile.kyc_status <> 'Verified'
     or v_profile.kyc_bvn_verified_at is null or v_profile.kyc_nin_verified_at is null then
    raise exception 'Complete BVN and NIN verification before paying bills';
  end if;
  select * into v_wallet from public.wallets
   where user_id = p_user_id and code = 'NGN' for update;
  if not found then raise exception 'NGN wallet not found'; end if;
  if coalesce(v_wallet.balance,0) < v_amount then raise exception 'Insufficient NGN balance'; end if;
  update public.wallets set balance = coalesce(balance,0) - v_amount, updated_at = now()
   where id = v_wallet.id;
  update public.purchase_transactions
     set status = 'processing', wallet_debit_status = 'reserved',
         reserved_at = now(), updated_at = now()
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
  if p_purchase_id is null or p_user_id is null then raise exception 'Invalid purchase settlement'; end if;
  select * into v_purchase from public.purchase_transactions
   where id = p_purchase_id and user_id = p_user_id for update;
  if not found then raise exception 'Purchase not found'; end if;
  if v_purchase.wallet_debit_status in ('settled','refunded') then return v_purchase.wallet_debit_status; end if;
  if v_purchase.wallet_debit_status <> 'reserved' then raise exception 'Purchase has no reserved wallet funds'; end if;

  if v_status in ('success','successful','completed') then
    update public.purchase_transactions
       set status = 'success', wallet_debit_status = 'settled',
           provider_status = p_provider_status, provider_payload = coalesce(p_provider_payload,'{}'::jsonb),
           delivery_code = coalesce(p_delivery_code, delivery_code),
           completed_at = now(), settled_at = now(), updated_at = now()
     where id = p_purchase_id and user_id = p_user_id;
    return 'settled';
  elsif v_status in ('failed','reversed','cancelled') then
    update public.wallets set balance = coalesce(balance,0) + v_purchase.amount, updated_at = now()
     where user_id = p_user_id and code = 'NGN';
    if not found then raise exception 'NGN wallet not found during refund'; end if;
    update public.purchase_transactions
       set status = 'failed', wallet_debit_status = 'refunded',
           provider_status = p_provider_status, provider_payload = coalesce(p_provider_payload,'{}'::jsonb),
           error_message = coalesce(error_message, 'Provider reported failed bill payment'),
           refunded_at = now(), updated_at = now()
     where id = p_purchase_id and user_id = p_user_id;
    return 'refunded';
  else
    update public.purchase_transactions
       set status = 'review', provider_status = coalesce(p_provider_status,'pending'),
           provider_payload = coalesce(p_provider_payload,'{}'::jsonb), updated_at = now()
     where id = p_purchase_id and user_id = p_user_id;
    return 'reserved';
  end if;
end;
$$;

revoke all on function public.reserve_wallet_purchase(uuid,uuid,text,numeric) from public, anon, authenticated;
revoke all on function public.settle_wallet_purchase(uuid,uuid,text,text,text,jsonb) from public, anon, authenticated;
grant execute on function public.reserve_wallet_purchase(uuid,uuid,text,numeric) to service_role;
grant execute on function public.settle_wallet_purchase(uuid,uuid,text,text,text,jsonb) to service_role;

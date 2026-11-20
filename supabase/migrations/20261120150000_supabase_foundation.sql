-- Nedu Wallet Supabase foundation.
-- Authentication identities live in Supabase Auth (auth.users); never store passwords here.
create extension if not exists pgcrypto with schema extensions;

create type public.wallet_status as enum ('ACTIVE', 'FROZEN', 'CLOSED');
create type public.ledger_account_type as enum (
  'USER_WALLET', 'FLUTTERWAVE_CLEARING', 'FEES_REVENUE', 'WITHDRAWAL_PAYABLE'
);
create type public.entry_direction as enum ('DEBIT', 'CREDIT');
create type public.payment_status as enum ('PENDING', 'PROCESSING', 'SUCCEEDED', 'FAILED', 'REVERSED');
create type public.payment_kind as enum ('DEPOSIT', 'WITHDRAWAL', 'TRANSFER', 'VIRTUAL_ACCOUNT');

create table public.profiles (
  id uuid primary key references auth.users(id) on delete restrict,
  email text,
  phone text unique,
  first_name text,
  last_name text,
  is_active boolean not null default true,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table public.wallets (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null unique references public.profiles(id) on delete restrict,
  currency text not null default 'NGN' check (currency ~ '^[A-Z]{3}$'),
  status public.wallet_status not null default 'ACTIVE',
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table public.ledger_accounts (
  id uuid primary key default gen_random_uuid(),
  wallet_id uuid unique references public.wallets(id) on delete restrict,
  code text not null unique,
  account_type public.ledger_account_type not null,
  currency text not null default 'NGN' check (currency ~ '^[A-Z]{3}$'),
  created_at timestamptz not null default now(),
  check (
    (account_type = 'USER_WALLET' and wallet_id is not null)
    or (account_type <> 'USER_WALLET' and wallet_id is null)
  )
);

create table public.journal_transactions (
  id uuid primary key default gen_random_uuid(),
  reference text not null unique,
  description text not null,
  currency text not null default 'NGN' check (currency ~ '^[A-Z]{3}$'),
  created_at timestamptz not null default now()
);

create table public.ledger_entries (
  id uuid primary key default gen_random_uuid(),
  journal_id uuid not null references public.journal_transactions(id) on delete restrict,
  account_id uuid not null references public.ledger_accounts(id) on delete restrict,
  direction public.entry_direction not null,
  amount numeric(20,2) not null check (amount > 0),
  created_at timestamptz not null default now()
);
create index ledger_entries_account_created_idx on public.ledger_entries(account_id, created_at);
create index ledger_entries_journal_idx on public.ledger_entries(journal_id);

create table public.payment_transactions (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references public.profiles(id) on delete restrict,
  tx_ref text not null unique,
  provider_ref text unique,
  kind public.payment_kind not null,
  status public.payment_status not null default 'PENDING',
  amount numeric(20,2) not null check (amount > 0),
  currency text not null default 'NGN' check (currency ~ '^[A-Z]{3}$'),
  provider text not null default 'FLUTTERWAVE',
  checkout_url text,
  metadata jsonb,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create index payment_transactions_user_created_idx on public.payment_transactions(user_id, created_at desc);
create index payment_transactions_status_created_idx on public.payment_transactions(status, created_at);

create table public.virtual_accounts (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references public.profiles(id) on delete restrict,
  tx_ref text not null unique,
  provider_ref text,
  account_number text not null,
  bank_name text not null,
  currency text not null default 'NGN',
  expires_at timestamptz,
  created_at timestamptz not null default now()
);
create index virtual_accounts_user_idx on public.virtual_accounts(user_id);

create table public.webhook_events (
  id uuid primary key default gen_random_uuid(),
  event_key text not null unique,
  event_type text not null,
  payload jsonb not null,
  processed_at timestamptz,
  created_at timestamptz not null default now()
);

-- Provision one profile, NGN wallet and wallet ledger account atomically on Auth signup.
create or replace function public.handle_new_auth_user()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_email text;
  v_phone text;
  v_first text;
  v_last text;
  v_wallet_id uuid;
begin
  v_email := nullif(lower(trim(coalesce(new.email, ''))), '');
  -- Only Supabase Auth's verified phone field is trusted here; ignore arbitrary metadata.
  v_phone := nullif(trim(coalesce(new.phone, '')), '');
  v_first := nullif(trim(coalesce(new.raw_user_meta_data ->> 'first_name', '')), '');
  v_last := nullif(trim(coalesce(new.raw_user_meta_data ->> 'last_name', '')), '');

  insert into public.profiles (id, email, phone, first_name, last_name)
  values (new.id, v_email, v_phone, v_first, v_last);

  insert into public.wallets (user_id, currency)
  values (new.id, 'NGN')
  returning id into v_wallet_id;

  insert into public.ledger_accounts (wallet_id, code, account_type, currency)
  values (v_wallet_id, 'wallet:' || v_wallet_id::text, 'USER_WALLET', 'NGN');

  return new;
end;
$$;

create trigger on_auth_user_created
  after insert on auth.users
  for each row execute procedure public.handle_new_auth_user();

-- System ledger accounts used by later payment/transfer functions.
insert into public.ledger_accounts (code, account_type, currency) values
  ('system:flutterwave-clearing:NGN', 'FLUTTERWAVE_CLEARING', 'NGN'),
  ('system:fees-revenue:NGN', 'FEES_REVENUE', 'NGN'),
  ('system:withdrawal-payable:NGN', 'WITHDRAWAL_PAYABLE', 'NGN');

-- A journal cannot commit unless it has at least two entries and total debits equal credits.
create or replace function public.assert_journal_balanced()
returns trigger
language plpgsql
set search_path = ''
as $$
declare
  v_journal_id uuid;
  v_debits numeric(20,2);
  v_credits numeric(20,2);
  v_count bigint;
begin
  if tg_op = 'DELETE' then
    v_journal_id := old.journal_id;
  else
    v_journal_id := new.journal_id;
  end if;
  select
    coalesce(sum(case when direction = 'DEBIT' then amount else 0 end), 0),
    coalesce(sum(case when direction = 'CREDIT' then amount else 0 end), 0),
    count(*)
  into v_debits, v_credits, v_count
  from public.ledger_entries
  where journal_id = v_journal_id;

  if v_count < 2 or v_debits <> v_credits then
    raise exception 'Journal % is not balanced (entries %, debits %, credits %)',
      v_journal_id, v_count, v_debits, v_credits;
  end if;
  return null;
end;
$$;

create constraint trigger ledger_journal_must_balance
  after insert or update or delete on public.ledger_entries
  deferrable initially deferred
  for each row execute procedure public.assert_journal_balanced();

-- Posted ledger rows are append-only. Corrections must use reversing journals.
create or replace function public.prevent_ledger_entry_mutation()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  raise exception 'Ledger entries are immutable; post a reversal instead.';
end;
$$;
create trigger ledger_entries_are_immutable
  before update or delete on public.ledger_entries
  for each row execute procedure public.prevent_ledger_entry_mutation();

-- Updated timestamps are maintained by the database.
create or replace function public.set_updated_at()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  new.updated_at = now();
  return new;
end;
$$;
create trigger profiles_set_updated_at before update on public.profiles
  for each row execute procedure public.set_updated_at();
create trigger wallets_set_updated_at before update on public.wallets
  for each row execute procedure public.set_updated_at();
create trigger payment_transactions_set_updated_at before update on public.payment_transactions
  for each row execute procedure public.set_updated_at();

-- RLS is enabled everywhere. Clients receive read-only access to their own account-facing records.
alter table public.profiles enable row level security;
alter table public.wallets enable row level security;
alter table public.ledger_accounts enable row level security;
alter table public.journal_transactions enable row level security;
alter table public.ledger_entries enable row level security;
alter table public.payment_transactions enable row level security;
alter table public.virtual_accounts enable row level security;
alter table public.webhook_events enable row level security;

create policy "profiles_select_own" on public.profiles
  for select to authenticated using (id = (select auth.uid()));
create policy "wallets_select_own" on public.wallets
  for select to authenticated using (user_id = (select auth.uid()));
create policy "ledger_accounts_select_own" on public.ledger_accounts
  for select to authenticated using (
    exists (select 1 from public.wallets w where w.id = wallet_id and w.user_id = (select auth.uid()))
  );
create policy "ledger_entries_select_own" on public.ledger_entries
  for select to authenticated using (
    exists (
      select 1 from public.ledger_accounts a
      join public.wallets w on w.id = a.wallet_id
      where a.id = account_id and w.user_id = (select auth.uid())
    )
  );
create policy "payments_select_own" on public.payment_transactions
  for select to authenticated using (user_id = (select auth.uid()));
create policy "virtual_accounts_select_own" on public.virtual_accounts
  for select to authenticated using (user_id = (select auth.uid()));

-- No client-side inserts/updates/deletes for financial records or webhook data.
revoke insert, update, delete on public.wallets, public.ledger_accounts,
  public.journal_transactions, public.ledger_entries, public.payment_transactions,
  public.virtual_accounts, public.webhook_events from anon, authenticated;
revoke insert, update, delete on public.profiles from anon, authenticated;
grant select on public.profiles, public.wallets, public.ledger_accounts,
  public.ledger_entries, public.payment_transactions, public.virtual_accounts
  to authenticated;

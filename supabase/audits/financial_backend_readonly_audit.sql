-- Nedu Wallet: READ-ONLY financial backend compatibility audit
-- Run this in Supabase SQL Editor on the EXISTING project.
-- This script reads PostgreSQL catalogs only. It does not read customer rows,
-- change data, create objects, alter permissions, or execute wallet RPCs.
-- Share the result grids (redact project-specific secrets if any appear).

-- 1) Exact columns and types for the live financial/account tables.
select
  table_schema,
  table_name,
  ordinal_position,
  column_name,
  data_type,
  udt_name,
  is_nullable,
  column_default,
  numeric_precision,
  numeric_scale
from information_schema.columns
where table_schema = 'public'
  and table_name in (
    'profiles', 'wallets', 'transactions', 'rates',
    'user_security', 'user_backups', 'cards', 'nedu_cards',
    'notifications'
  )
order by table_name, ordinal_position;

-- 2) Full definitions and execute grants for existing wallet-changing RPCs.
-- Function bodies may contain business logic; they should be reviewed before
-- any client is allowed to invoke them.
select
  p.oid::regprocedure as function_signature,
  pg_get_userbyid(p.proowner) as owner,
  p.prosecdef as security_definer,
  p.proconfig as function_settings,
  pg_get_functiondef(p.oid) as function_definition,
  has_function_privilege('anon', p.oid, 'EXECUTE') as anon_can_execute,
  has_function_privilege('authenticated', p.oid, 'EXECUTE') as authenticated_can_execute,
  has_function_privilege('service_role', p.oid, 'EXECUTE') as service_role_can_execute
from pg_proc p
join pg_namespace n on n.oid = p.pronamespace
where n.nspname = 'public'
  and p.proname in (
    'add_money_to_wallet', 'send_money', 'swap_money',
    'create_default_wallets'
  )
order by p.proname, p.oid::regprocedure::text;

-- 3) All triggers on relevant tables, including their exact trigger functions.
select
  c.relname as table_name,
  t.tgname as trigger_name,
  pg_get_triggerdef(t.oid, true) as trigger_definition,
  p.oid::regprocedure as trigger_function,
  pg_get_functiondef(p.oid) as trigger_function_definition
from pg_trigger t
join pg_class c on c.oid = t.tgrelid
join pg_namespace n on n.oid = c.relnamespace
join pg_proc p on p.oid = t.tgfoid
where not t.tgisinternal
  and n.nspname = 'public'
  and c.relname in ('profiles', 'wallets', 'transactions', 'rates')
order by c.relname, t.tgname;

-- 4) Constraints and indexes affecting balances, transaction references,
-- uniqueness/idempotency, and currency ownership.
select
  tbl.relname as table_name,
  con.conname as object_name,
  case con.contype
    when 'p' then 'PRIMARY KEY'
    when 'u' then 'UNIQUE'
    when 'f' then 'FOREIGN KEY'
    when 'c' then 'CHECK'
    when 'x' then 'EXCLUSION'
    else con.contype::text
  end as object_type,
  pg_get_constraintdef(con.oid, true) as definition
from pg_constraint con
join pg_class tbl on tbl.oid = con.conrelid
join pg_namespace ns on ns.oid = tbl.relnamespace
where ns.nspname = 'public'
  and tbl.relname in ('profiles', 'wallets', 'transactions', 'rates')
order by tbl.relname, con.contype, con.conname;

select
  tablename,
  indexname,
  indexdef
from pg_indexes
where schemaname = 'public'
  and tablename in ('profiles', 'wallets', 'transactions', 'rates')
order by tablename, indexname;

-- 5) RLS state and policies for the same tables.
select
  c.relname as table_name,
  c.relrowsecurity as rls_enabled,
  c.relforcerowsecurity as rls_forced
from pg_class c
join pg_namespace n on n.oid = c.relnamespace
where n.nspname = 'public'
  and c.relkind = 'r'
  and c.relname in ('profiles', 'wallets', 'transactions', 'rates')
order by c.relname;

select
  schemaname,
  tablename,
  policyname,
  permissive,
  roles,
  cmd,
  qual,
  with_check
from pg_policies
where schemaname = 'public'
  and tablename in ('profiles', 'wallets', 'transactions', 'rates')
order by tablename, policyname;

-- 6) Direct table privileges. This helps identify whether clients can write
-- balances or transaction history without going through a trusted server.
select
  table_name,
  grantee,
  privilege_type
from information_schema.role_table_grants
where table_schema = 'public'
  and table_name in ('profiles', 'wallets', 'transactions', 'rates')
  and grantee in ('anon', 'authenticated', 'service_role')
order by table_name, grantee, privilege_type;

-- 7) Other public functions that mention the financial tables/RPC names.
-- This is a useful dependency scan, not proof that external clients do not
-- call the RPCs. iOS source call sites still need a separate review.
select
  p.oid::regprocedure as function_signature,
  pg_get_userbyid(p.proowner) as owner,
  p.prosecdef as security_definer,
  pg_get_functiondef(p.oid) as function_definition
from pg_proc p
join pg_namespace n on n.oid = p.pronamespace
where n.nspname = 'public'
  and p.prokind = 'f'
  and (
    pg_get_functiondef(p.oid) ilike '%public.wallets%'
    or pg_get_functiondef(p.oid) ilike '%public.transactions%'
    or pg_get_functiondef(p.oid) ilike '%send_money%'
    or pg_get_functiondef(p.oid) ilike '%swap_money%'
    or pg_get_functiondef(p.oid) ilike '%add_money_to_wallet%'
  )
order by p.proname, p.oid::regprocedure::text;

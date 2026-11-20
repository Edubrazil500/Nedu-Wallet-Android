-- Database behavior tests for the Nedu Wallet Supabase foundation.
begin;

create extension if not exists pgtap with schema extensions;

select plan(12);

-- Simulate two Supabase Auth signups. The auth.users trigger must provision
-- each profile, NGN wallet, and wallet ledger account atomically.
insert into auth.users (
  instance_id, id, aud, role, email, encrypted_password,
  email_confirmed_at, raw_app_meta_data, raw_user_meta_data,
  created_at, updated_at
) values
  (
    null, '10000000-0000-4000-8000-000000000001',
    'authenticated', 'authenticated', 'wallet-test-one@example.test', '',
    now(), '{"provider":"email","providers":["email"]}',
    '{"first_name":"Ada","last_name":"One"}', now(), now()
  ),
  (
    null, '10000000-0000-4000-8000-000000000002',
    'authenticated', 'authenticated', 'wallet-test-two@example.test', '',
    now(), '{"provider":"email","providers":["email"]}',
    '{"first_name":"Tunde","last_name":"Two"}', now(), now()
  );

select is(
  (select count(*)::int from public.profiles
   where id = '10000000-0000-4000-8000-000000000001'),
  1, 'Auth signup provisions exactly one profile'
);
select is(
  (select count(*)::int from public.wallets
   where user_id = '10000000-0000-4000-8000-000000000001' and currency = 'NGN'),
  1, 'Auth signup provisions one NGN wallet'
);
select is(
  (select count(*)::int
   from public.ledger_accounts a
   join public.wallets w on w.id = a.wallet_id
   where w.user_id = '10000000-0000-4000-8000-000000000001'
     and a.account_type = 'USER_WALLET'),
  1, 'Auth signup provisions one wallet ledger account'
);
select is(
  (select first_name from public.profiles
   where id = '10000000-0000-4000-8000-000000000001'),
  'Ada', 'Profile reads first name from signup metadata'
);

-- Verify row-level isolation using the same claim Supabase Auth supplies.
set local role authenticated;
set local request.jwt.claim.sub = '10000000-0000-4000-8000-000000000001';

select is((select count(*)::int from public.profiles), 1,
  'RLS exposes only the signed-in user profile');
select is((select count(*)::int from public.wallets), 1,
  'RLS exposes only the signed-in user wallet');
select is((select count(*)::int from public.profiles
  where id = '10000000-0000-4000-8000-000000000002'), 0,
  'RLS hides another user profile');
select is((select count(*)::int from public.wallets
  where user_id = '10000000-0000-4000-8000-000000000002'), 0,
  'RLS hides another user wallet');

reset role;
select ok(
  not has_table_privilege('authenticated', 'public.wallets', 'INSERT'),
  'Authenticated clients cannot insert wallets directly'
);

-- A balanced journal should be accepted and retain both immutable entries.
insert into public.journal_transactions (reference, description, currency)
values ('test-balanced-journal-001', 'Database test deposit', 'NGN');

insert into public.ledger_entries (journal_id, account_id, direction, amount)
select
  j.id, a.id,
  case when a.account_type = 'FLUTTERWAVE_CLEARING' then 'DEBIT'::public.entry_direction
       else 'CREDIT'::public.entry_direction end,
  2500.00
from public.journal_transactions j
cross join public.ledger_accounts a
where j.reference = 'test-balanced-journal-001'
  and a.code in (
    'system:flutterwave-clearing:NGN',
    (select 'wallet:' || w.id::text from public.wallets w
     where w.user_id = '10000000-0000-4000-8000-000000000001')
  );

set constraints ledger_journal_must_balance immediate;
set constraints ledger_journal_must_balance deferred;

select is(
  (select count(*)::int from public.ledger_entries e
   join public.journal_transactions j on j.id = e.journal_id
   where j.reference = 'test-balanced-journal-001'),
  2, 'Balanced journal stores both entries'
);

create function pg_temp.unbalanced_journal_is_rejected()
returns boolean
language plpgsql
as $$
begin
  insert into public.journal_transactions (reference, description, currency)
  values ('test-unbalanced-journal-001', 'Expected rejection', 'NGN');

  insert into public.ledger_entries (journal_id, account_id, direction, amount)
  select j.id, a.id, 'CREDIT', 100.00
  from public.journal_transactions j
  join public.ledger_accounts a
    on a.code = 'system:flutterwave-clearing:NGN'
  where j.reference = 'test-unbalanced-journal-001';

  set constraints ledger_journal_must_balance immediate;
  return false;
exception
  when sqlstate 'P0001' then
    return true;
end;
$$;

select ok(pg_temp.unbalanced_journal_is_rejected(),
  'Database rejects an unbalanced journal');

create function pg_temp.ledger_update_is_rejected()
returns boolean
language plpgsql
as $$
begin
  update public.ledger_entries
  set amount = 1.00
  where id = (
    select e.id
    from public.ledger_entries e
    join public.journal_transactions j on j.id = e.journal_id
    where j.reference = 'test-balanced-journal-001'
    limit 1
  );
  return false;
exception
  when sqlstate 'P0001' then
    return true;
end;
$$;

select ok(pg_temp.ledger_update_is_rejected(),
  'Posted ledger entries cannot be updated');

select * from finish();
rollback;

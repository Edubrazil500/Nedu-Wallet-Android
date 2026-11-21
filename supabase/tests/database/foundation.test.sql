-- Database behavior tests for the Nedu Wallet Supabase foundation.
begin;

create extension if not exists pgtap with schema extensions;

select plan(40);

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

-- Bill purchases are server-only. The service role reserves funds atomically,
-- while authenticated clients cannot create or settle purchases directly.
select ok(
  not has_table_privilege('authenticated', 'public.purchase_transactions', 'INSERT'),
  'Authenticated clients cannot insert purchase records'
);
select ok(
  not has_function_privilege(
    'authenticated',
    'public.reserve_wallet_purchase(uuid,uuid,text,numeric)',
    'EXECUTE'
  ),
  'Authenticated clients cannot reserve wallet funds'
);
select ok(
  has_function_privilege(
    'service_role',
    'public.reserve_wallet_purchase(uuid,uuid,text,numeric)',
    'EXECUTE'
  ),
  'Service role can reserve wallet funds'
);
select ok(
  has_table_privilege('service_role', 'public.purchase_transactions', 'INSERT'),
  'Service role can create purchase records'
);

update public.profiles
set kyc_status = 'Verified',
    kyc_bvn_verified_at = now(),
    kyc_nin_verified_at = now()
where id = '10000000-0000-4000-8000-000000000001';

update public.wallets
set balance = 5000
where user_id = '10000000-0000-4000-8000-000000000001'
  and code = 'NGN';

insert into public.purchase_transactions (
  id, user_id, type, category, provider, provider_code, account_number,
  amount, currency, pay_from_wallet, status, provider_reference, metadata
) values
  (
    '20000000-0000-4000-8000-000000000001',
    '10000000-0000-4000-8000-000000000001',
    'bills', 'POWER', 'Flutterwave', 'BIL100', '12345678901',
    500, 'NGN', 'NGN', 'pending', 'NEDU-BILL-TEST-ONE',
    '{"item_code":"POWER-PREPAID"}'
  ),
  (
    '20000000-0000-4000-8000-000000000002',
    '10000000-0000-4000-8000-000000000001',
    'bills', 'POWER', 'Flutterwave', 'BIL100', '12345678901',
    250, 'NGN', 'NGN', 'pending', 'NEDU-BILL-TEST-TWO',
    '{"item_code":"POWER-PREPAID"}'
  ),
  (
    '20000000-0000-4000-8000-000000000003',
    '10000000-0000-4000-8000-000000000002',
    'bills', 'POWER', 'Flutterwave', 'BIL100', '12345678902',
    100, 'NGN', 'NGN', 'pending', 'NEDU-BILL-TEST-THREE',
    '{"item_code":"POWER-PREPAID"}'
  );

select is(
  public.reserve_wallet_purchase(
    '20000000-0000-4000-8000-000000000001',
    '10000000-0000-4000-8000-000000000001',
    'NGN',
    500
  ),
  true,
  'Verified user can reserve NGN for a bill'
);
select is(
  (select balance from public.wallets
   where user_id = '10000000-0000-4000-8000-000000000001' and code = 'NGN'),
  4500::numeric,
  'Reservation debits the wallet exactly once'
);
select is(
  (select status || ':' || wallet_debit_status
   from public.purchase_transactions
   where id = '20000000-0000-4000-8000-000000000001'),
  'processing:reserved',
  'Reservation marks the purchase as processing and reserved'
);
select ok(
  (select wallet_transaction_id is not null
   from public.purchase_transactions
   where id = '20000000-0000-4000-8000-000000000001'),
  'Reservation links a wallet transaction history row'
);
select is(
  (select status from public.transactions
   where id = (select wallet_transaction_id from public.purchase_transactions
     where id = '20000000-0000-4000-8000-000000000001')),
  'pending',
  'Wallet history shows the bill payment as pending while provider confirms'
);
select is(
  public.settle_wallet_purchase(
    '20000000-0000-4000-8000-000000000001',
    '10000000-0000-4000-8000-000000000001',
    'pending'
  ),
  'reserved',
  'Pending provider result keeps the funds reserved'
);
select is(
  (select status || ':' || wallet_debit_status
   from public.purchase_transactions
   where id = '20000000-0000-4000-8000-000000000001'),
  'review:reserved',
  'Pending provider result moves the purchase to review'
);
select is(
  (select status from public.transactions
   where id = (select wallet_transaction_id from public.purchase_transactions
     where id = '20000000-0000-4000-8000-000000000001')),
  'pending',
  'Pending provider result keeps wallet history pending'
);
select is(
  (select balance from public.wallets
   where user_id = '10000000-0000-4000-8000-000000000001' and code = 'NGN'),
  4500::numeric,
  'Pending provider result does not release reserved funds'
);
select is(
  public.settle_wallet_purchase(
    '20000000-0000-4000-8000-000000000001',
    '10000000-0000-4000-8000-000000000001',
    'failed'
  ),
  'refunded',
  'Confirmed provider failure refunds the reservation'
);
select is(
  (select balance from public.wallets
   where user_id = '10000000-0000-4000-8000-000000000001' and code = 'NGN'),
  5000::numeric,
  'Failed purchase restores the exact reserved amount'
);
select is(
  (select status from public.transactions
   where id = (select wallet_transaction_id from public.purchase_transactions
     where id = '20000000-0000-4000-8000-000000000001')),
  'refunded',
  'Wallet history records the confirmed bill refund'
);
select is(
  public.settle_wallet_purchase(
    '20000000-0000-4000-8000-000000000001',
    '10000000-0000-4000-8000-000000000001',
    'failed'
  ),
  'refunded',
  'Repeated failure settlement is idempotent'
);
select is(
  (select balance from public.wallets
   where user_id = '10000000-0000-4000-8000-000000000001' and code = 'NGN'),
  5000::numeric,
  'Repeated failure cannot issue a second refund'
);
select is(
  public.reserve_wallet_purchase(
    '20000000-0000-4000-8000-000000000002',
    '10000000-0000-4000-8000-000000000001',
    'NGN',
    250
  ),
  true,
  'A second verified purchase can reserve funds'
);
select is(
  public.settle_wallet_purchase(
    '20000000-0000-4000-8000-000000000002',
    '10000000-0000-4000-8000-000000000001',
    'successful'
  ),
  'settled',
  'Confirmed provider success settles the reservation'
);
select is(
  (select balance from public.wallets
   where user_id = '10000000-0000-4000-8000-000000000001' and code = 'NGN'),
  4750::numeric,
  'Successful purchase retains only the exact bill amount'
);
select is(
  (select status || ':' || wallet_debit_status
   from public.purchase_transactions
   where id = '20000000-0000-4000-8000-000000000002'),
  'success:settled',
  'Successful purchase is marked settled'
);
select is(
  (select status from public.transactions
   where id = (select wallet_transaction_id from public.purchase_transactions
     where id = '20000000-0000-4000-8000-000000000002')),
  'completed',
  'Wallet history records the confirmed successful bill payment'
);
select is(
  public.settle_wallet_purchase(
    '20000000-0000-4000-8000-000000000002',
    '10000000-0000-4000-8000-000000000001',
    'failed'
  ),
  'settled',
  'A later failure callback cannot refund a settled purchase'
);
select is(
  (select balance from public.wallets
   where user_id = '10000000-0000-4000-8000-000000000001' and code = 'NGN'),
  4750::numeric,
  'A settled purchase cannot be credited back by a duplicate callback'
);

create function pg_temp.unverified_bill_reservation_is_rejected()
returns boolean
language plpgsql
as $
begin
  perform public.reserve_wallet_purchase(
    '20000000-0000-4000-8000-000000000003',
    '10000000-0000-4000-8000-000000000002',
    'NGN',
    100
  );
  return false;
exception
  when others then return true;
end;
$;

select ok(
  pg_temp.unverified_bill_reservation_is_rejected(),
  'User without verified BVN and NIN cannot reserve bill funds'
);
select is(
  (select balance from public.wallets
   where user_id = '10000000-0000-4000-8000-000000000002' and code = 'NGN'),
  0::numeric,
  'KYC rejection leaves the unverified wallet unchanged'
);
select is(
  (select status || ':' || wallet_debit_status
   from public.purchase_transactions
   where id = '20000000-0000-4000-8000-000000000003'),
  'pending:not_reserved',
  'KYC rejection leaves the purchase unreserved'
);

select * from finish();
rollback;

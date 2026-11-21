alter table public.purchase_transactions
  add column if not exists dedupe_key text;

create unique index if not exists purchase_transactions_user_dedupe_uidx
  on public.purchase_transactions(user_id, dedupe_key)
  where dedupe_key is not null;

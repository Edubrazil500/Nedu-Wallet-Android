drop policy if exists "Users can insert own purchases" on public.purchase_transactions;
revoke insert, update, delete on table public.purchase_transactions from anon, authenticated;
grant select on table public.purchase_transactions to authenticated;

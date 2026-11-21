create unique index if not exists purchase_transactions_one_active_bill_per_account_uidx
on public.purchase_transactions (
  user_id,
  provider_code,
  account_number,
  (metadata->>'item_code')
)
where type = 'bills' and wallet_debit_status = 'reserved';

-- Transfer requests are review-only. Do not retain full bank account numbers before a payout provider is configured.
ALTER TABLE public.wallet_transfer_requests
  ADD COLUMN IF NOT EXISTS account_number_last4 text;
UPDATE public.wallet_transfer_requests
SET account_number_last4 = right(account_number, 4)
WHERE account_number_last4 IS NULL AND account_number IS NOT NULL;
ALTER TABLE public.wallet_transfer_requests
  DROP COLUMN IF EXISTS account_number;

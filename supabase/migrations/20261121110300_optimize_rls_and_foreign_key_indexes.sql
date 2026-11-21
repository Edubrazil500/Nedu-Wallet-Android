-- Cover account-scoped foreign keys and optimize existing RLS predicates.
CREATE INDEX IF NOT EXISTS idx_bill_validations_user_id ON public.bill_validations(user_id);
CREATE INDEX IF NOT EXISTS idx_esim_orders_user_id ON public.esim_orders(user_id);
CREATE INDEX IF NOT EXISTS idx_flutterwave_funding_events_user_id ON public.flutterwave_funding_events(user_id);
CREATE INDEX IF NOT EXISTS idx_gift_card_orders_user_id ON public.gift_card_orders(user_id);
CREATE INDEX IF NOT EXISTS idx_nedu_cards_user_id ON public.nedu_cards(user_id);
CREATE INDEX IF NOT EXISTS idx_purchase_orders_user_id ON public.purchase_orders(user_id);
CREATE INDEX IF NOT EXISTS idx_transactions_user_id ON public.transactions(user_id);
CREATE INDEX IF NOT EXISTS idx_wallet_transfer_requests_user_id ON public.wallet_transfer_requests(user_id);

DROP POLICY IF EXISTS "own transactions" ON public.transactions;
DROP POLICY IF EXISTS "user can read own security" ON public.user_security;

DO $$
DECLARE
  p record;
  roles_sql text;
  command_sql text;
  qual_sql text;
  check_expr text;
BEGIN
  FOR p IN
    SELECT * FROM pg_policies
    WHERE schemaname='public'
      AND (
        coalesce(qual,'') ILIKE '%auth.uid()%'
        OR coalesce(qual,'') ILIKE '%auth.role()%'
        OR coalesce(with_check,'') ILIKE '%auth.uid()%'
        OR coalesce(with_check,'') ILIKE '%auth.role()%'
      )
  LOOP
    EXECUTE format('DROP POLICY %I ON %I.%I',p.policyname,p.schemaname,p.tablename);
    roles_sql:=array_to_string(p.roles,', ');
    command_sql:=format('CREATE POLICY %I ON %I.%I AS %s FOR %s TO %s',p.policyname,p.schemaname,p.tablename,p.permissive,p.cmd,roles_sql);
    qual_sql:=replace(replace(coalesce(p.qual,''),'auth.uid()','(select auth.uid())'),'auth.role()','(select auth.role())');
    check_expr:=replace(replace(coalesce(p.with_check,''),'auth.uid()','(select auth.uid())'),'auth.role()','(select auth.role())');
    IF p.qual IS NOT NULL THEN command_sql:=command_sql||format(' USING (%s)',qual_sql); END IF;
    IF p.with_check IS NOT NULL THEN command_sql:=command_sql||format(' WITH CHECK (%s)',check_expr); END IF;
    EXECUTE command_sql;
  END LOOP;
END $$;

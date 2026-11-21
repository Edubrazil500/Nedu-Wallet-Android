-- Cover account-scoped foreign keys where the corresponding legacy app tables exist.
-- The production project has these tables, while the minimal local foundation schema may not.
DO $$
DECLARE
  item record;
BEGIN
  FOR item IN
    SELECT * FROM (VALUES
      ('bill_validations','user_id','idx_bill_validations_user_id'),
      ('esim_orders','user_id','idx_esim_orders_user_id'),
      ('flutterwave_funding_events','user_id','idx_flutterwave_funding_events_user_id'),
      ('gift_card_orders','user_id','idx_gift_card_orders_user_id'),
      ('nedu_cards','user_id','idx_nedu_cards_user_id'),
      ('purchase_orders','user_id','idx_purchase_orders_user_id'),
      ('transactions','user_id','idx_transactions_user_id'),
      ('wallet_transfer_requests','user_id','idx_wallet_transfer_requests_user_id')
    ) AS indexes(table_name,column_name,index_name)
  LOOP
    IF to_regclass(format('public.%I',item.table_name)) IS NOT NULL
       AND EXISTS (
         SELECT 1 FROM information_schema.columns
         WHERE table_schema='public'
           AND table_name=item.table_name
           AND column_name=item.column_name
       ) THEN
      EXECUTE format(
        'CREATE INDEX IF NOT EXISTS %I ON public.%I(%I)',
        item.index_name,item.table_name,item.column_name
      );
    END IF;
  END LOOP;
END $$;

-- These named legacy policies are only present in the full application schema.
DO $$
BEGIN
  IF to_regclass('public.transactions') IS NOT NULL THEN
    DROP POLICY IF EXISTS "own transactions" ON public.transactions;
  END IF;
  IF to_regclass('public.user_security') IS NOT NULL THEN
    DROP POLICY IF EXISTS "user can read own security" ON public.user_security;
  END IF;
END $$;

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

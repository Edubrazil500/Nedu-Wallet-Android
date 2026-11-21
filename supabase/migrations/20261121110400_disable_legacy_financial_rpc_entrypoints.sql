-- Disable legacy financial entrypoints that are superseded by verified backend workflows.
-- The original iOS-era RPCs are present in the live database, but are not all
-- created by the clean-room foundation migrations used by local CI. Revoke
-- every overload that exists without making fresh installs depend on them.
DO $$
DECLARE
  legacy_function record;
BEGIN
  FOR legacy_function IN
    SELECT p.oid::regprocedure AS signature
    FROM pg_proc AS p
    JOIN pg_namespace AS n ON n.oid = p.pronamespace
    WHERE n.nspname = 'public'
      AND p.proname = ANY (ARRAY[
        'add_money_to_wallet',
        'send_money',
        'create_default_card',
        'swap_money'
      ])
  LOOP
    EXECUTE format(
      'REVOKE EXECUTE ON FUNCTION %s FROM PUBLIC, anon, authenticated, service_role',
      legacy_function.signature
    );
  END LOOP;
END
$$;

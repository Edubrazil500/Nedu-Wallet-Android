-- Disable legacy financial entrypoints that are superseded by verified backend workflows.
REVOKE EXECUTE ON FUNCTION public.add_money_to_wallet(uuid,text,numeric,text,text)
  FROM PUBLIC, anon, authenticated, service_role;
REVOKE EXECUTE ON FUNCTION public.send_money(uuid,text,numeric,text,text,text,text,text)
  FROM PUBLIC, anon, authenticated, service_role;
REVOKE EXECUTE ON FUNCTION public.create_default_card(uuid,text)
  FROM PUBLIC, anon, authenticated, service_role;

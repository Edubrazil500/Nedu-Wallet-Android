-- Grant backend-only database access to the Supabase service role.
-- Client roles remain restricted by table grants and RLS.
GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public TO service_role;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO service_role;

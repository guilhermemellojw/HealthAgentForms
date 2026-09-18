-- Triggers/policies executam helpers do schema app como INVOKER; service_role
-- (bypass RLS, mas sem USAGE implícito em schemas novos) precisa de USAGE.
GRANT USAGE ON SCHEMA app TO service_role;

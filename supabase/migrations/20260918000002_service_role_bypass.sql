-- service_role (backend confiável) contorna os guards de trigger de nível de app.
-- RLS já é bypassed por service_role; sem isso, o seed histórico (ex. transfers
-- DECLINED, role inicial) seria bloqueado pelos mesmos guards que protegem clients.
-- Clients (anon/authenticated) continuam sujeitos a todos os guards.
CREATE OR REPLACE FUNCTION app.is_service_role()
RETURNS boolean
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = '' AS $$
  SELECT (select auth.jwt()->>'role') = 'service_role';
$$;

CREATE OR REPLACE FUNCTION app.protect_profile_privileges()
RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER
SET search_path = '' AS $$
BEGIN
  IF (select app.is_service_role()) THEN RETURN NEW; END IF;
  IF NOT (select app.is_admin()) THEN
    IF NEW.role IS DISTINCT FROM OLD.role
       OR NEW.is_authorized IS DISTINCT FROM OLD.is_authorized THEN
      RAISE EXCEPTION 'profiles: role/is_authorized só podem ser alterados por admin';
    END IF;
  END IF;
  RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION app.enforce_transfer_transition()
RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER
SET search_path = '' AS $$
DECLARE
  actor uuid := (select auth.uid());
  actor_is_origin boolean := (OLD.from_agent_id IS NOT DISTINCT FROM actor);
  actor_is_dest   boolean := (OLD.to_agent_id IS NOT DISTINCT FROM actor
                              OR OLD.to_key IS NOT DISTINCT FROM (select app.my_agent_name()));
BEGIN
  -- backend confiável (seed, admin console, edge functions): sem guards de app
  IF (select app.is_service_role()) THEN RETURN NEW; END IF;

  -- INSERT: origem cria PENDING p/ outro agente (to_key válido e não-próprio)
  IF TG_OP = 'INSERT' THEN
    IF NEW.from_agent_id IS DISTINCT FROM actor AND NOT (select app.is_admin()) THEN
      RAISE EXCEPTION 'day_transfers: from_agent_id deve ser o próprio usuário';
    END IF;
    IF NEW.status <> 'PENDING' THEN
      RAISE EXCEPTION 'day_transfers: oferta deve nascer PENDING';
    END IF;
    IF EXISTS (SELECT 1 FROM public.profiles p
               WHERE p.id = NEW.from_agent_id AND p.agent_name IS NOT DISTINCT FROM NEW.to_key) THEN
      RAISE EXCEPTION 'day_transfers: to_key não pode ser o próprio nome';
    END IF;
    RETURN NEW;
  END IF;

  -- UPDATE: só sobre PENDING (rules); delete de não-PENDING é política RLS de DELETE
  IF OLD.status <> 'PENDING' THEN
    RAISE EXCEPTION 'day_transfers: só ofertas PENDING podem ser alteradas';
  END IF;
  IF actor_is_dest AND NOT actor_is_origin THEN
    -- destino aceita/recusa: whitelist = status, final_date_text, accepted_at, to_agent_id
    IF NEW.id IS DISTINCT FROM OLD.id
       OR NEW.from_agent_id IS DISTINCT FROM OLD.from_agent_id
       OR NEW.from_name IS DISTINCT FROM OLD.from_name
       OR NEW.to_name IS DISTINCT FROM OLD.to_name
       OR NEW.to_key IS DISTINCT FROM OLD.to_key
       OR NEW.from_date_text IS DISTINCT FROM OLD.from_date_text
       OR NEW.house_count IS DISTINCT FROM OLD.house_count
       OR NEW.offered_at IS DISTINCT FROM OLD.offered_at
       OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
      RAISE EXCEPTION 'day_transfers: destino só pode alterar status/finalDate/acceptedAt/toUid';
    END IF;
    RETURN NEW;
  ELSIF actor_is_origin AND NOT actor_is_dest THEN
    -- origem cancela: whitelist = status
    IF NEW.id IS DISTINCT FROM OLD.id
       OR NEW.from_agent_id IS DISTINCT FROM OLD.from_agent_id
       OR NEW.to_agent_id IS DISTINCT FROM OLD.to_agent_id
       OR NEW.from_name IS DISTINCT FROM OLD.from_name
       OR NEW.to_name IS DISTINCT FROM OLD.to_name
       OR NEW.to_key IS DISTINCT FROM OLD.to_key
       OR NEW.from_date_text IS DISTINCT FROM OLD.from_date_text
       OR NEW.final_date_text IS DISTINCT FROM OLD.final_date_text
       OR NEW.accepted_at IS DISTINCT FROM OLD.accepted_at
       OR NEW.house_count IS DISTINCT FROM OLD.house_count
       OR NEW.offered_at IS DISTINCT FROM OLD.offered_at
       OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
      RAISE EXCEPTION 'day_transfers: origem só pode cancelar (status)';
    END IF;
    RETURN NEW;
  END IF;
  RAISE EXCEPTION 'day_transfers: ator sem permissão para esta transição';
END;
$$;

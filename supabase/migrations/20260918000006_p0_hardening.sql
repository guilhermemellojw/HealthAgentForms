-- ============================================================================
-- P0 bug-hunt fixes (auditoria pré-cutover).
-- P0-1 transfer guard: whitelist de status por ator + anti self-transfer por UID
-- P0-2 agents DELETE só admin (dono não pode wipear a própria produção)
-- P0-3 day_transfers FK ON DELETE CASCADE (purge travava com transfer ativa)
-- P0-4 access_requests: status só muda por admin (solicitante não se aprova)
-- P0-5 Realtime: profiles/agents/monthly_summaries/backups (admin ao vivo)
-- P0-6 CHECK DD-MM-YYYY em data_text/date_text ('' virava NULL invisível no RG)
-- P0-7 updated_at condicional (re-seed preserva cursor; app omite -> carimba)
-- Idempotente.
-- ============================================================================

-- ----------------------------------------------------------------------------
-- P0-1: state-machine completa de day_transfers
-- ----------------------------------------------------------------------------
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

  IF TG_OP = 'INSERT' THEN
    IF NEW.from_agent_id IS DISTINCT FROM actor AND NOT (select app.is_admin()) THEN
      RAISE EXCEPTION 'day_transfers: from_agent_id deve ser o próprio usuário';
    END IF;
    IF NEW.status <> 'PENDING' THEN
      RAISE EXCEPTION 'day_transfers: oferta deve nascer PENDING';
    END IF;
    IF NEW.to_agent_id IS NOT DISTINCT FROM NEW.from_agent_id THEN
      RAISE EXCEPTION 'day_transfers: não é possível transferir para si mesmo';
    END IF;
    IF EXISTS (SELECT 1 FROM public.profiles p
               WHERE p.id = NEW.from_agent_id AND p.agent_name IS NOT DISTINCT FROM NEW.to_key) THEN
      RAISE EXCEPTION 'day_transfers: to_key não pode ser o próprio nome';
    END IF;
    RETURN NEW;
  END IF;

  -- UPDATE: só sobre PENDING; delete de não-PENDING é política RLS de DELETE
  IF OLD.status <> 'PENDING' THEN
    RAISE EXCEPTION 'day_transfers: só ofertas PENDING podem ser alteradas';
  END IF;
  IF actor_is_dest AND NOT actor_is_origin THEN
    -- destino: só aceita ou recusa; whitelist de colunas
    IF NEW.status NOT IN ('ACCEPTED', 'DECLINED') THEN
      RAISE EXCEPTION 'day_transfers: destino só pode aceitar (ACCEPTED) ou recusar (DECLINED)';
    END IF;
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
    -- origem: só cancela; whitelist = status
    IF NEW.status <> 'CANCELLED' THEN
      RAISE EXCEPTION 'day_transfers: origem só pode cancelar (CANCELLED)';
    END IF;
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

-- ----------------------------------------------------------------------------
-- P0-2: agents DELETE só admin (SELECT/INSERT/UPDATE seguem owner||staff)
-- ----------------------------------------------------------------------------
DROP POLICY IF EXISTS "agents all" ON public.agents;
DROP POLICY IF EXISTS "agents select" ON public.agents;
DROP POLICY IF EXISTS "agents insert" ON public.agents;
DROP POLICY IF EXISTS "agents update" ON public.agents;
DROP POLICY IF EXISTS "agents delete admin" ON public.agents;
CREATE POLICY "agents select" ON public.agents FOR SELECT TO authenticated
  USING ((select auth.uid()) = id OR (select app.is_staff()));
CREATE POLICY "agents insert" ON public.agents FOR INSERT TO authenticated
  WITH CHECK ((select auth.uid()) = id OR (select app.is_staff()));
CREATE POLICY "agents update" ON public.agents FOR UPDATE TO authenticated
  USING ((select auth.uid()) = id OR (select app.is_staff()))
  WITH CHECK ((select auth.uid()) = id OR (select app.is_staff()));
CREATE POLICY "agents delete admin" ON public.agents FOR DELETE TO authenticated
  USING ((select app.is_admin()));

-- ----------------------------------------------------------------------------
-- P0-3: transfers acompanham o agente no purge
-- ----------------------------------------------------------------------------
ALTER TABLE public.day_transfers DROP CONSTRAINT IF EXISTS day_transfers_from_agent_id_fkey;
ALTER TABLE public.day_transfers DROP CONSTRAINT IF EXISTS day_transfers_to_agent_id_fkey;
ALTER TABLE public.day_transfers
  ADD CONSTRAINT day_transfers_from_agent_id_fkey
  FOREIGN KEY (from_agent_id) REFERENCES public.agents(id) ON UPDATE CASCADE ON DELETE CASCADE;
ALTER TABLE public.day_transfers
  ADD CONSTRAINT day_transfers_to_agent_id_fkey
  FOREIGN KEY (to_agent_id) REFERENCES public.agents(id) ON UPDATE CASCADE ON DELETE CASCADE;

-- ----------------------------------------------------------------------------
-- P0-4: status de access_requests só por admin
-- ----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION app.protect_request_status()
RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER
SET search_path = '' AS $$
BEGIN
  IF (select app.is_service_role()) THEN RETURN NEW; END IF;
  IF OLD.status IS DISTINCT FROM NEW.status AND NOT (select app.is_admin()) THEN
    RAISE EXCEPTION 'access_requests: status só pode ser alterado por admin';
  END IF;
  RETURN NEW;
END;
$$;
DROP TRIGGER IF EXISTS t_requests_status ON public.access_requests;
CREATE TRIGGER t_requests_status
  BEFORE UPDATE ON public.access_requests
  FOR EACH ROW EXECUTE FUNCTION app.protect_request_status();

-- ----------------------------------------------------------------------------
-- P0-5: Realtime cobre o que o portal assina
-- ----------------------------------------------------------------------------
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_publication_tables
                 WHERE pubname = 'supabase_realtime' AND schemaname = 'public' AND tablename = 'profiles') THEN
    ALTER PUBLICATION supabase_realtime ADD TABLE
      public.profiles, public.agents, public.monthly_summaries, public.backups;
  END IF;
END $$;
ALTER TABLE public.profiles REPLICA IDENTITY FULL;
ALTER TABLE public.agents REPLICA IDENTITY FULL;
ALTER TABLE public.monthly_summaries REPLICA IDENTITY FULL;
ALTER TABLE public.backups REPLICA IDENTITY FULL;

-- ----------------------------------------------------------------------------
-- P0-6: formato DD-MM-YYYY obrigatório (NULL invisível quebrava o RG)
-- ----------------------------------------------------------------------------
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'houses_data_text_fmt') THEN
    ALTER TABLE public.houses
      ADD CONSTRAINT houses_data_text_fmt CHECK (data_text ~ '^[0-9]{2}-[0-9]{2}-[0-9]{4}$');
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'activities_date_text_fmt') THEN
    ALTER TABLE public.day_activities
      ADD CONSTRAINT activities_date_text_fmt CHECK (date_text ~ '^[0-9]{2}-[0-9]{2}-[0-9]{4}$');
  END IF;
END $$;

-- ----------------------------------------------------------------------------
-- P0-7: updated_at só carimba quando o escritor não definiu (seed preserva)
-- ----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION app.touch_updated_at()
RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER
SET search_path = '' AS $$
BEGIN
  IF TG_OP = 'INSERT' THEN
    IF NEW.updated_at IS NULL THEN
      NEW.updated_at := now();
    END IF;
    RETURN NEW;
  END IF;
  IF NEW.updated_at IS NOT DISTINCT FROM OLD.updated_at THEN
    NEW.updated_at := now();
  END IF;
  RETURN NEW;
END;
$$;

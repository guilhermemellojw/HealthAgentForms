-- ============================================================================
-- HealthAgentForms · Supabase foundation (Fase 0 — shadow)
-- Firebase segue como source of truth até a paridade ser provada.
--
-- Espelha firestore.rules (146 linhas) + storage.rules em Postgres/RLS:
--   profiles        <- users/{uid}            (self||admin read; sem self-escalation)
--   agents          <- agents/{uid}           (owner||staff)
--   houses          <- agents/{uid}/houses    (read: qualquer logado; write: owner||staff)
--   day_activities  <- agents/{uid}/day_activities (idem houses)
--   monthly_summaries, backups <- subcoleções (owner||staff)
--   metadata        <- metadata/*            (read logados; write admin)
--   access_requests <- access_requests/*     (create próprio; read/delete admin)
--   admin_audit     <- admin_audit/*         (admin)
--   day_transfers   <- day_transfers/*       (state-machine origem/destino, sem admin)
--   storage bucket backups/{uid}/**         (owner||admin)
-- Convenções do playbook supabase-agent-skills:
--   - sem auth.role() (deprecated); políticas TO authenticated + predicado
--   - (select auth.uid()) para permitir initPlan
--   - helpers SECURITY DEFINER em schema privado `app` (nunca em public)
--   - GRANTs explícitos (tabelas novas NÃO são auto-expostas na Data API)
--   - UPDATE exige SELECT + USING + WITH CHECK
-- Idempotente: pode ser rodada de novo no SQL Editor sem quebrar.
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 0. Schema privado p/ helpers (defesa em profundidade; fora da Data API)
-- ----------------------------------------------------------------------------
CREATE SCHEMA IF NOT EXISTS app;
GRANT USAGE ON SCHEMA app TO authenticated;

-- ----------------------------------------------------------------------------
-- 1. profiles  <- users/{uid}
-- PK = auth.users.id (canonical). firebase_uid preservado p/ mapeamento da migração.
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS public.profiles (
  id                uuid PRIMARY KEY REFERENCES auth.users(id) ON DELETE CASCADE,
  firebase_uid      text UNIQUE,
  email             text NOT NULL,
  display_name      text,
  photo_url         text,
  role              text NOT NULL DEFAULT 'AGENT' CHECK (role IN ('AGENT','SUPERVISOR','ADMIN')),
  is_authorized     boolean NOT NULL DEFAULT false,
  agent_name        text UNIQUE,               -- canônico MAIÚSCULO (toKey das transferências)
  is_pre_registered boolean NOT NULL DEFAULT false,
  require_data_reset boolean NOT NULL DEFAULT false,
  created_at        timestamptz NOT NULL DEFAULT now(),
  updated_at        timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX IF NOT EXISTS profiles_email_unique ON public.profiles (lower(email));

-- ----------------------------------------------------------------------------
-- 2. agents  <- agents/{uid}   (1:1 com profiles; identidade do agente)
-- last_pull: cursor do pull delta (NULL = full pull). Substitui lastSyncTime como
-- protocolo; last_sync_time é só compat de leitura durante a transição.
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS public.agents (
  id                uuid PRIMARY KEY REFERENCES public.profiles(id) ON UPDATE CASCADE ON DELETE CASCADE,
  firebase_uid      text UNIQUE,
  email             text,
  agent_name        text,
  photo_url         text,
  is_pre_registered boolean NOT NULL DEFAULT false,
  last_sync_time    timestamptz,               -- compat: epoch-ms do Firestore
  last_pull         timestamptz,               -- cursor do pull delta (novo protocolo)
  app_version_code  integer,
  app_version_name  text,
  created_at        timestamptz NOT NULL DEFAULT now(),
  updated_at        timestamptz NOT NULL DEFAULT now()
);

-- ----------------------------------------------------------------------------
-- 3. houses  <- agents/{uid}/houses/{naturalKey}
-- data_text preserva "DD-MM-YYYY" (compat); data_date é a coluna canônica p/ queries.
-- deleted_at = soft-delete (substitui arrays deleted_house_ids; propaga no pull delta).
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS public.houses (
  id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  agent_id          uuid NOT NULL REFERENCES public.agents(id) ON UPDATE CASCADE ON DELETE CASCADE,
  natural_key       text NOT NULL,
  data_text         text NOT NULL,
  data_date         date,                          -- derivado de data_text via trigger
  street_name       text,
  number            text,
  block_number      text,
  block_sequence    text,
  sequence          integer,
  complement        integer,
  visit_segment     integer,
  list_order        integer,
  situation         text,
  property_type     text,
  com_foco          boolean,
  a1                integer NOT NULL DEFAULT 0,
  a2                integer NOT NULL DEFAULT 0,
  b                 integer NOT NULL DEFAULT 0,
  c                 integer NOT NULL DEFAULT 0,
  d1                integer NOT NULL DEFAULT 0,
  d2                integer NOT NULL DEFAULT 0,
  e                 integer NOT NULL DEFAULT 0,
  eliminados        integer NOT NULL DEFAULT 0,
  larvicida         integer NOT NULL DEFAULT 0,
  latitude          double precision,
  longitude         double precision,
  focus_capture_time timestamptz,
  observation       text,
  municipio         text,
  bairro            text,
  categoria         text,
  zona              text,
  tipo              text,                      -- código; Firestore mistura int/string -> text
  atividade         text,                      -- idem
  ciclo             text,
  localidade_concluida boolean,
  quarteirao_concluido boolean,
  agent_name        text,
  agent_uid         text,                      -- firebase uid (compat transição)
  client_uuid       text,                      -- uuid gerado no app (compat)
  edited_by_admin   boolean NOT NULL DEFAULT false,
  created_at        timestamptz,
  last_sync_time    timestamptz,               -- compat
  updated_at        timestamptz NOT NULL DEFAULT now(),
  deleted_at        timestamptz,               -- soft-delete (novo protocolo)
  UNIQUE (agent_id, natural_key)
);

-- ----------------------------------------------------------------------------
-- 4. day_activities  <- agents/{uid}/day_activities/{DD-MM-YYYY}
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS public.day_activities (
  id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  agent_id          uuid NOT NULL REFERENCES public.agents(id) ON UPDATE CASCADE ON DELETE CASCADE,
  date_text         text NOT NULL,
  date_value        date,                          -- derivado de date_text via trigger
  status            text,
  is_closed         boolean,
  is_manual_unlock  boolean,
  agent_name        text,
  agent_uid         text,
  edited_by_admin   boolean NOT NULL DEFAULT false,
  updated_at        timestamptz NOT NULL DEFAULT now(),
  deleted_at        timestamptz,
  UNIQUE (agent_id, date_text)
);

-- ----------------------------------------------------------------------------
-- 5. monthly_summaries  <- agents/{uid}/monthly_summaries/{MM-YYYY}
-- Mantida por trigger/pg_cron na Fase 1 (elimina refreshMonthSummary client-side).
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS public.monthly_summaries (
  id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  agent_id          uuid NOT NULL REFERENCES public.agents(id) ON UPDATE CASCADE ON DELETE CASCADE,
  month_year        text NOT NULL,
  treated_count     integer NOT NULL DEFAULT 0,
  focus_count       integer NOT NULL DEFAULT 0,
  total_houses      integer NOT NULL DEFAULT 0,
  days_worked       integer NOT NULL DEFAULT 0,
  situation_counts  jsonb NOT NULL DEFAULT '{}'::jsonb,
  property_type_counts jsonb NOT NULL DEFAULT '{}'::jsonb,
  updated_at        timestamptz NOT NULL DEFAULT now(),
  UNIQUE (agent_id, month_year)
);

-- ----------------------------------------------------------------------------
-- 6. backups (metadados)  <- agents/{uid}/backups/{ts}  (+ arquivos no Storage)
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS public.backups (
  id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  agent_id          uuid NOT NULL REFERENCES public.agents(id) ON UPDATE CASCADE ON DELETE CASCADE,
  ts                bigint NOT NULL,
  storage_path      text NOT NULL,
  house_count       integer NOT NULL DEFAULT 0,
  activity_count    integer NOT NULL DEFAULT 0,
  agent_name        text,
  created_at        timestamptz NOT NULL DEFAULT now(),
  UNIQUE (agent_id, ts)
);

-- ----------------------------------------------------------------------------
-- 7. metadata  <- metadata/{agent_info,locations,settings}
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS public.metadata (
  key               text PRIMARY KEY,
  value             jsonb NOT NULL DEFAULT '{}'::jsonb,
  updated_at        timestamptz NOT NULL DEFAULT now()
);

-- ----------------------------------------------------------------------------
-- 8. access_requests  <- access_requests/{uid}
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS public.access_requests (
  id                text PRIMARY KEY,          -- doc id Firestore (= uid solicitante)
  requester_id      uuid REFERENCES public.profiles(id) ON UPDATE CASCADE ON DELETE SET NULL,
  email             text,
  display_name      text,
  requested_name    text,
  status            text NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','APPROVED','REJECTED')),
  created_at        timestamptz NOT NULL DEFAULT now()
);

-- ----------------------------------------------------------------------------
-- 9. admin_audit  <- admin_audit/*  (admin only)
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS public.admin_audit (
  id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  firebase_doc_id   text UNIQUE,
  actor_uid         text,
  actor_email       text,
  action            text,
  payload           jsonb NOT NULL DEFAULT '{}'::jsonb,
  created_at        timestamptz NOT NULL DEFAULT now()
);

-- ----------------------------------------------------------------------------
-- 10. day_transfers  <- day_transfers/{fromUid_date_toKey}
-- State-machine replicada das rules §8: checks de formato aqui; transições no trigger.
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS public.day_transfers (
  id                text PRIMARY KEY,          -- id determinístico Firestore (rastreabilidade)
  from_agent_id     uuid NOT NULL REFERENCES public.agents(id) ON UPDATE CASCADE,
  to_agent_id       uuid REFERENCES public.agents(id) ON UPDATE CASCADE,
  from_name         text,
  to_name           text,
  to_key            text NOT NULL CHECK (to_key <> ''),
  from_date_text    text NOT NULL CHECK (from_date_text ~ '^[0-9]{2}-[0-9]{2}-[0-9]{4}$'),
  from_date         date,                          -- derivado de from_date_text via trigger
  final_date_text   text,
  status            text NOT NULL CHECK (status IN ('PENDING','ACCEPTED','DECLINED','CANCELLED')),
  house_count       integer NOT NULL DEFAULT 0 CHECK (house_count >= 0),
  offered_at        timestamptz NOT NULL DEFAULT now(),
  accepted_at       timestamptz,
  created_at        timestamptz NOT NULL DEFAULT now()
);

-- ----------------------------------------------------------------------------
-- 11. Helpers de autorização (SECURITY DEFINER, schema privado, com auth.uid() guard)
-- ----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION app.is_admin()
RETURNS boolean
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = '' AS $$
  SELECT (select auth.uid()) IS NOT NULL AND (
    lower((select auth.jwt()->>'email')) = lower('gmellobkp@gmail.com')
    OR EXISTS (
      SELECT 1 FROM public.profiles p
      WHERE p.id = (select auth.uid()) AND p.role = 'ADMIN'
    )
  );
$$;

CREATE OR REPLACE FUNCTION app.is_staff()
RETURNS boolean
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = '' AS $$
  SELECT (select app.is_admin()) OR EXISTS (
    SELECT 1 FROM public.profiles p
    WHERE p.id = (select auth.uid())
      AND p.is_authorized IS TRUE AND p.role = 'SUPERVISOR'
  );
$$;

CREATE OR REPLACE FUNCTION app.my_agent_name()
RETURNS text
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = '' AS $$
  SELECT p.agent_name FROM public.profiles p WHERE p.id = (select auth.uid());
$$;

-- updated_at automático
CREATE OR REPLACE FUNCTION app.touch_updated_at()
RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER
SET search_path = '' AS $$
BEGIN
  NEW.updated_at := now();
  RETURN NEW;
END;
$$;

-- profiles: sem self-escalation (rules §1: write próprio nunca define role/isAuthorized)
CREATE OR REPLACE FUNCTION app.protect_profile_privileges()
RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER
SET search_path = '' AS $$
BEGIN
  IF NOT (select app.is_admin()) THEN
    IF NEW.role IS DISTINCT FROM OLD.role
       OR NEW.is_authorized IS DISTINCT FROM OLD.is_authorized THEN
      RAISE EXCEPTION 'profiles: role/is_authorized só podem ser alterados por admin';
    END IF;
  END IF;
  RETURN NEW;
END;
$$;

-- day_transfers: state-machine das rules §8 (affectedKeys por ator).
-- Sem bypass de admin (o catch-all §7 das rules não se replica aqui;
-- emergências usam service_role). NULL-safe: to_agent_id pode ser NULL até o aceite.
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

-- Deriva colunas date de "DD-MM-YYYY" (fail-fast com mensagem clara no import/app)
CREATE OR REPLACE FUNCTION app.set_derived_dates()
RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER
SET search_path = '' AS $$
BEGIN
  IF TG_TABLE_NAME = 'houses' THEN
    NEW.data_date := CASE WHEN NEW.data_text IS NULL OR NEW.data_text = ''
      THEN NULL ELSE to_date(NEW.data_text, 'DD-MM-YYYY') END;
  ELSIF TG_TABLE_NAME = 'day_activities' THEN
    NEW.date_value := CASE WHEN NEW.date_text IS NULL OR NEW.date_text = ''
      THEN NULL ELSE to_date(NEW.date_text, 'DD-MM-YYYY') END;
  ELSIF TG_TABLE_NAME = 'day_transfers' THEN
    NEW.from_date := to_date(NEW.from_date_text, 'DD-MM-YYYY');
  END IF;
  RETURN NEW;
EXCEPTION WHEN OTHERS THEN
  RAISE EXCEPTION 'data inválida em %.%: %', TG_TABLE_SCHEMA, TG_TABLE_NAME, SQLERRM;
END;
$$;

-- Claim de perfil migrado: no primeiro login, o auth.uid() novo assume a linha seed
-- (casada por e-mail verificado do Google). Cascata atualiza agents + filhas.
CREATE OR REPLACE FUNCTION app.claim_migrated_profile()
RETURNS void
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = '' AS $$
DECLARE
  caller_email text := lower((select auth.jwt()->>'email'));
  caller_uid   uuid := (select auth.uid());
BEGIN
  IF caller_uid IS NULL OR caller_email IS NULL THEN
    RAISE EXCEPTION 'claim: sem sessão autenticada com e-mail';
  END IF;
  UPDATE public.profiles
     SET id = caller_uid, updated_at = now()
   WHERE lower(email) = caller_email AND id <> caller_uid;
END;
$$;

-- Stub de perfil no signup (se o e-mail ainda não tem linha): o app completa depois.
-- Evita duplicar a linha seed da migração.
CREATE OR REPLACE FUNCTION app.handle_new_auth_user()
RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = '' AS $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM public.profiles WHERE lower(email) = lower(NEW.email)) THEN
    INSERT INTO public.profiles (id, email, display_name, photo_url)
    VALUES (NEW.id, NEW.email,
            NEW.raw_user_meta_data->>'name',
            NEW.raw_user_meta_data->>'picture');
  END IF;
  RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS on_auth_user_created ON auth.users;
CREATE TRIGGER on_auth_user_created
  AFTER INSERT ON auth.users
  FOR EACH ROW EXECUTE FUNCTION app.handle_new_auth_user();

-- Triggers updated_at
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_trigger WHERE tgname = 't_profiles_upd') THEN
    CREATE TRIGGER t_profiles_upd BEFORE UPDATE ON public.profiles
      FOR EACH ROW EXECUTE FUNCTION app.touch_updated_at();
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_trigger WHERE tgname = 't_profiles_priv') THEN
    CREATE TRIGGER t_profiles_priv BEFORE UPDATE ON public.profiles
      FOR EACH ROW EXECUTE FUNCTION app.protect_profile_privileges();
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_trigger WHERE tgname = 't_agents_upd') THEN
    CREATE TRIGGER t_agents_upd BEFORE UPDATE ON public.agents
      FOR EACH ROW EXECUTE FUNCTION app.touch_updated_at();
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_trigger WHERE tgname = 't_houses_upd') THEN
    CREATE TRIGGER t_houses_upd BEFORE UPDATE ON public.houses
      FOR EACH ROW EXECUTE FUNCTION app.touch_updated_at();
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_trigger WHERE tgname = 't_activities_upd') THEN
    CREATE TRIGGER t_activities_upd BEFORE UPDATE ON public.day_activities
      FOR EACH ROW EXECUTE FUNCTION app.touch_updated_at();
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_trigger WHERE tgname = 't_summaries_upd') THEN
    CREATE TRIGGER t_summaries_upd BEFORE UPDATE ON public.monthly_summaries
      FOR EACH ROW EXECUTE FUNCTION app.touch_updated_at();
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_trigger WHERE tgname = 't_metadata_upd') THEN
    CREATE TRIGGER t_metadata_upd BEFORE UPDATE ON public.metadata
      FOR EACH ROW EXECUTE FUNCTION app.touch_updated_at();
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_trigger WHERE tgname = 't_transfers_guard') THEN
    CREATE TRIGGER t_transfers_guard BEFORE INSERT OR UPDATE ON public.day_transfers
      FOR EACH ROW EXECUTE FUNCTION app.enforce_transfer_transition();
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_trigger WHERE tgname = 't_houses_dates') THEN
    CREATE TRIGGER t_houses_dates BEFORE INSERT OR UPDATE OF data_text ON public.houses
      FOR EACH ROW EXECUTE FUNCTION app.set_derived_dates();
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_trigger WHERE tgname = 't_activities_dates') THEN
    CREATE TRIGGER t_activities_dates BEFORE INSERT OR UPDATE OF date_text ON public.day_activities
      FOR EACH ROW EXECUTE FUNCTION app.set_derived_dates();
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_trigger WHERE tgname = 't_transfers_dates') THEN
    CREATE TRIGGER t_transfers_dates BEFORE INSERT OR UPDATE OF from_date_text ON public.day_transfers
      FOR EACH ROW EXECUTE FUNCTION app.set_derived_dates();
  END IF;
END $$;

-- ----------------------------------------------------------------------------
-- 12. RLS (toda tabela exposta tem RLS + políticas por comando)
-- ----------------------------------------------------------------------------
ALTER TABLE public.profiles            ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.agents              ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.houses              ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.day_activities      ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.monthly_summaries   ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.backups             ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.metadata            ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.access_requests     ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.admin_audit         ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.day_transfers       ENABLE ROW LEVEL SECURITY;

-- profiles: read self||admin (rules §1); write: self (sem escalação, via trigger) ou admin
DROP POLICY IF EXISTS "profiles select" ON public.profiles;
CREATE POLICY "profiles select" ON public.profiles FOR SELECT TO authenticated
  USING ((select auth.uid()) = id OR (select app.is_admin()));
DROP POLICY IF EXISTS "profiles insert admin" ON public.profiles;
CREATE POLICY "profiles insert admin" ON public.profiles FOR INSERT TO authenticated
  WITH CHECK ((select app.is_admin()));
DROP POLICY IF EXISTS "profiles update" ON public.profiles;
CREATE POLICY "profiles update" ON public.profiles FOR UPDATE TO authenticated
  USING ((select auth.uid()) = id OR (select app.is_admin()))
  WITH CHECK ((select auth.uid()) = id OR (select app.is_admin()));
DROP POLICY IF EXISTS "profiles delete admin" ON public.profiles;
CREATE POLICY "profiles delete admin" ON public.profiles FOR DELETE TO authenticated
  USING ((select app.is_admin()));

-- agents: owner||staff (rules §2)
DROP POLICY IF EXISTS "agents all" ON public.agents;
CREATE POLICY "agents all" ON public.agents FOR ALL TO authenticated
  USING ((select auth.uid()) = id OR (select app.is_staff()))
  WITH CHECK ((select auth.uid()) = id OR (select app.is_staff()));

-- houses / day_activities: read qualquer logado (RG unificado); write owner||staff
DROP POLICY IF EXISTS "houses select" ON public.houses;
CREATE POLICY "houses select" ON public.houses FOR SELECT TO authenticated USING (true);
DROP POLICY IF EXISTS "houses write" ON public.houses;
CREATE POLICY "houses write" ON public.houses FOR INSERT TO authenticated
  WITH CHECK (agent_id = (select auth.uid()) OR (select app.is_staff()));
DROP POLICY IF EXISTS "houses update" ON public.houses;
CREATE POLICY "houses update" ON public.houses FOR UPDATE TO authenticated
  USING (agent_id = (select auth.uid()) OR (select app.is_staff()))
  WITH CHECK (agent_id = (select auth.uid()) OR (select app.is_staff()));
DROP POLICY IF EXISTS "houses delete" ON public.houses;
CREATE POLICY "houses delete" ON public.houses FOR DELETE TO authenticated
  USING (agent_id = (select auth.uid()) OR (select app.is_staff()));

DROP POLICY IF EXISTS "activities select" ON public.day_activities;
CREATE POLICY "activities select" ON public.day_activities FOR SELECT TO authenticated USING (true);
DROP POLICY IF EXISTS "activities write" ON public.day_activities;
CREATE POLICY "activities write" ON public.day_activities FOR INSERT TO authenticated
  WITH CHECK (agent_id = (select auth.uid()) OR (select app.is_staff()));
DROP POLICY IF EXISTS "activities update" ON public.day_activities;
CREATE POLICY "activities update" ON public.day_activities FOR UPDATE TO authenticated
  USING (agent_id = (select auth.uid()) OR (select app.is_staff()))
  WITH CHECK (agent_id = (select auth.uid()) OR (select app.is_staff()));
DROP POLICY IF EXISTS "activities delete" ON public.day_activities;
CREATE POLICY "activities delete" ON public.day_activities FOR DELETE TO authenticated
  USING (agent_id = (select auth.uid()) OR (select app.is_staff()));

-- monthly_summaries / backups: owner||staff
DROP POLICY IF EXISTS "summaries all" ON public.monthly_summaries;
CREATE POLICY "summaries all" ON public.monthly_summaries FOR ALL TO authenticated
  USING (agent_id = (select auth.uid()) OR (select app.is_staff()))
  WITH CHECK (agent_id = (select auth.uid()) OR (select app.is_staff()));
DROP POLICY IF EXISTS "backups all" ON public.backups;
CREATE POLICY "backups all" ON public.backups FOR ALL TO authenticated
  USING (agent_id = (select auth.uid()) OR (select app.is_staff()))
  WITH CHECK (agent_id = (select auth.uid()) OR (select app.is_staff()));

-- metadata: read logados; write admin (rules §3)
DROP POLICY IF EXISTS "metadata select" ON public.metadata;
CREATE POLICY "metadata select" ON public.metadata FOR SELECT TO authenticated USING (true);
DROP POLICY IF EXISTS "metadata write admin" ON public.metadata;
CREATE POLICY "metadata write admin" ON public.metadata FOR INSERT TO authenticated
  WITH CHECK ((select app.is_admin()));
DROP POLICY IF EXISTS "metadata update admin" ON public.metadata;
CREATE POLICY "metadata update admin" ON public.metadata FOR UPDATE TO authenticated
  USING ((select app.is_admin())) WITH CHECK ((select app.is_admin()));
DROP POLICY IF EXISTS "metadata delete admin" ON public.metadata;
CREATE POLICY "metadata delete admin" ON public.metadata FOR DELETE TO authenticated
  USING ((select app.is_admin()));

-- access_requests: create próprio; update admin||dono; read/delete admin (rules §4)
DROP POLICY IF EXISTS "requests insert" ON public.access_requests;
CREATE POLICY "requests insert" ON public.access_requests FOR INSERT TO authenticated
  WITH CHECK (requester_id = (select auth.uid()));
DROP POLICY IF EXISTS "requests update" ON public.access_requests;
CREATE POLICY "requests update" ON public.access_requests FOR UPDATE TO authenticated
  USING ((select app.is_admin()) OR requester_id = (select auth.uid()))
  WITH CHECK ((select app.is_admin()) OR requester_id = (select auth.uid()));
DROP POLICY IF EXISTS "requests read admin" ON public.access_requests;
CREATE POLICY "requests read admin" ON public.access_requests FOR SELECT TO authenticated
  USING ((select app.is_admin()));
DROP POLICY IF EXISTS "requests delete admin" ON public.access_requests;
CREATE POLICY "requests delete admin" ON public.access_requests FOR DELETE TO authenticated
  USING ((select app.is_admin()));

-- admin_audit: admin (rules §5b)
DROP POLICY IF EXISTS "audit admin" ON public.admin_audit;
CREATE POLICY "audit admin" ON public.admin_audit FOR ALL TO authenticated
  USING ((select app.is_admin())) WITH CHECK ((select app.is_admin()));

-- day_transfers: partes envolvidas (rules §8); state-machine no trigger
DROP POLICY IF EXISTS "transfers select" ON public.day_transfers;
CREATE POLICY "transfers select" ON public.day_transfers FOR SELECT TO authenticated
  USING (from_agent_id = (select auth.uid())
      OR to_agent_id = (select auth.uid())
      OR to_key = (select app.my_agent_name()));
DROP POLICY IF EXISTS "transfers insert" ON public.day_transfers;
CREATE POLICY "transfers insert" ON public.day_transfers FOR INSERT TO authenticated
  WITH CHECK (from_agent_id = (select auth.uid()) AND status = 'PENDING');
DROP POLICY IF EXISTS "transfers update" ON public.day_transfers;
CREATE POLICY "transfers update" ON public.day_transfers FOR UPDATE TO authenticated
  USING (from_agent_id = (select auth.uid())
      OR to_agent_id = (select auth.uid())
      OR to_key = (select app.my_agent_name()))
  WITH CHECK (from_agent_id = (select auth.uid())
      OR to_agent_id = (select auth.uid())
      OR to_key = (select app.my_agent_name()));
DROP POLICY IF EXISTS "transfers delete" ON public.day_transfers;
CREATE POLICY "transfers delete" ON public.day_transfers FOR DELETE TO authenticated
  USING (from_agent_id = (select auth.uid()) AND status <> 'PENDING');

-- ----------------------------------------------------------------------------
-- 13. Índices (padrões de consulta do app/portal; RG unificado cross-agent)
-- ----------------------------------------------------------------------------
CREATE INDEX IF NOT EXISTS houses_agent_date ON public.houses (agent_id, data_date) WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS houses_date_all ON public.houses (data_date) WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS houses_bairro_date ON public.houses (bairro, data_date) WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS houses_updated ON public.houses (agent_id, updated_at);
CREATE INDEX IF NOT EXISTS activities_agent_date ON public.day_activities (agent_id, date_value) WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS activities_updated ON public.day_activities (agent_id, updated_at);
CREATE INDEX IF NOT EXISTS transfers_to ON public.day_transfers (to_agent_id, status);
CREATE INDEX IF NOT EXISTS transfers_to_key ON public.day_transfers (to_key, status);
CREATE INDEX IF NOT EXISTS transfers_from ON public.day_transfers (from_agent_id, status);
CREATE INDEX IF NOT EXISTS requests_status ON public.access_requests (status);
CREATE INDEX IF NOT EXISTS backups_agent_ts ON public.backups (agent_id, ts DESC);

-- ----------------------------------------------------------------------------
-- 14. Realtime (substitui onSnapshot: produção RG, transfers, requests, metadata)
-- ----------------------------------------------------------------------------
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_publication_tables
                 WHERE pubname = 'supabase_realtime' AND schemaname = 'public' AND tablename = 'houses') THEN
    ALTER PUBLICATION supabase_realtime ADD TABLE
      public.houses, public.day_activities, public.day_transfers,
      public.access_requests, public.metadata;
  END IF;
END $$;
-- payload completo de DELETE p/ listeners de transfers/requests
ALTER TABLE public.day_transfers  REPLICA IDENTITY FULL;
ALTER TABLE public.access_requests REPLICA IDENTITY FULL;

-- ----------------------------------------------------------------------------
-- 15. Exposição na Data API (tabelas novas NÃO são auto-expostas; RLS filtra linhas)
-- ----------------------------------------------------------------------------
GRANT USAGE ON SCHEMA public TO authenticated;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO authenticated;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO authenticated;

-- ----------------------------------------------------------------------------
-- 16. Storage: bucket backups/ (owner||admin — espelha storage.rules)
-- ----------------------------------------------------------------------------
INSERT INTO storage.buckets (id, name, public)
VALUES ('backups', 'backups', false)
ON CONFLICT (id) DO NOTHING;

DROP POLICY IF EXISTS "backups owner read" ON storage.objects;
CREATE POLICY "backups owner read" ON storage.objects FOR SELECT TO authenticated
  USING (bucket_id = 'backups'
    AND ((storage.foldername(name))[1] = (select auth.uid())::text
         OR (select app.is_admin())));
DROP POLICY IF EXISTS "backups owner insert" ON storage.objects;
CREATE POLICY "backups owner insert" ON storage.objects FOR INSERT TO authenticated
  WITH CHECK (bucket_id = 'backups'
    AND ((storage.foldername(name))[1] = (select auth.uid())::text
         OR (select app.is_admin())));
DROP POLICY IF EXISTS "backups owner update" ON storage.objects;
CREATE POLICY "backups owner update" ON storage.objects FOR UPDATE TO authenticated
  USING (bucket_id = 'backups'
    AND ((storage.foldername(name))[1] = (select auth.uid())::text
         OR (select app.is_admin())))
  WITH CHECK (bucket_id = 'backups'
    AND ((storage.foldername(name))[1] = (select auth.uid())::text
         OR (select app.is_admin())));
DROP POLICY IF EXISTS "backups owner delete" ON storage.objects;
CREATE POLICY "backups owner delete" ON storage.objects FOR DELETE TO authenticated
  USING (bucket_id = 'backups'
    AND ((storage.foldername(name))[1] = (select auth.uid())::text
         OR (select app.is_admin())));

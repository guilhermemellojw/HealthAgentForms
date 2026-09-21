-- Wrapper PÚBLICO p/ claim de perfil migrado (portal/app chamam via PostgREST rpc).
-- Exceção documentada à regra "SECURITY DEFINER fora de public": a função só
-- reatribui o id QUANDO o e-mail verificado do JWT casa com a linha seed, e só
-- se ainda não houver linha com o auth.uid() atual. Sem parâmetros, sem bypass
-- genérico. Alternativa seria expor o schema `app` inteiro na Data API (pior).
CREATE OR REPLACE FUNCTION public.claim_migrated_profile()
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
  IF EXISTS (SELECT 1 FROM public.profiles WHERE id = caller_uid) THEN
    RETURN; -- já canonicalizado
  END IF;
  UPDATE public.profiles
     SET id = caller_uid, updated_at = now()
   WHERE lower(email) = caller_email AND id <> caller_uid;
END;
$$;
REVOKE ALL ON FUNCTION public.claim_migrated_profile() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.claim_migrated_profile() TO authenticated;

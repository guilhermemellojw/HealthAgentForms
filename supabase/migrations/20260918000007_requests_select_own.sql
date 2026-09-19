-- P0: requester enxerga o próprio pedido (sem SELECT, o UPDATE próprio
-- retornava 0 linhas em silêncio e o trigger de status nunca era alcançado).
DROP POLICY IF EXISTS "requests read admin" ON public.access_requests;
CREATE POLICY "requests select" ON public.access_requests FOR SELECT TO authenticated
  USING ((select app.is_admin()) OR requester_id = (select auth.uid()));

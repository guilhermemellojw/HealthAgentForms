-- larvicida admite frações reais (0.5, 1.5, ... frascos) — 35 docs no seed.
-- numeric (exato) em vez de integer; app trata como número em ambos clients.
ALTER TABLE public.houses ALTER COLUMN larvicida TYPE numeric USING larvicida::numeric;
ALTER TABLE public.houses ALTER COLUMN larvicida SET DEFAULT 0;

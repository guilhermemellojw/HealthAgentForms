#!/usr/bin/env node
/**
 * Aplica uma migration SQL no Supabase via conexão direta (token temporário/PAT
 * como senha — nunca commitar). Uso:
 *   SUPABASE_DB_URL="postgres://postgres@db.<ref>.supabase.co:5432/postgres?sslmode=require" \
 *     npm run apply -- <arquivo.sql>
 * O token vai na senha: postgres://postgres:<TOKEN>@db.<ref>.supabase.co:5432/...
 * Após aplicar, roda verificações (tabelas, RLS, policies, publication, bucket).
 */
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import pg from "pg";

const file = process.argv[2];
if (!file) {
  console.error("Uso: node apply-migration.mjs <arquivo.sql>");
  process.exit(1);
}
if (!process.env.SUPABASE_DB_URL) {
  console.error("Defina SUPABASE_DB_URL (com o token como senha). Veja ../.env.example");
  process.exit(1);
}

const sql = readFileSync(resolve(file), "utf8");
// sslmode configurável: pooler Supavisor usa cert self-signed -> no-verify;
// direto com CA válida -> require/verify-full.
const sslmode = process.env.SUPABASE_SSLMODE || "require";
const connectionString = process.env.SUPABASE_DB_URL.replace(
  /sslmode=[^&]*/,
  `sslmode=${sslmode}`
);
const client = new pg.Client({ connectionString });

try {
  await client.connect();
  console.log("Conectado. Aplicando migration em transação implícita...");
  await client.query(sql);
  console.log("Migration aplicada.");

  const checks = {
    tabelas: `SELECT tablename FROM pg_tables WHERE schemaname='public'
              AND tablename IN ('profiles','agents','houses','day_activities','monthly_summaries','backups','metadata','access_requests','admin_audit','day_transfers')
              ORDER BY 1`,
    rls_ativas: `SELECT tablename FROM pg_tables WHERE schemaname='public' AND rowsecurity
                 AND tablename LIKE ANY (ARRAY['profiles','agents','houses','day_activities','monthly_summaries','backups','metadata','access_requests','admin_audit','day_transfers']) ORDER BY 1`,
    policies: `SELECT tablename, count(*) FROM pg_policies WHERE schemaname='public' GROUP BY 1 ORDER BY 1`,
    realtime: `SELECT tablename FROM pg_publication_tables WHERE pubname='supabase_realtime' AND schemaname='public' ORDER BY 1`,
    bucket: `SELECT id, public FROM storage.buckets WHERE id='backups'`,
    funcoes_app: `SELECT proname FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
                  WHERE n.nspname='app' ORDER BY 1`,
  };
  for (const [nome, q] of Object.entries(checks)) {
    const { rows } = await client.query(q);
    console.log(`\n== ${nome} (${rows.length}) ==`);
    for (const r of rows) console.log("  " + JSON.stringify(r));
  }
} catch (e) {
  console.error("FALHA:", e.message);
  process.exit(1);
} finally {
  await client.end();
}

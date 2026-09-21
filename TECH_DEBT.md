# Tech Debt / Scalability Backlog

**Contexto**: App funcional para 2 agentes (30 casas/dia/agente). Arquitetura atual (Room + Firestore offline-first) suporta bem a escala atual. Este documento registra itens para quando escalar (>10 agentes ou >500 casas/dia/agente).

---

## 🔴 Crítico (fazer primeiro ao escalar)

| Item | Arquivo(s) | Esforço | Gatilho |
|------|------------|---------|---------|
| Coluna `normalizedDate` + índices compostos `(agentUid, normalizedDate)` | `AppDatabase.kt` (migration), `House.kt`, `HouseDao.kt` | ~4h | Queries com `REPLACE(data)` ficam >100ms |
| `allHousesFlow` → Paging 3 (`Pager` + `PagingSource`) | `HomeViewModel.kt`, novo `HousePagingSource.kt` | ~8h | Memória >100MB ou scroll travado |
| Limitar `collectionGroup` no TeamworkSync (`.limit(500)`) | `TeamworkSyncHandler.kt:32` | ~30min | Timeout 30s no Firestore |
| Sync pull paginado por cursor (`startAfter`) | `HouseRemoteDataSourceImpl.kt:24-40` | ~4h | Sync inicial >10s ou custo leitura alto |
| Supervisor: `fetchAgentsPaged` + lazy load casas | `AgentRepositoryImpl.kt:76`, `SupervisorViewModel.kt` | ~6h | >10 agentes na lista |

---

## 🟡 Importante (pós-crítico)

| Item | Arquivo(s) | Esforço | Gatilho |
|------|------------|---------|---------|
| TTL automático: tombstones >90d, rascunhos unsynced >30d | `AppDatabase.kt` (WorkManager), Firestore TTL policy | ~3h | DB local >500MB / custos Firestore |
| Monthly summaries → Cloud Function nightly (pré-agregado) | Novo `functions/`, `AgentRemoteDataSourceImpl.kt` | ~8h | Supervisor lento ao abrir relatórios |
| Export BigQuery para analytics histórico | Novo pipeline, `firestore.rules` | ~16h | Necessidade de BI / auditoria longa |
| Sharding `houses_{YYYYMM}` subcollections | Migração script + `HouseRemoteDataSourceImpl.kt` | ~16h | >5M casas/agente ou hotspot no doc agent |

---

## 🟢 Nice to Have

- Baseline Profiles + R8 full mode no `build.gradle.kts`
- Coil disk cache limit configurável
- PDF streaming (gera em chunks, não carrega tudo em memória)
- Sync checkpointing (salva progresso mid-sync para sobreviver app kill)

---

## Como priorizar quando chegar a hora

1. **Medir primeiro**: `adb shell dumpsys meminfo`, Firestore usage console, `EXPLAIN QUERY PLAN` no Room
2. **Atacar o gargalo real** (não o imaginado)
3. **Uma mudança por PR** com teste de carga local
4. **Manter offline-first** - nunca quebrar sync resiliente

---

## Referências rápidas no código

- **Sync core**: `SyncPullHandler.kt`, `SyncPushHandler.kt`, `SyncReconciler.kt`
- **Room DAOs**: `HouseDao.kt`, `DayActivityDao.kt`, `AppDatabase.kt` (migrations)
- **Repositórios**: `HouseRepositoryImpl.kt`, `AgentRepositoryImpl.kt`
- **UI Flows**: `HomeViewModel.kt` (linhas 120-135, 163-176), `WeeklySummaryViewModel.kt` (linhas 106-112)
- **Teamwork**: `TeamworkSyncHandler.kt:17-78`
- **Supervisor**: `SupervisorViewModel.kt:135-213`

---

*Criado em: 2026-08-12 | Escala atual: 2 agentes, ~30 casas/dia/agente*
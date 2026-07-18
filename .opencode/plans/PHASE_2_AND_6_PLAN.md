# Plano de Implementação - Fases Restantes (Phase 2 + Phase 6 + fix_plan.txt)

---

## 📋 Resumo do Estado Atual

| Item | Status |
|------|--------|
| Bugs críticos (3) | ✅ **Corrigidos** (284/285 testes passam) |
| Phase 1: Lifecycle & Leaks | ✅ Concluída |
| Phase 3: ViewModel Extraction | ✅ Concluída |
| Phase 4: Settings Consolidation | ✅ Concluída |
| Phase 5: Delegate Consolidation | ✅ Concluída (HouseEditViewModel removido, persistListOrder consolidado, TOCTOU corrigido) |
| Phase 6: Cleanup (parcial) | ⚠️ **Parcial** - UpdateHouseUseCase removido, restantes pendentes |
| **Phase 2: DB Migration Safety** | ❌ **Não iniciada** |
| **fix_plan.txt: monthly_summaries overwrite** | ❌ **Não endereçado** |

---

## 🎯 FASE 2: DB Migration Safety

### Objetivo
Garantir que migrações de banco de dados (Room) sejam seguras, idempotentes e testáveis, evitando perda de dados ou crashes em produção.

### Escopo Atual (PerformLocalDatabaseMigrationUseCase.kt)
```kotlin
// 3 migrações existentes:
1. migrateStreetNamesToFormat()      // formata streetName
2. migrateBairrosToUppercase()       // bairros para UPPERCASE  
3. migrateDateFormats()              // datas de "/" para "-"
```

### Problemas Identificados
- Migrações rodam em background (IO) sem transação atômica por batch
- `migrateDateFormats` faz delete + insert de DayActivity (risco de perda se crashar no meio)
- Sem versionamento de migração aplicada (roda toda vez no init)
- Sem testes de integração que validam estado antes/depois
- `NormalizeLocalDatesUseCase` duplicado em InitializationDelegate

### Plano de Ação

| Task | Descrição | Risco | Estimativa |
|------|-----------|-------|------------|
| **2.1** | Adicionar tabela `migration_log` (version, applied_at, checksum) para tracking | Baixo | 2h |
| **2.2** | Tornar migrações idempotentes com `WHERE` clauses que evitam re-processamento | Médio | 3h |
| **2.3** | `migrateDateFormats`: usar transação única para delete+insert de DayActivity | Alto | 2h |
| **2.4** | Adicionar `migrationVersion` no SettingsManager para pular já aplicadas | Baixo | 1h |
| **2.5** | Criar testes de integração: `MigrationIntegrationTest` com DB real (Room in-memory) | Médio | 4h |
| **2.6** | Remover `NormalizeLocalDatesUseCase` duplicado (usar o do migration) | Baixo | 1h |

### Critérios de Aceitação
- [ ] Migrações rodam uma única vez por device
- [ ] Rollback manual possível via `migration_log`
- [ ] Testes cobrem: dados já migrados, dados parciais, erro no meio
- [ ] Zero crashes em produção relacionados a migração

---

## 🐛 fix_plan.txt: monthly_summaries Overwrite Bug

### Localização
`SyncPushHandler.kt` linhas **365-453** (bloco "SUMMARY AGGREGATION")

### Bug Atual
Quando supervisor edita (`isProxyPush = true`):
1. Linha 369-388: Busca houses do mês no **cloud** (`whereIn("data", chunk)`)  
2. Linha 388: `allDocs.mapNotNull { it.toHouseSafe(...) }` → **apenas casas que o supervisor tem no cache local**
3. Linha 397-404: Filtra por data ≤ hoje
4. Linha 452: `userDocRef.collection("monthly_summaries").document(monthYear).set(summary)` → **SOBRESCREVE** o resumo mensal completo do agente com dados parciais

### Fix Recomendado (conforme fix_plan.txt linha 4-8)
> "If `isProxyPush` is true, we should SKIP updating `monthly_summaries` entirely. The agent will recalculate it perfectly the next time they sync."

### Implementação

```kotlin
// Em SyncPushHandler.kt, antes do loop for (monthYear in monthsToUpdate):
val isProxyPush = targetUid != null && targetUid != auth.currentUser?.uid

for (monthYear in monthsToUpdate) {
    // NOVO: Skip summary update on proxy push
    if (isProxyPush && !shouldReplace) {
        AppLogger.d("SyncPushHandler", "Skipping monthly_summaries update for $monthYear (proxy push)")
        continue
    }
    // ... resto do cálculo e escrita
}
```

### Arquivos
- `app/src/main/java/com/antigravity/healthagent/data/repository/SyncPushHandler.kt` (linhas 365-453)

### Testes
- Unit test: `SyncPushHandlerTest.shouldSkipMonthlySummaryOnProxyPush`
- Integration test: supervisor edit → push → verify monthly_summaries unchanged

### Estimativa: **2h**

---

## 🎯 FASE 6: Cleanup Restante

### 6.1 Inject Clock Interface

**Problema**: `TimeManager.currentTimeMillis()` e `System.currentTimeMillis()` espalhados no código (HouseEditDelegate, InitializationDelegate, SyncPushHandler, etc.) dificultam testes determinísticos de tempo.

**Solução**: Criar interface `Clock` e injetar via Hilt.

```kotlin
// Novo arquivo: domain/util/Clock.kt
interface Clock {
    fun currentTimeMillis(): Long
    fun instant(): java.time.Instant
}

// Implementação real
@Singleton
class SystemClock @Inject constructor() : Clock {
    override fun currentTimeMillis() = System.currentTimeMillis()
    override fun instant() = java.time.Instant.now()
}

// Implementação de teste
class TestClock(private val time: AtomicLong = AtomicLong(0)) : Clock {
    override fun currentTimeMillis() = time.get()
    override fun instant() = java.time.Instant.ofEpochMilli(time.get())
    fun advance(millis: Long) = time.addAndGet(millis)
}
```

**Locais a refatorar** (busca por `TimeManager.currentTimeMillis()` ou `System.currentTimeMillis()`):
- `HouseEditDelegate.kt`: linhas 397, 428, 432, 664
- `InitializationDelegate.kt`: linha 291
- `SyncPushHandler.kt`: linha 449
- `SaveHouseUseCase.kt`: verificar uso
- `DayManagementUseCase.kt`: verificar uso

**Estimativa: 4h**

---

### 6.2 KmlManager/KmlLayers Cleanup

**Análise do KmlManager.kt**:
- ✅ Não usa recursos que precisam cleanup (InputStream é do caller)
- ✅ `styleMap` e `styleMapMap` são limpos no início do `parseKml()`
- ✅ Sem `Closeable`, sem listeners, sem coroutines
- ⚠️ `DocumentBuilderFactory` criado a cada parse (pode ser cached via `ThreadLocal` ou pool)

**Verificação necessária**: `QuarteiroesViewModel.kt` uso do `KmlManager`
- `KmlManager` é `@Inject` singleton - OK
- `parseKml` chamado em coroutine - OK

**Ação**: Nenhuma mudança crítica. Opcional: otimizar `DocumentBuilderFactory` reuse.

**Estimativa: 1h (revisão apenas)**

---

### 6.3 Remove Hardcoded Email in generateMockData

**Localização**: `SyncViewModel.kt` linha **225**
```kotlin
val email = settingsManager.cachedUser.firstOrNull()?.email
if (email != "gmellobkp@gmail.com") return@launch  // HARDCODED
```

**Fix**: Mover para `SettingsManager` como configuração de "desenvolvedor autorizado" ou remover completamente (feature flag de debug).

```kotlin
// SettingsManager.kt - adicionar
val isMockDataAllowed: Boolean = BuildConfig.DEBUG && cachedUser.map { it.email }.firstOrNull()?.endsWith("@healthagent.com") == true

// SyncViewModel.kt
if (!settingsManager.isMockDataAllowed) return@launch
```

**Estimativa: 1h**

---

## 📦 Cronograma Consolidado

| Fase | Tasks | Estimativa Total | Prioridade |
|------|-------|------------------|------------|
| **Phase 2: DB Migration Safety** | 2.1–2.6 | **13h** | 🔴 Alta (produção) |
| **fix_plan.txt: monthly_summaries** | Skip on proxy push | **2h** | 🔴 Alta (data loss) |
| **Phase 6.1: Clock Interface** | Interface + DI + refactor 5+ files | **4h** | 🟡 Média (testabilidade) |
| **Phase 6.2: KmlManager Review** | Verificar apenas | **1h** | 🟢 Baixa |
| **Phase 6.3: Hardcoded Email** | SettingsManager flag + remove | **1h** | 🟡 Média |

**Total: ~21h**

---

## 🧪 Estratégia de Testes

### Phase 2 - Migration Tests
```kotlin
// MigrationIntegrationTest.kt
@Test fun `migrateStreetNamesToFormat runs once`() {
    // 1. Insert raw street names
    // 2. Run migration
    // 3. Verify formatted
    // 4. Run again - verify no duplicate work (idempotent)
    // 5. Verify migration_log has entry
}

@Test fun `migrateDateFormats preserves DayActivity in transaction`() {
    // Insert activities with "/" dates
    // Run migration
    // Verify all converted, none lost
}
```

### fix_plan.txt Test
```kotlin
// SyncPushHandlerTest.kt
@Test fun `monthlySummaries not updated on proxy push`() {
    // Setup: agent has 30 houses in month, summary exists
    // Supervisor pushes 1 house edit (isProxyPush=true)
    // Verify monthly_summaries.set() NOT called
    // Verify agent's next full sync recalculates correctly
}
```

### Clock Tests
```kotlin
// HouseEditDelegateTest with TestClock
@Test fun `debounce uses clock for deterministic timing`() {
    val clock = TestClock()
    val delegate = HouseEditDelegate(..., clock)
    // advance clock 300ms -> debounce fires
    // no flakiness
}
```

---

## ✅ Checklist de Execução

### Phase 2
- [ ] 2.1 Criar tabela `migration_log` (Room entity + DAO)
- [ ] 2.2 Refatorar 3 migrações para idempotência + log
- [ ] 2.3 Transação atômica em `migrateDateFormats`
- [ ] 2.4 SettingsManager.migrationVersion
- [ ] 2.5 Testes de integração (Room in-memory)
- [ ] 2.6 Remover NormalizeLocalDatesUseCase duplicado

### fix_plan.txt
- [ ] Editar `SyncPushHandler.kt` pular summary em proxy push
- [ ] Test unitário + integração

### Phase 6
- [ ] 6.1 Clock interface + Hilt module + refatorar 5+ arquivos
- [ ] 6.2 Revisar KmlManager (confirmar sem leaks)
- [ ] 6.3 Remover email hardcoded → SettingsManager flag

---

## 🚀 Ordem Recomendada de Execução

1. **fix_plan.txt** (2h) - Bug de perda de dados crítico, rápido
2. **Phase 2.1-2.4** (8h) - Core migration safety
3. **Phase 2.5** (4h) - Testes (pode rodar em paralelo com 6.x)
4. **Phase 6.1** (4h) - Clock interface (desbloqueia testes determinísticos)
5. **Phase 6.3** (1h) - Hardcoded email
6. **Phase 6.2** (1h) - KmlManager review
7. **Phase 2.6** (1h) - Cleanup NormalizeLocalDatesUseCase

---

## ❓ Perguntas para Alinhamento

1. **Phase 2**: O app já está em produção com usuários reais? Se sim, a tabela `migration_log` precisa ser criada via Room migration (não drop/create), e as migrações existentes precisam detectar "já aplicado" via query no DB atual.

2. **Clock Interface**: Prefere `java.time.Clock` (JDK 8+) ou interface customizada? `java.time.Clock` já tem `instant()`, `millis()`, `withZone()` - mais padrão.

3. **Hardcoded Email**: Remover completamente o `generateMockData` em release builds (`BuildConfig.DEBUG` guard) ou manter como feature flag para QA?

4. **Testes de Migração**: Usar `Room.inMemoryDatabaseBuilder` ou Testcontainers com PostgreSQL? Room in-memory é mais rápido para CI.

---

*Documento gerado em 2026-07-18. Pronto para execução sequencial.*
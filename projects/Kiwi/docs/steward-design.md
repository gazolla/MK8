# Kiwi — Design do Steward, Arquivos de Identidade e Autonomia

> Documento de design (rascunho vivo). Objetivo: descrever como o Kiwi evolui de
> "assistente reativo" para "assistente que persegue um objetivo, evolui criando
> agentes/plugins e mantém identidade durável" — **sem tocar `kernel/` nem `agent/`**,
> seguindo KISS e DRY.

---

## 0. Princípios inegociáveis

1. **Kernel e Agent fechados.** Tudo novo vive em `projects/Kiwi/` (tools/system/agents/docs). O runtime estrutural `agent/` só ganha **novas personas** (diretórios), nunca alteração de código.
2. **KISS — uma responsabilidade por componente.** O Steward decide; não executa nem reimplementa planner/workflow/creator.
3. **DRY — uma fonte de verdade.** Cada `.md` é único; consumido **sob demanda** por quem precisa, nunca copiado em prompts.
4. **Processo efêmero, estado durável.** Longo prazo = estado persistido + atenção periódica; não processo eternamente vivo.
5. **Governança antes de autonomia.** Cada degrau de autonomia exige guardrail correspondente (orçamento, aprovação, restrições do Soul).
6. **Aditivo antes de auto-modificação.** Criar capacidade nova (baixo risco) vem muito antes de reescrever capacidade existente (regressão).

---

## 1. Atores e altitudes

```
                         ┌──────────────────────────────────────┐
   USUÁRIO  ⇄ Telegram ⇄ │  ASSISTANT  (persistente, reativo)   │  ← a VOZ, única interface
                         │  - chat.respond                       │
                         └───────────────┬──────────────────────┘
                                         │ delega (agent.*)
            ┌────────────────────────────┼─────────────────────────────┐
            ▼                            ▼                              ▼
   ┌─────────────────┐        ┌────────────────────┐         ┌──────────────────┐
   │  STEWARD         │        │  Equipe especialista│         │  (auto-evolução) │
   │  (pulsante,      │ usa →  │  researcher/writer/ │  usa →  │  creator +       │
   │   deliberativo)  │        │  coder/planner ...  │         │  plugin-installer│
   │  agent.steward   │        └────────────────────┘         └──────────────────┘
   │  - decide        │ submete →  ┌──────────────────────┐
   │  - comissiona    │            │  WORKFLOW engine      │  ← executa DAGs longos
   │  - reflete       │            │  (persistente)        │     (parallel layers)
   └─────────────────┘            └──────────────────────┘
```

| Ator | Altitude | Função | Lifecycle | Já existe? |
|---|---|---|---|---|
| **Assistant** | reativo | única interface com o usuário; orquestra a equipe por requisição; é a voz | persistente (boot) | ✅ |
| **Steward** | deliberativo | mantém o objetivo; decide o próximo passo; comissiona; reflete; aciona evolução | **pulsante** (tick+evento) | ❌ a criar |
| **planner** | executor | decompõe objetivo/sub-objetivo em DAG | on-demand | ✅ |
| **workflow** | executor | executa o DAG até o fim (camadas paralelas, failure policy) | persistente | ✅ |
| **researcher/writer/coder/creator** | executores | tarefas especializadas; `creator` gera plugins | on-demand | ✅ |
| **plugin-installer** | sistema | valida/compila/instala/spawna plugins gerados | persistente | ✅ |

**Regra de ouro:** o Steward **não fala com o usuário direto** e **não escreve código de plugin**. Ele decide e delega. Isso preserva KISS (uma voz, uma altitude) e DRY (reusa planner/workflow/creator).

---

## 2. Os arquivos de identidade (`.md`)

Local proposto: `projects/Kiwi/mind/`. Quatro arquivos, **donos e mutabilidade distintos**:

| Arquivo | Conteúdo | Dono / quem escreve | Mutabilidade | Quem lê |
|---|---|---|---|---|
| **Soul.md** | identidade, valores, **meta-missão**, **restrições duras** (guardrails) | humano (ratifica) | quase imutável | Steward (diretriz); Assistant (referência p/ "quem é você?") |
| **User.md** | modelo do dono: quem é, preferências, necessidades recorrentes | humano semeia + Assistant enriquece | cresce devagar | Assistant |
| **Goal.md** | objetivo(s) atual(is), critério de sucesso, roadmap | humano semeia + Steward atualiza | mutável | Steward |
| **Journal.md** | memória de progresso: o que tentou, o que falhou, decisões, reflexões | **só** Steward | cresce sempre | Steward |

### Regras de consumo (anti-bloat, DRY)
- **Nunca** injetar os quatro num system prompt. Cada agente **lê sob demanda** o que é seu.
- Mecanismo: capability nova `context.load` (system plugin `projects/Kiwi/system/context/`) que devolve um arquivo por nome/escopo. Fonte única no disco; leitura on-demand. Reusa o padrão que o Kiwi já tem (assistant escreve `task.context` no blackboard, researcher lê → "context loaded for session").
- **Journal.md nunca vai inteiro pro contexto** (cresce sem limite ⇒ explosão de tokens/custo). O Steward lê **um resumo reflexionado** (as últimas N entradas + sumário comprimido). Reflexão periódica condensa o Journal (insight dos *Generative Agents*).

### Memória pesquisável (FTS5) — inspirado no Hermes (ver §12)

O `Journal.md` é a face **humana** (legível, auditável). Por baixo, a memória operacional do
Steward é um índice **pesquisável**, não um arquivo lido linha a linha:

- **Substrato que já existe:** o Kiwi tem SQLite (`data/sessions-*.db`), e SQLite traz **FTS5
  nativo** — busca full-text de graça, sem dependência nova.
- **Como funciona:** cada entrada do Journal é também indexada numa tabela FTS5
  (`memory` em `data/steward-memory.db`). No passo SENSE/REFLECT, o Steward **recupera por
  relevância** (consulta FTS pelo sub-objetivo atual) + um **resumo rolante**, em vez de
  carregar o histórico inteiro. É o mecanismo que torna o "nunca injete o Journal" viável de fato.
- **Curadoria periódica (Hermes "periodic nudges"):** na reflexão, o Steward **comprime**
  entradas antigas em sumários e marca aprendizados duráveis. Mantém o índice enxuto e útil.
- **Fonte única:** `Journal.md` (verdade legível) ↔ FTS5 (verdade pesquisável) andam juntos;
  a escrita passa pelo mesmo escritor único (lease, §6) para não divergirem.

### Conteúdo mínimo de cada arquivo (esqueleto)
- **Soul.md**: missão central · valores · tom/persona · **restrições duras** (ex.: "nunca alterar `kernel/` ou `agent/`"; "ações irreversíveis exigem aprovação"; "respeitar orçamento") · critérios de "boa cidadania".
- **User.md**: nome/contato · contexto · preferências de comunicação · o que valoriza · o que evitar.
- **Goal.md**: objetivo declarado · critério de sucesso mensurável · restrições/escopo · roadmap de marcos · estado atual (link p/ Journal).
- **Journal.md**: log append-only datado: `[data] tipo(decisão|tentativa|resultado|reflexão|bloqueio) — texto`.

---

## 3. O laço do Steward (um ciclo)

Cada vez que o Steward acorda, executa **um ciclo bounded** e morre:

```
  WAKE (tick por tempo OU evento de conclusão)
    │
    ├─ 1. SENSE     lê Goal.md + resumo do Journal + estado de workflows em curso
    │               (context.load + system.workflow.status)
    │
    ├─ 2. REFLECT   "onde estou vs objetivo? o que falhou? falta capacidade?"
    │               (1 chamada LLM, bounded)
    │
    ├─ 3. DECIDE    escolhe UMA próxima ação:
    │                 a) comissionar plano  → planner → (workflow)
    │                 b) acionar evolução    → creator → installer   (aditivo)
    │                 c) pedir decisão/aprovação ao usuário → via Assistant
    │                 d) marcar objetivo concluído / revisar Goal.md
    │                 e) nada a fazer agora → dorme
    │
    ├─ 4. ACT       submete a ação (com checagem de guardrail/orçamento/aprovação)
    │
    ├─ 5. EVALUATE  registra resultado (quando síncrono) ou agenda reavaliação
    │
    └─ 6. JOURNAL   escreve no Journal.md + atualiza Goal.md se preciso → SLEEP
```

Pontos-chave:
- **Um passo por ciclo** (não tenta resolver tudo de uma vez) ⇒ bounded, auditável, barato.
- Execução longa **não fica no Steward**: ele submete ao `workflow` (persistente) e dorme; reavalia no próximo tick ou no evento de conclusão.
- Tudo o que ele "pensa" e decide vira linha no Journal ⇒ rastreável.

---

## 4. Lifecycle do Steward — **pulsante**, não residente

- **Processo efêmero:** spawnado on-demand (como o researcher), faz um ciclo, persiste, morre. Idle-kill cuida do resto. Estado vive em Goal.md/Journal.md/blackboard/`sessions-*.db`.
- **Gatilhos:**
  1. **Evento** (primário): workflow/tarefa terminou ⇒ Steward reavalia.
  2. **Heartbeat lento** (fallback): a cada X (min/horas), **só se houver objetivo ativo**.
  3. **Pedido do usuário**: via Assistant ("como vai o objetivo?", "novo objetivo: ...").
- **Heartbeat sem tocar o kernel:** novo system plugin `projects/Kiwi/system/heartbeat/` que usa `scheduleAtFixedRate` (mesmo padrão do reaper de idle) e publica `steward.tick` quando `Goal.md` tem objetivo ativo. Plugin novo = permitido.
- **Portão KISS:** sem objetivo ativo ⇒ sem tick ⇒ Steward nem sobe. Custo zero em repouso.

> **Por que pulsante resolve "longo prazo":** longo prazo = *estado* persistido (Goal/Journal) + *execução* no workflow persistente + *atenção periódica* do Steward. Nenhum desses exige o Steward vivo o tempo todo.

---

## 5. Fluxos de interação (sequência)

### 5.1 Configuração guiada: bootstrap + revisão

"Onboarding" é só o **caso de primeiro uso** de uma capability mais geral: **configuração
guiada dos arquivos `mind/`**. A mesma máquina cria (1ª vez) e revisa (a qualquer momento)
— um único mecanismo (DRY), diferenciado por um parâmetro de **escopo** (qual arquivo).

| Modo | Quando | Escopo |
|---|---|---|
| **Bootstrap** | 1º boot, `mind/` ausente/incompleto | entrevista completa, cria os 4 arquivos |
| **Revisão** | a qualquer momento, via Assistant | parcial, **um arquivo por vez** |

Disparo da revisão: o Assistant reconhece a intenção ("quero mudar meu objetivo",
"atualize minhas preferências", comando `/config`) e roteia para a mesma capability com o
escopo do arquivo alvo.

**Fluxo bootstrap (primeiro uso):**
```
Boot → Assistant detecta mind/ ausente ou incompleto
     → MODO BOOTSTRAP (entrevista guiada)
     → pergunta ao usuário (quem é você, o que valoriza...) → escreve User.md
     → ajuda a redigir Soul.md → USUÁRIO RATIFICA (assina)
     → coleta o objetivo + critério de sucesso → escreve Goal.md
     → cria Journal.md vazio
     → operação normal
```
Soul é a constituição: o Assistant ajuda a redigir, mas **quem assina é o humano**.

**Regras de reentrada (a revisão NÃO é igual para todo arquivo):**

| Arquivo | Reabrir quando? | Cuidado |
|---|---|---|
| **User.md** | livremente, sempre | risco baixo — só enriquece |
| **Goal.md** | sempre | **dispara reconciliação**: se há workflow em andamento, decidir abortar/terminar/trocar; o Steward re-planeja no próximo ciclo; registrar no Journal |
| **Soul.md** | sempre, **com portão** | constituição: exige intenção explícita + **ratificação humana**; **pausa o Steward** enquanto muda (altera valores/guardrails que ele obedece) |
| **Journal.md** | não via configuração | dono é o Steward; o usuário pode **ler**, não reescrever pela entrevista |

**Fluxo seguro ao revisar Soul/Goal com o Steward ativo (evita *torn read*):**
```
1. QUIESCE  pausa o Steward (não inicia novo ciclo)
2. APPLY    escreve o arquivo sob escritor único (lease/versão — ver §6)
3. JOURNAL  registra ("Goal revisado: X → Y" / "Soul emendado e ratificado")
4. RESUME   Steward re-avalia a partir do novo estado
```
Mudar Goal no meio de um DAG rodando é exatamente a corrida que o lease (§6) resolve.

### 5.2 Usuário → Steward (via Assistant)
```
Usuário (Telegram): "novo objetivo: montar um relatório semanal de cripto"
  → Assistant reconhece intenção de objetivo
  → delega agent.steward {action: set_goal, ...}
  → Steward escreve Goal.md + 1ª entrada no Journal
  → Steward responde via Assistant: "objetivo registrado; plano proposto: [...]; aprovar?"
```

### 5.3 Steward → Usuário (aprovação / status / bloqueio)
```
Steward (no ciclo) decide passo irreversível (ex.: enviar email)
  → NÃO executa direto
  → emite pedido de aprovação → Assistant entrega ao usuário
  → usuário aprova → Assistant → Steward → executa
```

### 5.4 Steward executa um plano (comissiona)
```
Steward → planner (decompõe sub-objetivo em DAG)
        → system.workflow.submit (DAG)        [workflow engine, persistente]
        → dorme
... (workflow roda, possivelmente longo, em paralelo) ...
workflow conclui → evento → acorda Steward → EVALUATE → JOURNAL → próximo passo
```

### 5.5 Auto-evolução — dois gatilhos

A auto-evolução tem **duas portas de entrada**, não uma:

**(a) Por lacuna** (gap-driven, estilo *Voyager*) — falta capacidade para avançar:
```
Steward (REFLECT) detecta: "pro objetivo preciso de tool.crypto.report (não existe)"
  → pede aprovação (v-inicial)  → Assistant → usuário aprova
  → delega ao creator: "gere tool que faz X" (creator projeta + smoke test)
  → plugin-installer valida/compila/instala/spawna
  → Steward verifica catálogo, registra no Journal, retoma o plano
```

**(b) Por experiência** (skills-from-experience, estilo *Hermes* — ver §12) — algo foi
resolvido de forma nova ou **se repetiu** várias vezes via passos ad-hoc:
```
Steward (REFLECT sobre a memória FTS5) percebe: "já montei o relatório de cripto
   manualmente 3x com a mesma sequência" (padrão recorrente no Journal)
  → propõe CRISTALIZAR isso num plugin/skill reutilizável
  → pede aprovação → creator empacota a sequência → installer instala
  → próximas vezes vira 1 capability, não N passos
```

**Quem faz o quê (ambos os gatilhos):** Steward detecta/prioriza/aceita · creator constrói ·
installer implanta. O Steward **não escreve o código** (DRY). A diferença é o gatilho: (a)
olha pra frente (o que falta), (b) olha pra trás (o que já fiz e vale virar capacidade).

---

## 6. Estrutura multithread / concorrência

### O que já existe (substrato suficiente)
- **Isolamento de processo:** cada plugin = processo próprio (spawn on-demand). Steward, Assistant, workflow, tools não disputam memória.
- **Virtual threads:** plugins despacham trabalho em threads virtuais (ex.: Telegram poll loop; Dashboard processa cada frame numa virtual thread).
- **Workflow com camadas paralelas:** DAG já executa tarefas concorrentes e até o fim.
- **Barramento assíncrono + idempotência (por corrId) + blackboard versionado** (`task.context v1/v2/v3`).

⇒ O Steward submeter workflow **não bloqueia** o Assistant: fluxos de evento independentes, `sessionId`/`corrId` distintos.

### O que falta para autonomia **segura** (não é mais thread — é coordenação/freio)
1. **Sessão própria do Steward:** namespace `steward-<goalId>` para blackboard/idempotência/journal não colidirem com `tg-<chatId>`.
2. **Single-writer nos efeitos colaterais:** concorrência do kernel é segura; a do mundo real não. Dois fluxos escrevendo no mesmo arquivo de `workspace/` ou mandando email ⇒ conflito. Filesystem/email não têm versão como o blackboard. Solução: lock/lease ou disciplina de escritor único por recurso.
3. **Teto de concorrência + orçamento:** o spawner on-demand não tem cap visível. DAG autônomo com camada larga pode subir muitos processos e queimar LLM. Adicionar back-pressure/budget (máx. processos simultâneos, máx. chamadas LLM por ciclo/dia, teto de gasto).
4. **Portão de aprovação** para passos irreversíveis.

Tudo isso é construível em `projects/Kiwi/` (plugin de orçamento/lease + lógica no Steward) **sem tocar kernel/agent**.

---

## 7. Governança e guardrails (degraus de autonomia)

| Degrau | O que o Steward pode fazer sozinho | Guardrail necessário |
|---|---|---|
| **D0** | só **propor** (plano/evolução) e esperar você mandar | nenhum (read-only) |
| **D1** | executar passos **reversíveis/read-only** (pesquisa, leitura, rascunho) | sessão própria + budget |
| **D2** | executar passos **irreversíveis** (email, escrita em workspace, install) | **aprovação humana** por passo + single-writer |
| **D3** | **criar capacidade nova** (tool/agent aditivo) via creator | aprovação + sandbox + smoke test + restrição do Soul |
| **D4** | **modificar capacidade existente** (auto-melhoria) | aprovação sempre + teste de regressão (último degrau) |

Restrições duras no **Soul.md** (lidas/obedecidas pelo Steward):
- **nunca** alterar `kernel/` ou `agent/` (sua regra de dev vira guardrail de autonomia);
- ações irreversíveis exigem aprovação;
- respeitar orçamento (passos/LLM/gasto);
- não criar tool que exfiltre segredos do Vault.

---

## 8. Novas capabilities / componentes (todos em `projects/Kiwi/`)

| Componente | Tipo | Onde | Função | Toca kernel/agent? |
|---|---|---|---|---|
| **steward** | agent (persona) | `agents/steward/` | o laço deliberativo; capability `agent.steward` | não (só nova persona) |
| **context** | system plugin | `system/context/` | `context.load` — lê Soul/User/Goal/Journal sob demanda | não |
| **heartbeat** | system plugin | `system/heartbeat/` | `scheduleAtFixedRate` → publica `steward.tick` se há objetivo | não |
| **budget/lease** | system plugin | `system/budget/` | teto de concorrência/gasto + lease de escritor único | não |
| **memory** | system plugin | `system/memory/` | índice FTS5 sobre o Journal (`memory.search` / `memory.append`); curadoria/compressão | não |
| **mind/** | dados | `mind/` | Soul.md, User.md, Goal.md, Journal.md | não |
| **config guiada** | modo no Assistant | persona do assistant | entrevista que cria (bootstrap) e revisa (por escopo) os `.md`; quiesce do Steward p/ Soul/Goal (ver §5.1) | não |

---

## 9. Roadmap incremental (detalhado)

Princípios do roadmap: **cada fase entrega valor sozinha, é reversível, e não habilita o
próximo degrau de autonomia sem o guardrail correspondente.** Cada fase tem critério de
aceite **testável headless** (via `dev/Dev.java --invoke/--prompt` + `Stop.java`). Esforço é
relativo (P/M/G); risco é o de *operação* (não de implementação).

### Visão geral

| Fase | Entrega | Degrau | Depende de | Esforço | Risco |
|---|---|---|---|---|---|
| **0** | Sementes `mind/` + leitura sob demanda | — | — | M | baixo |
| **1** | Steward que **propõe** | D0 | 0 | M | baixo |
| **2** | Memória pesquisável (FTS5) + reflexão | D0 | 1 | M | baixo |
| **3** | Heartbeat + execução **reversível** | D1 | 1,2 | G | médio |
| **4** | Execução **irreversível** c/ aprovação | D2 | 3 | M | alto |
| **5** | Auto-evolução **aditiva** (2 gatilhos) | D3 | 2,4 | G | alto |
| **6** | Interop & alcance (MCP, multi-provider, canais, workspace isolado) | — | transversal | G | médio |
| **7** | Auto-**melhoria** (modificar existente) | D4 | 5 | G | muito alto |

---

### Fase 0 — Sementes + leitura sob demanda
- **Objetivo:** existir identidade/estado em disco e ser lido sem bloat. Valida a tese com risco zero.
- **Entregáveis:** `mind/` com templates dos 4 `.md`; system plugin `context` (`context.load`); modo **config guiada (bootstrap)** no Assistant; Assistant passa a **usar User.md** e a **citar Soul** ao responder identidade.
- **Capabilities/eventos:** `context.load {scope}`.
- **Guardrails novos:** nenhum (tudo read-only/co-autorado com o humano).
- **Critério de aceite:** `mind/` ausente ⇒ Assistant entra em bootstrap e grava os 4 arquivos; pergunta "quem é você?" reflete o Soul; uma preferência dita pelo usuário aparece no User.md e muda a resposta seguinte.
- **Inspiração:** modelagem de usuário do Hermes (§12).

### Fase 1 — Steward que propõe (D0)
- **Objetivo:** introduzir o decisor sem deixá-lo agir. Só pensa e propõe.
- **Entregáveis:** persona `agents/steward/` (usa o runtime `agent/` existente); capability `agent.steward` roteável pelo Assistant; fluxos 5.2 e 5.4 **em modo proposta** (Steward → planner → *plano em texto*, sem submeter workflow).
- **Capabilities/eventos:** `agent.steward {action: set_goal|status|propose}`.
- **Guardrails novos:** nenhum (não executa nada).
- **Critério de aceite:** `--prompt "novo objetivo: X"` grava Goal.md + 1ª entrada no Journal; `--prompt "como vai o objetivo?"` devolve estado + plano proposto; nenhum workflow é submetido.
- **Inspiração:** laço deliberativo (AutoGPT/OpenHands) em versão *propose-only*.

### Fase 2 — Memória pesquisável + reflexão
- **Objetivo:** dar ao Steward memória que escala (pré-requisito de qualquer autonomia útil).
- **Entregáveis:** system plugin `memory` (FTS5 sobre `data/steward-memory.db`); SENSE/REFLECT passam a **recuperar por relevância** + resumo rolante; rotina de **curadoria/compressão** na reflexão.
- **Capabilities/eventos:** `memory.append`, `memory.search {query, k}`.
- **Guardrails novos:** escritor único do par Journal↔FTS (lease lógico básico).
- **Critério de aceite:** após N entradas, `memory.search "cripto"` retorna só as relevantes; o contexto enviado ao LLM no REFLECT **não** cresce com o tamanho do Journal (medir tokens).
- **Inspiração:** memória curada + FTS5 + "periodic nudges" do Hermes (§12).

### Fase 3 — Heartbeat + execução reversível (D1)
- **Objetivo:** primeira autonomia real, limitada a passos sem efeito colateral.
- **Entregáveis:** system plugin `heartbeat` (`steward.tick` se há objetivo ativo); **sessão própria** `steward-<goalId>`; system plugin `budget` (teto de processos/LLM/gasto por ciclo e por dia); Steward **submete workflows reversíveis** (pesquisa, leitura, rascunho em scratch) e reavalia por evento.
- **Capabilities/eventos:** `steward.tick`; `budget.check/consume`.
- **Guardrails novos:** budget + gate "só objetivo ativo gera tick" + lista de ações classificadas como reversíveis.
- **Critério de aceite:** com objetivo ativo, o tick acorda o Steward, ele roda 1 ciclo, submete um workflow read-only, jornaliza e dorme; sem objetivo, **zero** ticks; estourar o budget **interrompe** o ciclo com registro.
- **Inspiração:** cron embutido + subagentes isolados do Hermes; camadas paralelas do workflow já existente.

### Fase 4 — Execução irreversível com aprovação (D2)
- **Objetivo:** permitir efeitos colaterais (email, escrita em `workspace/`, install) com humano no laço.
- **Entregáveis:** **portão de aprovação** (fluxo 5.3) com timeout/expiração; **single-writer/lease** por recurso (arquivo/recurso externo); classificação reversível vs irreversível aplicada antes de ACT.
- **Capabilities/eventos:** `steward.approval.request/grant/deny`; `lease.acquire/release`.
- **Guardrails novos:** nenhuma ação irreversível sem aprovação válida e lease adquirido.
- **Critério de aceite:** passo irreversível **bloqueia** aguardando aprovação; negado ⇒ não executa e jornaliza; dois fluxos disputando o mesmo arquivo ⇒ um espera o lease (sem corromper).
- **Inspiração:** isolamento por sessão/workspace (OpenClaw, §12).

### Fase 5 — Auto-evolução aditiva (D3)
- **Objetivo:** fechar o loop "evoluir para atingir o objetivo" — **só criando capacidade nova**.
- **Entregáveis:** ambos os gatilhos do §5.5 ativos — **(a) por lacuna** e **(b) por experiência** (cristalização de padrões recorrentes detectados na memória FTS5); integração Steward → `creator` → `plugin-installer`, com aprovação.
- **Capabilities/eventos:** reusa `tool.plugin.install` + delegação ao creator.
- **Guardrails novos:** aprovação obrigatória; sandbox + smoke test (já no pipeline do creator); restrição do Soul ("nunca `kernel/`/`agent/`; não exfiltrar segredos do Vault"); novo plugin entra com **capabilities mínimas**.
- **Critério de aceite:** dado um objetivo que exige tool inexistente, o Steward propõe, (após aprovação) o creator gera, o installer instala, e o Steward **usa** a nova capability no ciclo seguinte; um padrão repetido 3× dispara proposta de cristalização.
- **Inspiração:** Voyager (lacuna) + Hermes skills-from-experience (§12).

### Fase 6 — Interop & alcance (transversal/opcional)
- **Objetivo:** ampliar capacidade e canais sem reescrever o núcleo. Pode rodar em paralelo às fases 3–5.
- **Entregáveis (cada um independente):**
  - **MCP bridge** (`system/mcp/`): consumir servidores MCP externos como tools (e, opcional, expor tools do Kiwi como MCP).
  - **Provider abstraction:** sair de só `NVIDIA_API_KEY` para multi-provider (fallback/custo).
  - **Channel contract** (`channel.*`): generalizar Telegram → WhatsApp/Discord/CLI como plugins.
  - **Workspace isolado por sessão/agente:** scratch próprio do Steward, merge deliberado no `workspace/` do usuário.
- **Guardrails novos:** MCP externo entra como capability sem privilégio de Vault; canais herdam o mesmo gate de aprovação.
- **Critério de aceite:** um tool MCP externo aparece no catálogo e é invocável; trocar de provider não muda o comportamento dos agentes.
- **Inspiração:** MCP/multi-provider do Hermes; gateway multi-canal e isolamento do OpenClaw (§12).

### Fase 7 — Auto-melhoria (D4)
- **Objetivo:** o degrau mais perigoso — Steward **modifica capacidade existente** (ex.: melhorar um tool).
- **Entregáveis:** pipeline de mudança com **teste de regressão** obrigatório; diff revisável; rollback automático.
- **Guardrails novos:** **aprovação sempre**; só passa se a suíte de regressão da capability passar; nunca toca `kernel/`/`agent/`.
- **Critério de aceite:** uma melhoria proposta só é instalada se os testes da capability antiga continuarem verdes; falha ⇒ rollback + registro.
- **Inspiração:** Hermes "improves skills during use" — porém aqui **gated** ao máximo.

> **Descartado deste roadmap (ver §12):** `execute_code` (execução de código arbitrário pelo agente — risco x autonomia), multi-canal completo/nós mobile (escopo), e o padrão de *sharing* de skills (agentskills.io). Reavaliáveis no futuro.

---

## 10. Riscos e mitigações

| Risco | Mitigação |
|---|---|
| Runaway loop / goal drift | um passo por ciclo · budget · heartbeat lento · gate por objetivo ativo |
| Explosão de tokens (Journal) | reflexão/compressão; nunca injetar Journal inteiro |
| Conflito de escrita (workspace/email) | single-writer/lease; aprovação para irreversíveis |
| Regressão por auto-modificação | D4 por último, sempre com aprovação + teste |
| Custo de LLM descontrolado | teto por ciclo/dia; budget plugin |
| Identidade incoerente entre agentes | fonte única `.md` + `context.load` (DRY) |

---

## 11. Decisões em aberto (para você)

1. **Gatilho do Steward na v1:** só pedido do usuário, ou já com heartbeat lento?
2. **Soul.md:** você redige do zero ou quer que eu gere um rascunho pra ratificar?
3. **Local dos arquivos:** `mind/` único, ou separar `identity/` (Soul/User) de `state/` (Goal/Journal)?
4. **planner separado vs inline no Steward:** reuso (DRY) vs menos peças (KISS).
5. **Onde começamos a implementar:** Fase 0 inteira, ou só `mind/` + `context.load` primeiro?

---

## 12. Inspirações externas (OpenClaw / Hermes)

Referências trazidas pelo usuário: <https://docs.openclaw.ai/> e
<https://hermes-agent.nousresearch.com/docs/>. Leitura honesta do que cada um é e do que vale
importar.

### O que cada um é
- **OpenClaw** — um **gateway** self-hosted que conecta *muitos apps de chat* (Discord, Slack,
  WhatsApp, iMessage, Signal, Telegram, Matrix...) a agentes de código. Foco nas **bordas**
  (I/O multi-canal, roteamento de sessão, isolamento por remetente, superfícies de controle:
  Web UI/CLI/mobile). **Não** é um framework de objetivo/autonomia.
- **Hermes (Nous Research)** — um **agente autônomo auto-melhorável**. Learning loop que **cria
  skills a partir da experiência e as melhora durante o uso**; memória persistente curada com
  **busca full-text (FTS5) + sumarização** e modelagem do usuário; **cron embutido**; subagentes
  isolados em paralelo; **MCP**; multi-provider. É quase um espelho do Kiwi-Steward.

> **Posição do Kiwi:** o **kernel do Kiwi já é o "gateway"** dos dois (capability routing +
> isolamento de processo) — fundação mais limpa que o gateway monolítico do OpenClaw. As lacunas
> são **largura de bordas** (OpenClaw) e **maturidade de cérebro** (Hermes), não fundação.

### Comparativo

| Dimensão | Kiwi (hoje/projetado) | OpenClaw | Hermes |
|---|---|---|---|
| Gateway / roteamento | kernel + capabilities | gateway central | gateway unificado |
| Multi-canal | Telegram + console | **forte** (10+ apps) | 20+ apps |
| Memória | Journal + FTS5 (projetado) | sessão por remetente | **forte** (FTS5 + sumarização) |
| Skills / auto-evolução | creator + installer (+ §5.5) | plugins de canal | **forte** (skills-from-experience) |
| Autonomia / cron | heartbeat (projetado) | autonomia p/ código | **forte** (cron + subagentes) |
| MCP | — (Fase 6) | — | sim |
| Multi-provider | 1 provider (Fase 6) | — | sim |
| Isolamento workspace | 1 compartilhado (Fase 6) | **por agente** | por subagente |

### Decisão: adotar agora / roadmap / descartar

**Adotar (já dobrado no design):**
- **Memória FTS5 + curadoria** (Hermes) → §2 e **Fase 2**. Encaixe perfeito: SQLite já existe, FTS5 é nativo. Resolve o bloat do Journal.
- **Skills a partir da experiência** (Hermes) → §5.5(b) e **Fase 5**. Eleva o loop de "preencher lacuna" para "cristalizar o que já funciona".
- **Cron/heartbeat** (Hermes) → §4 e **Fase 3**. Valida o gatilho pulsante.

**Roadmap (Fase 6, quando fizer sentido):**
- **MCP bridge** (Hermes) — capacidade externa de graça.
- **Multi-provider** (Hermes) — resiliência/custo.
- **Channel contract** (OpenClaw) — multi-canal como plugin.
- **Workspace isolado por agente** (OpenClaw) — reforça o single-writer do §6.

**Descartar por ora (reavaliável):**
- **`execute_code`** (Hermes) — execução de código arbitrário pelo agente: poderoso, mas superfície de ataque grande demais junto com autonomia + auto-evolução. Só com sandbox forte, no futuro.
- **Nós mobile / 10+ canais simultâneos** (OpenClaw) — escopo fora do objetivo atual.
- **Sharing de skills (agentskills.io)** (Hermes) — portabilidade é bom futuro, prematuro agora.

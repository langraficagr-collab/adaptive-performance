# 1.11.10 — monitoramento econômico e validação mais rigorosa

- Monitoramento normal com tela ativa: intervalo mínimo de 120 s. Risco térmico/pressão elevada: 60 s. Diagnóstico explícito pode usar 30 s. Tela apagada: pelo menos 10 min. Os eventos de tela/energia/temperatura continuam podendo antecipar uma leitura, sem manter wake lock ou alarme periódico.
- Removida a aceleração automática para 30 s enquanto o ajuste conservador coleta evidência. Eventos térmicos também antecipam a recuperação após esfriar.
- Custo do otimizador inclui CPU do app, CPU da ponte e CPU dos filhos shell já recolhidos, quando disponível. Número, duração e timeouts dos comandos são contabilizados sem iniciar novos comandos. Reinício/desconexão invalida a comparação remota. Latência é uma métrica de carga, não CPU nem energia. Trabalho provocado dentro de serviços do sistema ainda não é integralmente atribuível.
- Referência e candidato exigem 30 min e pelo menos 12 amostras, espaçadas por 120 s, com prazo máximo de 45 min. Ganho provisório exige redução de potência de pelo menos 7% superior a uma margem conservadora do ruído observado. Essa margem não prova independência estatística ou causalidade. A porcentagem inteira de bateria não aprova economia.
- Acompanhamento posterior mantém reversão imediata e revisão em 6–24 h. Confirmação exige 12 leituras comparáveis. Janelas antigas de dois minutos são descartadas e candidatos antigos pendentes são revertidos na migração.
- Recorrências avançam no máximo a cada cinco minutos; leituras intermediárias não empurram o relógio da contagem. Mudança de causa, expiração e retrocesso do relógio são tratados.
- Fluidez da interface do próprio app usa o prazo de quadro informado pelo Android ou a taxa atual da tela (60/90/120 Hz e outras taxas válidas). A coleta termina após 10 s mesmo sem novos quadros.
- Mantidos pré-carregamento opcional desativado por padrão, limpeza somente sob pressão e gerenciamento térmico nativo.

## Validação

`sh tests/monitoring/run.sh` cobre migração/preload, cadência normal/térmica/tela apagada, orçamento de quadros, ruído energético, recorrência, custo remoto/reconexão e reversões por CPU/RAM/calor/contexto. Os relógios dos testes são simulados; o teste de 24 h não representa uma medição real de autonomia.

Compilação e Android Lint: `:app:assembleFullDebug :app:lintFullDebug`.

# English

Normal monitoring now has a 120-second minimum interval, with 60-second thermal/pressure monitoring and a 10-minute minimum while the display is off. Native events can still request an earlier reading. Cost accounting includes the privileged bridge and reaped shell children when observable, plus shell latency and timeouts; it does not claim complete attribution of system-service CPU or battery energy. Provisional A/B decisions require 30 minutes, at least 12 spaced readings and a reduction larger than the measured noise margin. Final 24-hour validation requires 12 matched observations. The recurrence timer and refresh-rate-aware UI frame sampling are corrected. Existing opt-in preload and pressure-only cleanup safeguards remain.

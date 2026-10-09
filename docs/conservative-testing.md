# Conservador 1.0.3 — testes automáticos

Cada teste usa 15 minutos de referência e 15 minutos com uma única opção alterada. Após rejeição ou interrupção a opção retorna ao valor anterior, a restauração é processada e uma nova referência é exigida. A janela é verificada no próximo ciclo do serviço; Android pode atrasá-lo em suspensão. Não há garantia de timer exato com o aparelho dormindo.

Somente uma redução medida de energia sem regressão nas métricas aprova a opção. CPU, temperatura da bateria/SoC, RAM e energia são obrigatórios; com tela ativa também é exigida uma leitura recente de quadros. Falta de dados não é aprovação. As medições dependem do uso real e não provam causalidade ou economia futura.

Dependências não aprovadas são registradas como não testadas, sem ligar vários módulos ao mesmo tempo. Pré-carga permanece independente, com até 2 APKs/15 min e até 32 MiB.

## Cobertura da imagem

| Controle | Tratamento |
|---|---|
| Auto-Reparo com medição antes/depois | Desativado / fora do teste reversível |
| Pontuação de eficácia e histórico | Teste individual de 15 min |
| Perfis por aplicativo | Teste individual de 15 min |
| Proteção contra otimização excessiva | Proteção fixa |
| RAM emergencial: <8% livre ou <15% + PSI ≥25 | Desativado / fora do teste reversível |
| Diagnóstico na inicialização | Desativado / fora do teste reversível |
| Aprender padrões por horário e uso | Teste individual de 15 min |
| Exigir confiança mínima antes de Auto-Reparo | Proteção fixa |
| Detectar recidiva e variar estratégia | Proteção fixa |
| Observar 60 s após correções antes de agir novamente | Proteção fixa |
| Detectar regressão de consumo após atualização de apps | Teste individual de 15 min |
| Modo somente diagnóstico: não alterar o sistema | Desativado / fora do teste reversível |
| Reduzir a tela para 60 Hz somente com aquecimento | Proteção fixa |
| Liberar processos em cache quando RAM livre < 7% | Desativado / fora do teste reversível |
| Compactação automática de RAM | Desativado / fora do teste reversível |
| Modo automático geral: RAM + PSI + temperatura + bateria | Proteção fixa |
| Limpeza automática com pouco armazenamento e tela apagada | Desativado / fora do teste reversível |
| Congelamento adaptativo de apps ociosos/restritos | Desativado / fora do teste reversível |
| Detectar processos que reiniciam repetidamente | Teste individual de 15 min |
| Modo automático de bateria baixa (≤25%) | Teste individual de 15 min |
| Proteção contra thrashing da ZRAM/swap | Proteção fixa |
| Usar PSI real de CPU, memória e I/O nas decisões | Teste individual de 15 min |
| Detector de vazamento de RAM por tendência | Teste individual de 15 min |
| Controle térmico adaptativo gradual | Teste individual de 15 min |
| Limitar apps em segundo plano com RAM excessiva (limite individual suportado) | Desativado / fora do teste reversível |
| Modo RAM agressivo: agir quando RAM livre < 20% | Desativado / fora do teste reversível |
| Finalizar apps anormais travados em segundo plano | Desativado / fora do teste reversível |
| Restringir apps sem uso há mais de 3 dias | Desativado / fora do teste reversível |
| Reduzir segundo plano quando CPU/VM/energia indicarem pressão | Teste individual de 15 min |
| Economia profunda e segura quando a tela estiver desligada | Desativado / fora do teste reversível |
| Detectar wakeups excessivos e loops de rede | Desativado / fora do teste reversível |
| Saúde avançada: jank + LMKD + zRAM + Jobs | Proteção fixa |
| Autocalibrar limites conforme o comportamento do aparelho | Desativado / fora do teste reversível |
| Aprender temperatura, CPU, RAM e energia de cada app | Teste individual de 15 min |
| Modo anti-travamento preventivo | Teste individual de 15 min |
| Guardar histórico de saúde das últimas 24 horas | Teste individual de 15 min |
| Rollback automático de restrições se piorarem a fluidez | Proteção fixa |
| Conter apps em loop de crash/reinício | Desativado / fora do teste reversível |
| Diagnóstico estendido: ANR + Binder + GPU + sensores | Teste individual de 15 min |
| Modo diagnóstico de 10 min ao detectar anomalia | Teste individual de 15 min |
| Ajustar agressividade conforme estabilidade e consumo | Desativado / fora do teste reversível |
| Teste A/B interno após autocalibração | Desativado / fora do teste reversível |
| Monitorar sensores, GPS, câmera, microfone e modem | Teste individual de 15 min |
| Monitorar saturação de armazenamento/I/O | Teste individual de 15 min |
| Detectar serviços de automação duplicados | Teste individual de 15 min |
| Monitorar deep sleep e Doze com a tela apagada | Teste individual de 15 min |
| Reduzir brilho temporariamente em aquecimento forte | Teste individual de 15 min |
| Economia sistêmica adaptativa | Teste individual de 15 min |
| Deep idle: pausar monitoramento e liberar Doze | Desativado / fora do teste reversível |
| Economizar scans Wi-Fi e dados paralelos em repouso | Teste individual de 15 min |
| Gerenciar exceções do Doze automaticamente | Teste individual de 15 min |
| Timeout da tela adaptativo (até 3 min em uso comum) | Teste individual de 15 min |
| Máxima economia: desativar dois-toques no repouso | Teste individual de 15 min |
| Forçar modo seguro dos módulos avançados | Desativado / fora do teste reversível |
| Ativar congelamento manual dos apps selecionados | Desativado / fora do teste reversível |
| Notificar somente problemas que não puderem ser resolvidos | Teste individual de 15 min |

Sliders de RAM, perfil ZRAM, atraso de congelamento e botões de navegação/seleção não são experimentos booleanos. Nesta edição ficam ocultos junto com os controles avançados. Limpeza de cache, encerramento de processos e congelamento não são executados como experimentos porque não há restauração fiel do estado anterior. Autocalibração, A/B paralelo e deep idle ficam fora da fila para não alterar outros parâmetros ou interromper as leituras.

## Verificação

Compilação: gradle :app:assembleConservativeDebug --no-daemon.

Harness JVM com SharedPreferences em memória: tests/conservative-harness. Cobre regressão de calor/CPU/RAM/quadros, perda de leitura, tela, carregamento, prazo, reinício, parada do serviço, dependência não aprovada e benefício mensurável.

A instalação e o início do serviço são verificados separadamente; o harness não mede bateria real.

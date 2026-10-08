# Adaptive Performance

<p align="center"><img src="docs/branding/banner.jpg" alt="Adaptive Performance" width="100%"></p>

**Adaptive Performance** é um utilitário avançado para Android focado em desempenho adaptativo, controle térmico, gerenciamento de RAM/CPU, economia de bateria, limpeza de armazenamento, congelamento de aplicativos, diagnóstico do sistema, firewall DNS por aplicativo e otimização de sinal móvel.

O projeto foi desenvolvido principalmente para Android 16 e usa **Shizuku** para executar operações privilegiadas sem exigir root.

[Site oficial](https://langraficagr-collab.github.io/adaptive-performance/) · [Baixar a versão mais recente](https://github.com/langraficagr-collab/adaptive-performance/releases/latest)

## Idiomas / Languages

[🇧🇷 Documentação em português](README.md) · [English documentation](README.en.md) · [Site em português](https://langraficagr-collab.github.io/adaptive-performance/) · [Website in English](https://langraficagr-collab.github.io/adaptive-performance/en.html)

## Novidades da versão 1.9.3 — português / English

O Adaptive Performance agora permite trocar entre **Português** e **English** diretamente na tela inicial, sem alterar o idioma do Android. A preferência fica salva.

No **modo Automático**, ele mede uma referência por **2 minutos** e ativa **uma função reversível por vez**, acompanhando o resultado por mais **2 minutos**. Compara consumo elétrico estimado, CPU, RAM disponível, temperatura e fluidez (quando os sensores fornecem dados confiáveis). **Só mantém ajustes que comprovem melhoria sem regressão; os piores ou inconclusivos são desativados ou revertidos**. Testes são pausados em condições de risco, como aquecimento, carregamento e bateria baixa. Recursos sem restauração segura não entram nessa avaliação.

> **Limitação:** duas janelas curtas são indicadores comparativos, não garantia de aumento da autonomia. O resultado depende do aparelho e da carga de trabalho.

**English:** Adaptive Performance 1.9.3 adds an in-app **English / Português** language selector. In **Automatic** mode, it measures a **2-minute baseline**, enables **one reversible setting**, and observes it for **another 2 minutes**. It compares estimated power draw, CPU load, available RAM, temperature, and responsiveness. Only measurable improvements are kept; regressions and inconclusive trials are rolled back. Unsafe or irreversible changes are excluded. See the [complete English guide](README.en.md).

**APK:** [Baixar versão 1.9.3 / Download 1.9.3](https://github.com/langraficagr-collab/adaptive-performance/releases/tag/v1.9.3).

## Visão geral

O aplicativo monitora continuamente o estado do aparelho e tenta agir somente quando há necessidade real. As decisões podem considerar temperatura, memória disponível, pressão PSI, uso de CPU, swap/zRAM, bateria, atividade em segundo plano, estabilidade, wakeups e outros indicadores.

A proposta é reduzir aquecimento, consumo e travamentos sem aplicar restrições agressivas de forma permanente. Sempre que possível, o Adaptive Performance guarda o estado anterior e permite rollback ou restauração.

## Modos Automático e Avançado

A versão 1.9.3 acrescenta suporte a português/inglês e testes sequenciais reversíveis de 2 minutos no modo Automático, preservando o pré-carregamento inteligente de aplicativos e a manutenção segura de armazenamento.

### Automático — economia com aprendizado

- Escolhe as configurações automaticamente com prioridade para economia de bateria e fluidez.
- Aprende com o padrão real de uso do aparelho e identifica aplicativos usados com frequência.
- Não protege nem congela aplicativos automaticamente; o modo Automático mantém o foco no pré-carregamento e respeita as seleções manuais.
- Considera bateria, temperatura, CPU, RAM, potência estimada, estado da tela e estabilidade.
- Avalia sequencialmente opções reversíveis elegíveis, com referência de 2 minutos e observação de 2 minutos por ajuste; operações irreversíveis ou sem restauração segura não entram nos testes.
- Compara energia, uso de CPU, RAM livre, temperatura e fluidez, quando os indicadores são confiáveis; testes ruins ou inconclusivos são revertidos.
- Mantém apenas ajustes com melhoria mensurável sem regressão relevante; a observação curta não garante autonomia maior em longo prazo.
- Pode aumentar a economia quando a bateria está baixa, o aparelho aquece ou a tela permanece desligada.
- Mantém sempre ativas as proteções críticas de estabilidade, rollback, Auto-Reparo e segurança térmica.
- Permite reiniciar todo o aprendizado a qualquer momento.
- Oculta a maior parte dos controles técnicos para deixar a interface mais simples.

### Pré-carregamento inteligente

- A cada 15 minutos, analisa somente os aplicativos usados na janela recente.
- Seleciona até quatro apps elegíveis e exibe no painel quais estão mantidos no ciclo.
- Pré-carrega APK principal, splits e conteúdos selecionados de Android/data, OBB e mídia.
- Define o orçamento conforme RAM livre, temperatura do SoC e temperatura da bateria.
- Em modo automático, limita o orçamento a 64 MB quando a bateria está em 50% ou menos; reduz para 32 MB abaixo de 30% e pausa abaixo de 15%.
- Pausa ou reduz o pré-carregamento quando há pouca RAM ou aquecimento, sem congelar os aplicativos.

### Avançado — controle completo

- Exibe todas as opções e ferramentas do Adaptive Performance.
- Permite configurar manualmente proteção térmica, memória, zRAM, CPU, bateria, limpeza, congelamento, perfis de apps, diagnósticos e demais recursos disponíveis.
- Indicado para quem prefere controlar individualmente o comportamento do sistema.

Ao atualizar uma instalação existente, o aplicativo preserva o comportamento/configurações já utilizados. Novas instalações podem começar pela experiência automática simplificada.


## Histórico da versão 1.9.2

- Novo motor de autocalibração por testes A/B no modo Automático.
- Seleção automática dos melhores níveis por parâmetro com base em bateria, temperatura, CPU, RAM e potência estimada.
- Pré-carregamento automático de até quatro apps usados nos últimos 15 minutos, com lista visível no painel.
- Reinício completo do ciclo de aprendizado sempre que o usuário troca do modo Avançado para o Automático.
- Monitor de saúde do armazenamento executado a cada hora.
- Detecção de F2FS, GC, discard, gc_merge e ATGC.
- Manutenção/TRIM somente em repouso e em condições térmicas seguras.
- Economia automática abaixo de 50% da bateria e resposta térmica com menor carga de diagnósticos e manutenção.
- Versão Android: **1.9.2-auto-preload** (versionCode 71).

## Prints do aplicativo

| Painel principal | Ações inteligentes |
|---|---|
| <img src="docs/screenshots/01-home.png" alt="Painel principal do Adaptive Performance" width="300"> | <img src="docs/screenshots/02-smart-actions.png" alt="Ações inteligentes do Adaptive Performance" width="300"> |

| Limpeza avançada | Otimizador de sinal móvel |
|---|---|
| <img src="docs/screenshots/03-cleanup.png" alt="Tela de limpeza avançada" width="300"> | <img src="docs/screenshots/04-signal-optimizer.png" alt="Otimizador automático de sinal móvel" width="300"> |

Capturas reais da versão instalada e testada. Valores, sensores e opções disponíveis podem variar conforme aparelho, ROM, operadora e versão do Android.

## Monitoramento em tempo real

- Temperatura da bateria e sensores térmicos disponíveis.
- Uso e pressão de CPU.
- RAM total, livre e disponível.
- PSI de CPU, memória e I/O.
- Swap/zRAM e detecção de thrashing.
- LMKD e eventos ligados à pressão de memória.
- Estado da bateria, carregamento e consumo.
- Wakeups, deep sleep e comportamento em repouso.
- Histórico de saúde das últimas 24 horas.
- Diagnósticos estendidos de ANR, Binder, GPU, sensores e outros componentes quando disponíveis.

## Motor adaptativo

- Modo automático geral baseado em RAM, PSI, temperatura e bateria.
- Ajuste de agressividade conforme estabilidade e consumo.
- Autocalibração dos limites.
- Aprendizado de comportamento por aplicativo e horário.
- Detecção de recorrência de problemas.
- Auto-Reparo com medição antes/depois.
- Rollback automático quando uma otimização piora a fluidez.
- Proteção contra otimização excessiva.
- Modo anti-travamento preventivo.
- Diagnóstico temporário ao detectar anomalias.

## RAM, zRAM e memória

- Compactação de memória com limite configurável.
- Perfis Normal, Máxima, Extrema e Automático.
- Perfil automático baseado em RAM livre e temperatura.
- Monitoramento de swap/zRAM.
- Proteção contra thrashing.
- Limite de RAM para apps em segundo plano.
- Suporte a limites específicos por aplicativo.
- Detector de crescimento anormal/vazamento de RAM.
- Ações progressivas antes de aplicar force-stop.

O algoritmo real usado pelo zRAM depende do kernel e das permissões disponíveis no aparelho. O app não afirma alterar um algoritmo que o sistema não permita modificar.

## CPU e aplicativos em segundo plano

- Detecção de apps com uso excessivo de CPU.
- Restrição progressiva de segundo plano.
- Perfis Econômico, Balanceado e Desempenho por aplicativo.
- Lista de exceções para apps que nunca devem ser limitados automaticamente.
- Proteção do app atualmente em primeiro plano.
- Detecção de processos que reiniciam repetidamente.
- Contenção de loops de crash/reinício.
- Restauração de estados anteriores quando uma restrição é removida.

## Congelamento manual

É possível selecionar manualmente quais aplicativos devem permanecer totalmente parados quando não estão sendo usados.

- Aplica force-stop ao app selecionado.
- Mantém o aplicativo parado em segundo plano.
- O app abre normalmente quando o usuário toca no ícone.
- Ao sair dele, o Adaptive Performance volta a encerrá-lo.
- A seleção permanece salva após fechar ou reiniciar o Adaptive Performance.
- O congelamento manual tem prioridade sobre restrições automáticas concorrentes.
- Apps críticos e protegidos são filtrados por regras de segurança.

## Controle térmico

- Monitoramento contínuo de temperatura.
- Níveis graduais de proteção térmica.
- Redução de agressividade quando o aparelho aquece.
- Pausa de certas ações em temperatura elevada.
- Retomada somente depois de resfriar.
- Previsão de aquecimento com base em tendência.
- Controle temporário de brilho em aquecimento forte.
- Integração com regras de CPU, memória e segundo plano.

## Economia de bateria e repouso

- Economia sistêmica adaptativa.
- Modo automático de bateria baixa.
- Deep idle e monitoramento de deep sleep.
- Redução de atividades em segundo plano no repouso.
- Economia de scans Wi-Fi e tarefas paralelas quando aplicável.
- Gerenciamento de exceções do Doze.
- Detecção de wakeups excessivos.
- Timeout de tela adaptativo.
- Modo de máxima economia opcional.

## Limpeza de armazenamento

A aba **Limpeza** permite analisar e remover categorias de arquivos sem apagar automaticamente documentos pessoais.

- Cache de aplicativos e do sistema quando permitido.
- Miniaturas recriáveis.
- Downloads temporários ou incompletos antigos.
- Instaladores APK antigos.
- Diagnósticos antigos.
- Pastas vazias em Downloads.
- Logs e relatórios antigos acessíveis.
- Arquivos .log, .bak e .old antigos.
- Resíduos temporários de editores.
- Caches temporários de compilação do Android quando suportado.
- Análise do espaço ocupado antes da limpeza.
- Persistência das opções escolhidas.

### Arquivos grandes

- Procura arquivos grandes em Downloads.
- Mostra tamanho e nome antes de excluir.
- Permite selecionar individualmente o que apagar.
- Exige confirmação.
- O serviço só aceita caminhos encontrados pela própria varredura e limitados à área analisada.

### Apps sem uso há mais de 15 dias

- Analisa o histórico real de uso do Android.
- Recomenda apps sem uso há mais de 15 dias.
- Ignora apps de sistema e componentes protegidos.
- Exibe há quanto tempo cada app não é utilizado.
- A remoção não é automática: o Android abre a tela oficial de desinstalação.

### Manutenção / TRIM e saúde do armazenamento

- Monitora o armazenamento automaticamente a cada hora.
- Detecta o sistema de arquivos e, em F2FS, verifica GC em segundo plano, gc_merge, discard e ATGC quando disponíveis.
- Compara alterações de espaço livre entre leituras para decidir se há necessidade real de manutenção.
- Executa a manutenção oficial do Android/TRIM somente quando o aparelho estiver em repouso, com temperatura segura e energia suficiente.
- Evita rodar fsck em /data montado; se houver sinal de erro de integridade, recomenda verificação offline.
- Aciona rotinas de manutenção compatíveis com Android/F2FS em vez de desfragmentação tradicional de HDD.
- Exibe progresso estimado quando o Android não fornece percentual real.
- Permite acompanhamento e interrupção segura quando aplicável.

## Firewall DNS AdGuard por aplicativo

A versão 1.6.0 adicionou um firewall DNS seletivo baseado em VpnService.

- Usa os servidores filtrantes públicos do AdGuard DNS:
  - 94.140.14.14
  - 94.140.15.15
- Permite escolher exatamente quais aplicativos serão filtrados.
- Apps marcados usam AdGuard DNS.
- Apps desmarcados continuam usando a rede normal.
- Botões para filtrar todos ou deixar todos livres.
- Somente consultas DNS dos apps selecionados passam pelo túnel local.
- O restante do tráfego não é tunelado.
- A seleção fica salva.
- Pode retomar após reinicialização quando a autorização VPN já existe.
- Possui fallback seguro caso o serviço DNS/VPN pare.

Apps que usam DNS-over-HTTPS ou DNS próprio podem ignorar a filtragem DNS.

## Otimizador automático de sinal móvel

A versão 1.7.0 adicionou monitoramento e otimização da tecnologia da rede celular usando comandos de telefonia via Shizuku.

- Monitora nível de sinal e dBm/RSRP.
- Só considera agir depois de leituras realmente fracas.
- Exige confirmação de sinal baixo antes de trocar a tecnologia.
- Pode comparar 5G+4G, 4G/LTE, 3G/WCDMA/HSPA e 2G/GSM/EDGE.
- 5G só entra quando NR já está permitido no perfil do SIM.
- Nunca habilita uma tecnologia que não estava permitida no perfil original.
- Confirma a queda de sinal em cerca de 10 s e testa cada modo por 10 s.
- Mantém o modo com melhor resultado.
- Não troca a rede durante chamadas.
- Verifica por padrão a cada 1 min e usa cooldown configurável de 5 a 120 min para evitar alternância constante.
- Verifica o bitmask depois de cada mudança.
- Restaura o perfil original se o Android devolver um resultado inesperado.
- Possui botão para restaurar manualmente a configuração original.

A disponibilidade real de 2G/3G/4G/5G depende do aparelho, ROM, SIM e operadora.

### Economia opcional de GPS

A tela do otimizador de sinal permite limitar atualizações de localização em segundo plano enquanto a tela está apagada. O recurso eleva o intervalo do sistema para 15 minutos e restaura o valor anterior ao acender a tela, conectar o carregador, desligar a opção ou encerrar o serviço. Navegação visível continua disponível; geocercas e rastreamento em segundo plano podem atualizar mais tarde.

## Segurança

O Adaptive Performance segue regras para reduzir o risco de otimizações agressivas:

- Não restringe deliberadamente o app em primeiro plano.
- Evita apps críticos do sistema.
- Permite listas de exceção.
- Guarda estados anteriores quando possível.
- Usa rollback quando uma alteração piora o comportamento.
- Não exclui arquivos grandes sem seleção e confirmação.
- Não desinstala apps silenciosamente.
- Não troca tecnologia celular durante chamadas.
- Restaura o perfil de rede original quando necessário.
- Não exige root.

## Shizuku

Grande parte das leituras funciona normalmente, mas várias ações avançadas dependem de **Shizuku** para executar comandos com privilégios de shell.

Sem Shizuku, o app continua abrindo, mas algumas restrições, manutenção avançada e alterações de rede podem ficar indisponíveis.

## Requisitos

- Android 8.0+ (minSdk 26).
- Android 16 é o principal ambiente testado.
- Shizuku recomendado para funções avançadas.
- Algumas funções dependem da ROM/fabricante.
- Acesso ao uso é necessário para recomendações baseadas no histórico de apps.
- Autorização VPN do Android é necessária para o firewall DNS.

## Versão atual

**1.9.2 — pré-carregamento inteligente e economia automática**

Reconhece os quatro apps usados mais recentemente a cada 15 minutos, calcula o orçamento de memória conforme RAM e temperatura, informa os apps mantidos no ciclo e reduz automaticamente o trabalho pesado quando a bateria está baixa ou o telefone aquece.

**1.8.9 — correção do botão Ajustes no modo automático**

Corrige o fechamento do aplicativo ao tocar em Ajustes no modo Automático. A seção de ações agora é inserida corretamente mesmo quando ainda não existe um cartão de ações na tela.

**1.8.8 — congelamento inteligente por tempo de uso**

O congelamento automático agora espera o período escolhido depois que o app sai do primeiro plano. O padrão é 15 minutos e pode ser ajustado entre 5 e 120 minutos. A lista “Apps que nunca devem ser congelados” aceita apps comuns e apps de sistema elegíveis. Componentes essenciais do Android continuam protegidos.

**1.8.7 — ferramenta Reddit separada**

A seção de divulgação do Reddit foi removida do Adaptive Performance e agora está em um APK independente. O aplicativo separado prepara um rascunho, abre o formulário oficial para revisão manual e lembra o intervalo entre publicações; não envia posts sozinho. Os controles adaptativos ampliados e o monitor de bateria permanecem no Adaptive Performance.

[Baixar o APK Divulgação Reddit](https://github.com/langraficagr-collab/adaptive-performance/releases/latest/download/Reddit-Promo-1.0.apk)

**1.8.4 — assistente de divulgação no Reddit**

O assistente de divulgação que antes fazia parte do Adaptive Performance agora está disponível em um APK separado, para manter a otimização do celular independente das ferramentas de promoção.

A versão 1.8.3 incluiu economia GPS opcional em segundo plano com restauração automática, confirmação de sinal fraco e testes de rede de 10 segundos. A verificação padrão da rede é a cada 1 minuto e o cooldown mínimo é 5 minutos. Mantém as melhorias de repouso da versão 1.8.2: verificações em segundo plano mais espaçadas com tela apagada e aparelho frio, mantendo rapidez quando há calor ou pressão alta.

[Baixar a versão mais recente](https://github.com/langraficagr-collab/adaptive-performance/releases/latest)

## Compilação

~~~bash
gradle :app:assembleDebug --no-daemon
~~~

APK de debug:

~~~text
app/build/outputs/apk/debug/app-debug.apk
~~~

## Estrutura principal

- MainActivity.java — painel, navegação e ações inteligentes.
- OptimizationService.java — serviço principal de monitoramento e otimização.
- AdvancedAdaptiveController.java — decisões adaptativas e memória.
- BackgroundMaintenance.java — manutenção e controle de segundo plano.
- ManualFreezeManager.java — congelamento manual persistente.
- RestrictionGuard.java — coordenação segura entre módulos de restrição.
- StorageCleanupActivity.java — limpeza, arquivos grandes, apps sem uso e manutenção.
- SmartRecommendationSuite.java — automação inteligente, thrashing, bateria e recomendações.
- AdGuardDnsVpnService.java — túnel DNS local por aplicativo.
- DnsFirewallActivity.java — configuração do firewall DNS.
- CellularSignalOptimizer.java — comparação de tecnologias móveis.
- SignalOptimizerActivity.java — configuração do otimizador de sinal e economia GPS.
- LocationBatteryController.java — limitação/restauração de localização em segundo plano.
- CpuPressureController.java — controle de pressão de CPU.
- SystemBatteryController.java — bateria, repouso e wakeups.
- ExtendedDiagnosticsController.java — diagnósticos avançados.
- AdaptiveIntelligenceController.java — confiança, recorrência, aprendizado e Auto-Reparo.

## Vídeo de apresentação

[▶ Assistir ao vídeo do Adaptive Performance](https://github.com/langraficagr-collab/adaptive-performance/releases/download/v1.4.8/AdaptivePerformance-divulgacao.mp4)

O vídeo usa gravação real do aplicativo, narração em português e algumas cenas ilustrativas geradas por IA.

## Observações

O repositório não inclui caminhos locais do Android SDK, credenciais, backups de desenvolvimento ou arquivos temporários de build.

O comportamento de funções privilegiadas pode mudar entre fabricantes e versões do Android. Resultados devem ser interpretados de acordo com hardware, kernel e ROM utilizados.

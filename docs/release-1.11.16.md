# Adaptive Performance 1.11.16 — Congelamento manual forte e detecção de RAM

Data: 30 de outubro de 2026. `versionCode 98`; `versionName 1.11.16-strong-freeze`.

## Mudanças

- Congelamento forte de aplicativos **selecionados manualmente**: `am force-stop --user 0`, standby restrito e, quando autorizados pelo sistema, bloqueios `RUN_IN_BACKGROUND` e `RUN_ANY_IN_BACKGROUND` via Shizuku.
- AppOps originais são guardados antes de cada alteração e restaurados quando o usuário abre o app, retira a seleção ou desativa a função, com verificação do resultado. Se a ROM não permitir, mantém a força-parada.
- O status e a opção de desligar congelamento forte aparecem no aplicativo. Apps essenciais ou do sistema continuam protegidos.
- Detecção de tendências repetidas de crescimento de RAM: exige **2 episódios distintos**, separados por nova janela de PSS, dentro de 24 horas. O usuário recebe uma pergunta por notificação ou diálogo; nenhum app é acrescentado à seleção sem aprovação. No modo automático, usa somente coleta de memória leve, sem habilitar o diagnóstico pesado completo.
- Teste Java do contador de episódios: 6 casos aprovados. Compilação `assembleFullDebug` aprovada.

## Validação e cautelas

Teste em aparelho Android 16: congelamento forte aplicado aos 7 apps anteriormente selecionados; abrir o Vibes restaurou os AppOps originais e sair reaplicou o bloqueio. Uma primeira compilação da 1.11.16 foi instalada com sucesso. O Android cancelou a tentativa posterior de instalar o pacote final (INSTALL_FAILED_USER_RESTRICTED); **essa compilação final não foi instalada para um novo teste no dispositivo**. A lógica de recorrência foi testada em Java sem simular vazamentos reais no aparelho.

**Aviso:** congelamento forte pode impedir notificações, alarmes, autenticações ou sincronização de aplicativos selecionados, inclusive bancários. Não utilize em aplicativos essenciais. A leitura de crescimento de PSS sugere um possível vazamento, mas não o comprova.

APK de desenvolvimento assinado; atualizações diretas exigem a mesma assinatura que a versão instalada. Recursos privilegiados dependem de Shizuku/ROM. A versão não promete ganho de autonomia mensurado.

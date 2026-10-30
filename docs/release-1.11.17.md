# Adaptive Performance 1.11.17 — congelamento Shizuku/ADB shell

30 de outubro de 2026 · `versionCode 99` · `versionName 1.11.17-shizuku-adb-freeze` · versão de teste.

## Mudanças

- Congelamento de apps selecionados executado explicitamente com `am force-stop --user 0 PACKAGE`, através do serviço privilegiado Shizuku.
- Verifica o UID do serviço: 2000 = ADB shell (padrão), 0 = root (opcional). Não permite ações de congelamento via um serviço sem privilégios.
- Verifica com `pidof PACKAGE` se o processo principal foi finalizado antes de registrar sucesso; apresenta o método e o resultado na interface.
- Mantém as restrições de standby/AppOps e os estados originais para restauração ao abrir ou tirar o app da seleção.
- Mantém solicitações de consentimento para sugestões de congelamento por aquecimento ou duas suspeitas independentes de vazamento de RAM. Não congela novos apps sem aprovação.
- Não utiliza `cmd activity freeze --sticky`: essa opção suspende um processo ainda existente e pode dificultar a abertura posterior, enquanto a parada forçada evita o problema.

## Verificação

Gradle `assembleFullDebug`: aprovado. Doze testes Java de validação do backend e comandos: aprovados. Android 16 (Shizuku UID 2000): APK instalado com dados preservados. No teste com Vibes, a abertura restaurou os AppOps anteriores; ao sair, o aplicativo executou a parada forçada, confirmou a ausência do processo principal via `pidof` e reaplicou `RUN_IN_BACKGROUND` e `RUN_ANY_IN_BACKGROUND=ignore`. Sem falhas fatais observadas durante o teste.

**Atenção:** apps congelados podem deixar de receber mensagens, alarmes e notificações, inclusive bancárias. Este método não é root e não garante a ausência de todos os subprocessos ou novas execuções futuras; é a parada forçada de pacote confirmada para o processo principal, combinada com as restrições disponíveis na ROM. O APK foi assinado com certificado de desenvolvimento; atualizações exigem assinatura compatível.

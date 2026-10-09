# Revisão de código com CodeRabbit e GitHub

O CodeRabbit revisa Pull Requests no GitHub. A aplicação continua independente
do CodeRabbit em tempo de execução: **nenhum serviço MCP novo roda no celular**.

## Ativação inicial (necessita consentimento do titular da conta)

1. Entre na conta GitHub que administra `langraficagr-collab/adaptive-performance`.
2. Abra https://github.com/apps/coderabbitai e escolha **Install** ou **Configure**.
3. Selecione a conta e, de preferência, **Only select repositories**:
   `langraficagr-collab/adaptive-performance`. Conceda as permissões solicitadas
   somente após revisá-las.
4. A integração é gratuita para repositórios públicos, dentro dos limites de uso
   publicados pelo CodeRabbit. A configuração `.coderabbit.yaml` habilita análise
   automática em PRs novos e revisões incrementais.

## Fluxo de erros e correções

- Abra um Pull Request de uma branch de correção para `main`.
- Espere os comentários do CodeRabbit aparecerem; verifique também Android Lint
  e CodeQL. CodeRabbit **não aplica correções por conta própria** nesta integração.
- No Termux, use `tools/coderabbit-findings.sh NUMERO_DO_PR` para exibir revisões,
  observações por linha e comentários do bot, sem copiar tokens para arquivos.
- O ChatGPT, pela integração GitHub, pode ler essas observações; a correção é feita
  no código local, testada com Gradle e ADB e publicada de volta no PR para nova
  análise. Faça merge somente depois de revisar os resultados e os testes.
- Se o aplicativo foi instalado depois da abertura do PR, publique um novo commit
  no PR ou solicite uma revisão manual mencionando `@coderabbitai review`.

Notas: os modelos ML são locais; não envie dados pessoais, chaves ou arquivos
privados para comentários públicos. A assinatura do APK deve continuar compatível
para preservar dados nas atualizações.

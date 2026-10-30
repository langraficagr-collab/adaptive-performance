/* Adaptive Performance chat: local, transparent, contextual guide for the static Pages site.
 * No external requests or secret tokens. Keep replies tied to documented app behavior.
 */
(function (root, factory) {
  const engine = factory();
  if (typeof module === 'object' && module.exports) module.exports = engine;
  if (root) root.AdaptiveChatEngine = engine;
})(typeof window !== 'undefined' ? window : null, function () {
  'use strict';
  const RELEASE = '1.11.16';
  const REPO = 'https://github.com/langraficagr-collab/adaptive-performance';
  const DOWNLOAD = REPO + '/releases/download/v1.11.16/Adaptive-Performance-v1.11.16.apk';
  const TOPICS = [
    ['identity', ['quem e voce', 'voce e humano', 'voce e pessoa', 'voce e bot', 'voce e ia', 'are you human', 'who are you', 'are you ai', 'real person', 'your name', 'can you access my device', 'access my phone', 'access my device', 'acessar meu celular', 'acessa meu celular', 'pode acessar meu celular']],
    ['privacy', ['privacidade', 'dados pessoais', 'coleta dados', 'seguranca dados', 'privacy', 'personal data', 'data collection', 'tracking', 'confidencialidade']],
    ['notifications', ['notificacoes', 'notificacao', 'avisos', 'alertas', 'notification', 'notifications', 'enable notifications', 'alert', 'nao avisa', 'not receiving']],
    ['history', ['atividade recente', 'atividades recentes', 'historico', 'historico de mudancas', 'recent activity', 'activity log', 'history', 'logs de atividades']],
    ['inactivity', ['inatividade', 'apps restritos', 'restricoes', 'restringir apps', 'restricao por inatividade', 'background restriction', 'inactivity', 'restricted apps', 'restrictions']],
    ['freeze', ['congelamento', 'congelar', 'congela', 'nao congela', 'nao congelou', 'descongelar', 'apps congelados', 'freeze', 'freezing', 'not freezing', 'app not freezing', 'frozen', 'unfreeze', 'force stop', 'force-stop']],
    ['charging', ['carregamento', 'carregando', 'carregar devagar', 'carga lenta', 'carregador', 'slow charging', 'charge slowly', 'charging', 'charger']],
    ['heat', ['aquecimento', 'esquentando', 'esquenta', 'superaquec', 'temperatura', 'quente', 'calor', 'thermal', 'overheat', 'hot phone', 'heating', 'temperature']],
    ['install', ['instalar', 'instalacao', 'baixar', 'download', 'apk', 'atualizar aplicativo', 'instal', 'install', 'upgrade', 'update app', 'where to get']],
    ['release', ['nova versao', 'versao atual', 'versao mais recente', 'changelog', 'novidades', '1.11.16', 'latest version', 'release', 'version', 'what is new', 'whats new']],
    ['shizuku', ['shizuku', 'adb', 'rish', 'permissao shell', 'shell permission']],
    ['root', ['root', 'sem root', 'without root', 'rooted']],
    ['auto', ['modo automatico', 'automatico', 'automatica', 'automated', 'automatic mode', 'automatic tuning']],
    ['advanced', ['modo avancado', 'avancado', 'manual mode', 'advanced mode', 'advanced settings']],
    ['battery', ['bateria', 'autonomia', 'descarga', 'consumo', 'economia', 'battery', 'power drain', 'battery drain', 'battery life', 'save power']],
    ['memory', ['ram', 'zram', 'swap', 'memoria', 'memory', 'memory leak', 'lag ram']],
    ['performance', ['lentidao', 'travando', 'lento', 'engasgando', 'fluidez', 'cpu', 'gpu', 'performance', 'slow', 'lag', 'stuttering', 'framerate']],
    ['learning', ['aprendizado', 'aprende', 'machine learning', 'brain 2', 'inteligencia artificial', 'machine intelligence', 'learning', 'ml 2']],
    ['location', ['gps', 'localizacao', 'location', 'geofence', 'geofencing']],
    ['network', ['sinal', 'rede movel', '5g', '4g', 'internet movel', 'mobile signal', 'network', 'mobile data']],
    ['storage', ['armazenamento', 'limpeza', 'espaco', 'f2fs', 'trim', 'storage', 'cleanup', 'disk space']],
    ['compatibility', ['android 16', 'hyperos', 'xiaomi', 'poco', 'compatibilidade', 'compatibility', 'which android', 'supported device']],
    ['support', ['ajuda', 'suporte', 'erro', 'problema', 'bug', 'nao funciona', 'parou', 'help', 'support', 'not working', 'broken', 'issue']],
    ['thanks', ['obrigado', 'obrigada', 'valeu', 'thanks', 'thank you', 'thx']],
    ['greeting', ['oi', 'ola', 'bom dia', 'boa tarde', 'boa noite', 'e ai', 'hello', 'hey', 'hi', 'good morning']],
    ['farewell', ['tchau', 'ate mais', 'falou', 'bye', 'goodbye', 'see you']]
  ];
  const COPY = {
    pt: {
      welcome: 'Oi! Sou o assistente virtual do Adaptive Performance. Posso te ajudar a instalar, configurar ou resolver algum problema. O que você precisa?',
      chips: [['Meu celular esquenta','Meu celular está esquentando'],['Congelamento','O congelamento não funciona'],['Notificações','As notificações não aparecem'],['Baixar APK','Onde baixar a versão atual?']],
      unknown: 'Ainda não tenho uma resposta confiável para isso. Se me contar o que aparece na tela ou qual função está usando, posso sugerir um teste seguro. Também dá para relatar o problema no GitHub.',
      follow: 'Consigo explicar o passo a passo. Qual parte você quer resolver primeiro?',
      answers: {
        identity: ['Sou o assistente virtual deste site, não uma pessoa. Uso respostas baseadas na documentação do Adaptive Performance e no contexto desta conversa. Não tenho acesso direto ao seu celular nem faço alterações nele.', 'Posso conversar sobre os recursos, ajudar com configurações e orientar diagnósticos. Se eu não souber, prefiro dizer isso a inventar uma solução.'],
        privacy: ['Este chat funciona no próprio navegador, sem enviar suas perguntas para um servidor de IA. A conversa pode ficar na aba durante a sessão; o botão “Limpar” apaga o histórico local do chat.', 'Não compartilhe senhas, tokens, dados bancários nem informações pessoais em relatos públicos de erro. Para conferir detalhes do projeto, consulte a política de privacidade no repositório.'],
        notifications: ['Na versão 1.11.16, abra **Notificações de alterações** no aplicativo. Ali você vê se o Android bloqueou o canal e encontra **Enviar notificação de teste**.', 'Se o teste não aparecer, toque em **Configurar canal de notificações no Android** e habilite **Mostrar notificações**. Depois repita o teste. Os avisos também precisam estar ligados dentro do app.'],
        history: ['Na tela inicial, procure **Atividade recente** e toque em **Ver todas as atividades**. A 1.11.16 reúne eventos de manutenção, restrições, congelamentos e outras mudanças.', 'O histórico agora continua registrando ações mesmo que a análise de eficácia do aprendizado automático esteja desativada.'],
        inactivity: ['Em **Apps restritos por inatividade**, a 1.11.16 mostra a lista de restrições registradas, além de permitir consultar todos os apps. Registros de aplicativos desinstalados são limpos sem alterar as restrições dos que ainda existem.', 'Novas restrições automáticas devem se limitar a aplicativos instalados pelo usuário; apps do sistema ficam excluídos. Se uma restrição antiga permanecer, ela não some só porque a varredura foi desligada.'],
        freeze: ['O congelamento manual precisa estar ativado e ter os aplicativos selecionados. Ele age ao sair do app, não enquanto você está usando ele. Algumas ações exigem autorização do Shizuku.', 'Se não funcionar: confira a permissão Shizuku, a lista de apps selecionados e o status de congelamento na interface. Apps protegidos ou essenciais podem ser recusados por segurança.'],
        charging: ['O Adaptive Performance monitora temperatura e estados da bateria, mas não consegue aumentar a potência física do carregador.', 'Se a carga estiver lenta, confira o cabo e o carregador, evite jogos durante a carga e observe a temperatura. O próprio Android pode reduzir a corrente quando o aparelho está quente.'],
        heat: ['Se o aparelho está esquentando, comece verificando a temperatura e os apps recentes no Adaptive Performance. Na 1.11.16 há sugestões de congelamento com confirmação, baseadas em temperatura e indícios repetidos de uso alto de CPU.', 'Isso não mede o calor de cada aplicativo individualmente, então a sugestão é uma hipótese, não uma certeza. Evite congelar apps importantes ou do sistema.'],
        install: ['A versão disponível é a **1.11.16**. Baixe o APK pela página de Releases, abra o arquivo no Android e permita a instalação quando solicitado.', 'Para comandos avançados, inicie o Shizuku e autorize o Adaptive Performance. A atualização por cima exige a mesma assinatura do APK anterior; se der “app não instalado”, não desinstale antes de salvar seus dados.'],
        release: ['A versão **1.11.16** traz histórico completo de atividades, correção de registros obsoletos de restrições, notificações com teste, congelamento manual e sugestões térmicas com consentimento.', 'É uma versão de teste assinada para desenvolvimento. Economia real de bateria e identificação da causa do aquecimento ainda dependem de medições.'],
        shizuku: ['Abra o Shizuku, inicie o serviço pelo método disponível no seu Android e autorize o **Adaptive Performance** na lista de aplicativos.', 'Volte ao app e confira o indicador de conexão. Se ele perder acesso depois de reiniciar o celular, pode ser necessário iniciar o Shizuku novamente.'],
        root: ['Não é necessário root para instalar o Adaptive Performance. Algumas funções avançadas dependem do Shizuku e das permissões que o Android realmente concede.', 'Sem Shizuku, o monitoramento básico pode funcionar, mas vários comandos de sistema e congelamento ficam limitados.'],
        auto: ['O modo **Automático** tenta equilibrar bateria, fluidez e temperatura. Ele compara configurações reversíveis usando períodos de observação e recua quando há indícios de piora.', 'Na versão atual, os testes de referência e candidato usam janelas de até 30 minutos e precisam de amostras suficientes. Nem todo resultado é prova de economia.'],
        advanced: ['O modo **Avançado** deixa você ajustar recursos como memória, proteção térmica, congelamento e outras opções manualmente.', 'Vale começar com poucos ajustes por vez e acompanhar o resultado. Alterar muitas opções juntas dificulta descobrir o que realmente ajudou.'],
        battery: ['O app tenta reduzir trabalho desnecessário em segundo plano, observar temperatura e adaptar o monitoramento ao uso. Isso pode ajudar, mas não garante uma porcentagem fixa de economia.', 'Para descobrir a causa de descarga rápida, compare o consumo antes e depois e confira também o painel de bateria do próprio Android. A análise precisa de algumas horas de uso real.'],
        memory: ['O Android usa RAM livre para cache, e isso é normal. O Adaptive Performance oferece controle de compactação, zRAM e proteção contra pressão de memória, mas mexer em tudo pode piorar a fluidez.', 'Se o celular está travando, veja se há pressão real de memória antes de aumentar a limpeza ou fechar apps.'],
        performance: ['Travamentos podem vir de aquecimento, pressão de RAM, apps ativos, I/O ou da própria interface. O app monitora alguns desses sinais, mas não dá para concluir a causa só olhando um número.', 'Comece observando temperatura, CPU/RAM e o histórico de atividades. Se piorou após um ajuste, volte ao perfil anterior e compare.'],
        learning: ['O **Adaptive Brain 2.0** aprende padrões no próprio aparelho. Ele usa observações de uso e desempenho para orientar opções reversíveis, sem depender de um servidor de IA.', 'Previsões por si só não autorizam congelar aplicativos; sugestões térmicas da 1.11.16 exigem sua confirmação.'],
        location: ['A economia de GPS é opcional e pode espaçar atualizações em segundo plano quando a tela está apagada. Ela deve restaurar a configuração ao acender a tela ou conectar o carregador.', 'Apps de navegação e localização em tempo real podem ser afetados. Desative a economia de GPS se notar atrasos.'],
        network: ['O otimizador de sinal compara modos de rede permitidos pelo aparelho e pelo SIM quando o sinal está fraco, evitando mudanças durante chamadas.', 'A disponibilidade de 2G, 3G, 4G e 5G depende da operadora, do aparelho e da ROM.'],
        storage: ['O app acompanha espaço livre e pode executar manutenção de armazenamento sob condições seguras. Diagnósticos pesados são adiados quando o celular está quente ou carregando.', 'A limpeza não deve apagar arquivos pessoais sem uma ação clara do usuário. Para arquivos grandes, confira o que será removido antes de confirmar.'],
        compatibility: ['O Adaptive Performance foi desenvolvido principalmente com Android 16 em mente, mas o APK permite instalação a partir do Android 8.', 'Os recursos avançados variam conforme ROM, fabricante e permissões do Shizuku. Um comando pode estar disponível em um aparelho e indisponível em outro.'],
        support: ['Consigo ajudar a diagnosticar. Me diga **qual função falhou** e **o que aparece na tela**. Assim evito te passar passos genéricos ou arriscados.', 'Se houver fechamento inesperado ou um erro repetido, dá para abrir uma Issue no GitHub com versão do app, Android e passos para reproduzir, sem dados pessoais.'],
        thanks: ['Por nada. Se surgir outra dúvida sobre o app, pode perguntar.', 'Disponha. Quer conferir mais alguma configuração?'],
        greeting: ['Olá! Quer ajuda para instalar, configurar ou resolver algum problema no Adaptive Performance?', 'Oi! Me conta o que você quer fazer no aplicativo.'],
        farewell: ['Até mais! Quando precisar, é só abrir o chat.', 'Até a próxima!']
      },
      more: {
        notifications: 'Para conferir passo a passo: abra o app → **Notificações de alterações** → **Enviar notificação de teste**. Se não aparecer, abra **Configurar canal de notificações no Android** e habilite **Mostrar notificações**.',
        freeze: 'Confira: 1) Shizuku ativo e autorizado; 2) congelamento manual habilitado; 3) app selecionado; 4) saia desse app; 5) veja o histórico de atividades. Se falhar, diga qual aplicativo você escolheu.',
        heat: 'Se estiver desconfortavelmente quente, interrompa tarefas pesadas e deixe o aparelho esfriar. Depois compare temperatura, processos ativos e histórico do Adaptive Performance. O diagnóstico de calor por app é estimativo.',
        inactivity: 'Abra a lista completa de apps com restrições registradas e confira se a varredura automática está habilitada. Restrições antigas podem permanecer até uma restauração válida; não limpe dados do app para corrigir isso.',
        install: 'Primeiro baixe o APK da Release 1.11.16. Abra o arquivo pelo gerenciador e autorize a instalação. Se o Android rejeitar a atualização por assinatura diferente, preserve seus dados e peça orientação antes de desinstalar.',
        shizuku: 'No Shizuku, veja se o serviço está em execução; depois abra a lista de aplicativos autorizados e habilite Adaptive Performance. Reinicie o app e confira o indicador Shizuku.',
        battery: 'Compare dois períodos com brilho, rede e uso semelhantes. Confira a estatística de consumo por aplicativo do Android e se o Adaptive Performance aparece consumindo CPU em excesso.',
        history: 'Na tela inicial, role até Atividade recente. O botão Ver todas as atividades abre até 60 registros recentes e mostra horário e descrição.',
        privacy: 'O chatbot não exige login nem chave de API. O histórico fica só na sessão deste navegador; Limpar apaga as mensagens salvas nessa sessão.'
      }
    },
    en: {
      welcome: 'Hi! I’m the Adaptive Performance virtual assistant. I can help with setup, features, and troubleshooting. What would you like to do?',
      chips: [['Phone gets hot','My phone is overheating'],['App freezing','App freezing is not working'],['Notifications','I am not receiving notifications'],['Download APK','Where can I download the latest version?']],
      unknown: 'I don’t have a reliable answer for that yet. Tell me which feature you’re using and what the screen shows, and I can suggest a safe next step. You can also report the issue on GitHub.',
      follow: 'I can walk you through it. Which part would you like to tackle first?',
      answers: {
        identity: ['I’m this website’s virtual assistant, not a person. My replies come from Adaptive Performance documentation and the context of our conversation. I cannot access or change your phone.', 'I can help explain features, settings and safe troubleshooting. If I’m unsure, I’ll say so rather than guessing.'],
        privacy: ['This chat runs in your browser. Questions are not sent to an AI server. Messages may remain in this tab during the session; **Clear** deletes that local conversation history.', 'Avoid sharing passwords, access tokens or personal data in public bug reports. The project’s privacy policy is available in its GitHub repository.'],
        notifications: ['In version 1.11.16, open **Change notifications** in the app. You can check whether Android blocked the channel and tap **Send test notification**.', 'If the test does not arrive, use **Configure notification channel in Android**, enable **Show notifications**, and try again. Also confirm notifications are enabled inside the app.'],
        history: ['On the home screen, find **Recent activity** and tap **View all activity**. Version 1.11.16 groups maintenance, restrictions, freezes and other changes.', 'Activity is saved even when automatic effectiveness scoring is switched off.'],
        inactivity: ['The **Inactive app restrictions** panel lists tracked restrictions and offers a full list. Version 1.11.16 cleans entries for uninstalled apps without changing existing apps’ settings.', 'New automatic restrictions are limited to user-installed apps, excluding system packages. Older restrictions do not disappear just because new scans are switched off.'],
        freeze: ['Manual freezing must be enabled and target apps selected. It acts after you leave the app, not while you are using it. Some actions need Shizuku permission.', 'If it does not work, check Shizuku access, selected packages and the freeze status. Essential or protected apps may be rejected for safety.'],
        charging: ['Adaptive Performance monitors battery temperature, but it cannot physically increase your charger’s power.', 'For slow charging, check the cable and adapter, avoid heavy gaming during charging and watch temperature. Android may throttle charging when the phone gets hot.'],
        heat: ['If your device is overheating, check its temperature and recent apps. Version 1.11.16 offers consent-based freeze suggestions using elevated temperature and repeated high CPU signals.', 'This is not a direct per-app heat measurement, so a suggestion is a clue, not proof. Avoid freezing critical or system apps.'],
        install: ['The latest available build is **1.11.16**. Download the APK from Releases, open it on Android and approve installation when prompted.', 'For advanced features, start Shizuku and authorize Adaptive Performance. In-place updates need a matching signing certificate; if Android rejects the APK, back up your data before any uninstall.'],
        release: ['Version **1.11.16** adds a full activity history, stale restriction cleanup, testable notifications, manual app freezing and consent-based heat suggestions.', 'It is a development-signed test release. Battery-life gains and attribution of heating to a specific app still need real-world verification.'],
        shizuku: ['Open Shizuku, start its service using a method supported by your Android version and authorize **Adaptive Performance** in its app list.', 'Return to Adaptive Performance and check the connection indicator. Shizuku may need to be restarted after a phone reboot.'],
        root: ['Root is not required to install Adaptive Performance. Advanced controls may require Shizuku and permissions actually granted by Android.', 'Without Shizuku, some basic monitoring works, but system changes and app freezing are limited.'],
        auto: ['**Automatic mode** aims to balance battery life, smoothness and temperature. It compares reversible settings using observation windows and rolls back changes associated with regressions.', 'Current baseline and candidate tests use windows up to 30 minutes and require enough samples. Not every measured change proves power savings.'],
        advanced: ['**Advanced mode** exposes manual controls for thermal protection, memory, app freezing and more.', 'Start with one change at a time and compare results. Several simultaneous changes make it hard to find what helped.'],
        battery: ['The app tries to reduce unnecessary background work, monitor thermals and adapt polling to device use. It does not guarantee a fixed percentage of battery savings.', 'For rapid battery drain, compare before and after, and inspect Android’s own battery usage statistics. Meaningful testing takes hours of normal use.'],
        memory: ['Android uses spare RAM as cache, which is normal. Adaptive Performance offers compression, zRAM and memory-pressure controls, but aggressive changes can worsen responsiveness.', 'If the phone is lagging, check real memory pressure before aggressively clearing apps.'],
        performance: ['Lag can be caused by heat, memory pressure, active apps, storage I/O or graphics. One metric cannot establish the cause.', 'Start with temperature, CPU/RAM and recent activity. If a recent tweak made it worse, roll that back and compare.'],
        learning: ['**Adaptive Brain 2.0** learns usage patterns locally on the device. It uses observations to guide reversible settings, without requiring a cloud AI server.', 'A prediction alone does not freeze an app. Thermal suggestions in 1.11.16 require your approval.'],
        location: ['Optional GPS savings may space out background location updates with the screen off and should restore normal behavior when the screen is on or charging begins.', 'Navigation and real-time location apps may be affected. Turn the feature off if location updates are late.'],
        network: ['The signal optimizer compares network modes allowed by your device and SIM when reception is weak, avoiding switches during calls.', '2G, 3G, 4G and 5G availability depends on your carrier, hardware and firmware.'],
        storage: ['The app watches free space and can request storage maintenance in safe conditions. Heavy work is postponed while charging or hot.', 'Always review the list before removing personal files.'],
        compatibility: ['Adaptive Performance has mainly been developed for Android 16, but its APK supports installation on Android 8 and newer.', 'Shizuku features vary by firmware, manufacturer and permission availability. A command may work on one phone but not another.'],
        support: ['I can help troubleshoot. Tell me **which feature failed** and **what you see on screen**. That way I can avoid generic or risky advice.', 'For a reproducible crash, open a GitHub Issue with the app version, Android version and steps to reproduce, without personal data.'],
        thanks: ['You’re welcome. Feel free to ask if anything else comes up.', 'Glad to help. Want to check another setting?'],
        greeting: ['Hi! Would you like help with installation, setup or a problem in Adaptive Performance?', 'Hello! Tell me what you’re trying to do in the app.'],
        farewell: ['See you next time. You can reopen this chat whenever you need it.', 'Take care!']
      },
      more: {
        notifications: 'Open the app → **Change notifications** → **Send test notification**. If nothing appears, open **Configure notification channel in Android** and enable **Show notifications**.',
        freeze: 'Check: 1) Shizuku is running and authorized; 2) manual freeze enabled; 3) app selected; 4) leave that app; 5) review the activity history. If it still fails, tell me which app you selected.',
        heat: 'If the phone feels uncomfortably hot, pause heavy tasks and let it cool. Then compare temperatures, active processes and Adaptive Performance history. Per-app heat attribution is an estimate.',
        inactivity: 'Open the full restricted-app list and check whether automatic scans are enabled. Old restrictions may remain pending a verified restoration; clearing app data is not a safe fix.',
        install: 'Download the 1.11.16 APK from Releases, open it in Android’s file manager and approve installation. If there is a signature mismatch, preserve your data and ask for guidance before uninstalling.',
        shizuku: 'Check that Shizuku is running, authorize Adaptive Performance in Shizuku’s app list, then reopen the app and check its Shizuku indicator.',
        battery: 'Compare similar usage periods with comparable brightness and connectivity. Check Android’s battery-by-app report and whether Adaptive Performance itself is using too much CPU.',
        history: 'On the home screen, scroll to Recent activity and tap View all activity. You can review up to 60 recent events with timestamps.',
        privacy: 'The chatbot needs no account or API key. Messages stay in this browser tab’s session; Clear erases the saved session messages.'
      }
    }
  };
  const FOLLOWS = {
    pt: [ ['Como faço?','Como faço isso passo a passo?'],['Não funcionou','Isso não funcionou'],['Versão 1.11.16','Quais são as novidades da versão 1.11.16?'] ],
    en: [ ['Show me how','How do I do that step by step?'],['Still not working','That did not work'],['Version 1.11.16','What changed in version 1.11.16?'] ]
  };
  const LINKS = {
    install: [ ['pt','Baixar APK'], ['en','Download APK'], DOWNLOAD ],
    release: [ ['pt','Baixar APK 1.11.16'], ['en','Download APK 1.11.16'], DOWNLOAD ],
    support: [ ['pt','Relatar problema'], ['en','Report an issue'], REPO + '/issues/new/choose' ],
    privacy: [ ['pt','Política de privacidade'], ['en','Privacy policy'], REPO + '/blob/main/PRIVACY.md' ]
  };
  const normalize = function (s) {
    return String(s || '').toLowerCase().normalize('NFD').replace(/[\u0300-\u036f]/g, '').replace(/[^a-z0-9. ]/g, ' ').replace(/\s+/g, ' ').trim();
  };
  function matches(q, phrase) {
    const p = normalize(phrase);
    return (' ' + q + ' ').includes(' ' + p + ' ');
  }
  function kind(q, previous) {
    const qn = normalize(q);
    if (!qn) return null;
    const trouble = /\b(nao funciona|nao funcionou|continua|ainda nao|deu errado|nao aparece|nao abre|nao recebo|nao congela|does not work|doesnt work|still not|no longer works|not working|cant|cannot)\b/.test(qn);
    const details = /^(como|onde|por que|porque|explica|explique|passo a passo|me mostra|how|why|show me|explain|tell me more|what next|qual o proximo|e depois|isso|sim|ok|yes|sure|more|mais|pode)/.test(qn);
    let winner = null, best = 0;
    for (const entry of TOPICS) {
      let score = 0;
      for (const term of entry[1]) {
        if (matches(qn, term)) score = Math.max(score, term.includes(' ') ? 3 + term.length / 100 : (term.length <= 3 ? 1.3 : 2) + term.length / 100);
      }
      if (entry[0] === 'support') score *= 0.55;
      if (score > best) { best = score; winner = entry[0]; }
    }
    // Generic follow-up and failure refer to the previous topic, not a new generic support intent.
    if (previous && (details || trouble) && (!winner || winner === 'support' || winner === 'greeting'))
      return { topic: previous, detail: true, trouble };
    if (winner) return { topic: winner, detail: false, trouble };
    if (previous && qn.split(' ').length <= 5 && /\b(sim|yes|pode|ok|mais|detalhes|por favor|please)\b/.test(qn))
      return { topic: previous, detail: true, trouble: false };
    return { topic: 'unknown', detail: false, trouble };
  }
  function suggestions(lang, topic) {
    const l = lang === 'en' ? 'en' : 'pt';
    if (topic === 'unknown' || topic === 'greeting' || topic === 'thanks' || topic === 'farewell') return COPY[l].chips;
    const follow = FOLLOWS[l];
    return [follow[0], follow[1], ...(topic === 'release' ? [[l === 'pt' ? 'Baixar agora' : 'Download now', l === 'pt' ? 'Onde baixar o APK?' : 'Where can I download the APK?']] : [follow[2]])];
  }
  function reply(question, context, language) {
    const lang = language === 'en' ? 'en' : 'pt';
    const data = COPY[lang];
    const ctx = context || {};
    const classified = kind(question, ctx.topic);
    if (!classified) return {topic: ctx.topic || null, text: data.follow, suggestions: data.chips, links: []};
    const {topic, detail, trouble} = classified;
    const variants = data.answers[topic];
    const counter = Number(ctx.turn || 0);
    let message = variants ? variants[counter % variants.length] : data.unknown;
    if (detail && data.more[topic]) message = data.more[topic];
    else if (trouble && topic !== 'support' && topic !== 'unknown') {
      message = (lang === 'pt' ? 'Entendi que ainda não deu certo. ' : 'Got it — it is still not working. ') + (data.more[topic] || message);
    }
    const config = LINKS[topic];
    const links = config ? [{ label: config[lang === 'pt' ? 0 : 1][1], url: config[2] }] : [];
    if (topic === 'unknown' || topic === 'support')
      links.push({label: lang === 'pt' ? 'Abrir Issue' : 'Open issue', url: REPO + '/issues/new/choose'});
    return {topic: topic === 'unknown' ? (ctx.topic || null) : topic,
      text: message, suggestions: suggestions(lang, topic), links};
  }
  return { reply, normalize, release: RELEASE, download: DOWNLOAD, welcome: lang => COPY[lang === 'en' ? 'en' : 'pt'].welcome,
    starter: lang => COPY[lang === 'en' ? 'en' : 'pt'].chips};
});

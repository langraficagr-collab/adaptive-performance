/* Human-friendly browser chat UI. Clear AI disclosure; no network calls. */
(function () {
  'use strict';
  const engine = window.AdaptiveChatEngine;
  const panel = document.getElementById('faqPanel');
  if (!engine || !panel) return;
  const lang = document.documentElement.lang.toLowerCase().startsWith('en') ? 'en' : 'pt';
  const copy = lang === 'en' ? {
    bot: 'Adaptive', user: 'You', thinking: 'Typing…', voiceOn: 'Voice on', voiceOff: 'Read replies aloud',
    historyCleared: 'Conversation cleared. What would you like help with?',
    truncated: 'Please shorten your message.', error: 'Something went wrong. Please try again.'
  } : {
    bot: 'Adaptive', user: 'Você', thinking: 'Digitando…', voiceOn: 'Voz ativada', voiceOff: 'Ouvir respostas',
    historyCleared: 'Conversa limpa. Com o que posso ajudar agora?',
    truncated: 'Resuma sua mensagem, por favor.', error: 'Algo não saiu como esperado. Pode tentar de novo?'
  };
  const button = document.getElementById('faqBot');
  const close = document.getElementById('faqClose');
  const reset = document.getElementById('chatClear');
  const speak = document.getElementById('chatSpeak');
  const log = document.getElementById('faqMsgs');
  const chips = document.getElementById('chatSuggestions');
  const form = document.getElementById('faqForm');
  const input = document.getElementById('faqInput');
  const send = document.getElementById('chatSend');
  const storageKey = 'adaptive-site-chat-v2-' + lang;
  const history = [];
  let lastTopic = null;
  let turns = 0;
  let timer = null;
  let busy = false;
  let voiceEnabled = false;
  let lastBotReply = '';
  const avatar = () => {
    const span = document.createElement('span');
    span.className = 'chat-avatar'; span.textContent = 'A'; span.setAttribute('aria-hidden', 'true');
    return span;
  };
  function scrollBottom() { log.scrollTop = log.scrollHeight; }
  function formatted(text, element) {
    // Minimal rich text without HTML parsing; untrusted questions always use textContent.
    const chunks = String(text).split(/(\*\*[^*]+\*\*)/g);
    for (const chunk of chunks) {
      if (chunk.startsWith('**') && chunk.endsWith('**')) {
        const strong = document.createElement('strong');
        strong.textContent = chunk.slice(2, -2);
        element.appendChild(strong);
      } else element.appendChild(document.createTextNode(chunk));
    }
  }
  function renderMessage(role, text, links, remember) {
    const row = document.createElement('div'); row.className = 'chat-row ' + role;
    if (role === 'bot') row.appendChild(avatar());
    const container = document.createElement('div'); container.className = 'chat-content';
    const author = document.createElement('div'); author.className = 'chat-author';
    author.textContent = role === 'bot' ? copy.bot + ' · assistente virtual'.replace('assistente virtual', lang === 'en' ? 'virtual assistant' : 'assistente virtual') : copy.user;
    const bubble = document.createElement('div'); bubble.className = 'chat-bubble';
    if (role === 'bot') formatted(text, bubble); else bubble.textContent = text;
    container.append(author, bubble);
    if (role === 'bot' && Array.isArray(links) && links.length) {
      const linkRow = document.createElement('div'); linkRow.className = 'chat-links';
      for (const link of links) {
        if (!/^https:\/\/github\.com\/langraficagr-collab\/adaptive-performance(?:\/|$)/.test(link.url)) continue;
        const anchor = document.createElement('a'); anchor.className = 'chat-link';
        anchor.href = link.url; anchor.target = '_blank'; anchor.rel = 'noopener noreferrer';
        anchor.textContent = link.label;
        linkRow.appendChild(anchor);
      }
      container.appendChild(linkRow);
    }
    row.appendChild(container); log.appendChild(row);
    if (role === 'bot') lastBotReply = text;
    if (remember !== false) {
      history.push({role, text, links: links || []});
      if (history.length > 28) history.splice(0, history.length - 28);
      save();
    }
    scrollBottom();
  }
  function save() {
    try { sessionStorage.setItem(storageKey, JSON.stringify({history, topic:lastTopic, turns})); }
    catch (_error) { /* Private browsing or storage disallowed. UI still works. */ }
  }
  function suggestions(options) {
    chips.replaceChildren();
    for (const pair of (options || []).slice(0, 4)) {
      if (!Array.isArray(pair) || !pair[0] || !pair[1]) continue;
      const chip = document.createElement('button'); chip.type = 'button';
      chip.textContent = pair[0]; chip.setAttribute('aria-label', pair[1]);
      chip.addEventListener('click', () => sendMessage(pair[1])); chips.appendChild(chip);
    }
  }
  function showTyping() {
    const row = document.createElement('div'); row.className = 'chat-row bot'; row.id = 'chatTyping';
    const content = document.createElement('div'); content.className = 'chat-typing';
    content.setAttribute('role', 'status'); content.setAttribute('aria-label', copy.thinking);
    for (let n=0; n<3; n++) content.appendChild(document.createElement('span'));
    row.append(avatar(), content); log.appendChild(row); scrollBottom();
  }
  function say(text) {
    if (!voiceEnabled || !('speechSynthesis' in window)) return;
    const clean = String(text || '').replace(/\*\*/g, '');
    try {
      window.speechSynthesis.cancel();
      const utterance = new SpeechSynthesisUtterance(clean);
      utterance.lang = lang === 'en' ? 'en-US' : 'pt-BR';
      utterance.rate = 0.99; utterance.pitch = 1;
      const voices = window.speechSynthesis.getVoices();
      const chosen = voices.find(v => v.lang.toLowerCase() === utterance.lang.toLowerCase() && /natural|neural|google|microsoft/i.test(v.name))
        || voices.find(v => v.lang.toLowerCase() === utterance.lang.toLowerCase());
      if (chosen) utterance.voice = chosen;
      window.speechSynthesis.speak(utterance);
    } catch (_error) { /* Speech is optional and browser dependent. */ }
  }
  function sendMessage(message) {
    const text = String(message || '').trim();
    if (!text || busy) return;
    if (text.length > 500) { input.setCustomValidity(copy.truncated); input.reportValidity(); return; }
    input.setCustomValidity(''); input.value = '';
    busy = true; send.disabled = true; suggestions([]);
    renderMessage('user', text, [], true);
    showTyping();
    // Very brief typing cue; deliberately no slow pseudo-human delays.
    timer = setTimeout(() => {
      timer = null; document.getElementById('chatTyping')?.remove();
      try {
        const result = engine.reply(text, {topic:lastTopic, turn:turns}, lang);
        turns++; lastTopic = result.topic;
        renderMessage('bot', result.text, result.links, true);
        suggestions(result.suggestions); say(result.text); save();
      } catch (_error) { renderMessage('bot', copy.error, [], true); }
      finally { busy = false; send.disabled = false; }
    }, 270);
  }
  function clearChat() {
    if (timer !== null) clearTimeout(timer);
    timer = null; busy = false; turns = 0; lastTopic = null; lastBotReply = '';
    send.disabled = false; log.replaceChildren(); history.length = 0;
    if ('speechSynthesis' in window) window.speechSynthesis.cancel();
    try { sessionStorage.removeItem(storageKey); } catch (_error) {}
    renderMessage('bot', copy.historyCleared, [], true);
    suggestions(engine.starter(lang)); input.focus();
  }
  function toggle(open) {
    panel.hidden = !open; button.setAttribute('aria-expanded', String(open));
    if (open) { input.focus(); scrollBottom(); }
    else {
      if ('speechSynthesis' in window) window.speechSynthesis.cancel();
      button.focus();
    }
  }
  button.addEventListener('click', () => toggle(panel.hidden));
  close.addEventListener('click', () => toggle(false));
  reset.addEventListener('click', clearChat);
  document.addEventListener('keydown', ev => { if (ev.key === 'Escape' && !panel.hidden) toggle(false); });
  form.addEventListener('submit', ev => { ev.preventDefault(); sendMessage(input.value); });
  input.addEventListener('input', () => input.setCustomValidity(''));
  if (!('speechSynthesis' in window)) speak.hidden = true;
  else speak.addEventListener('click', () => {
    voiceEnabled = !voiceEnabled; speak.setAttribute('aria-pressed', String(voiceEnabled));
    speak.title = voiceEnabled ? copy.voiceOn : copy.voiceOff;
    speak.setAttribute('aria-label', speak.title);
    if (voiceEnabled && lastBotReply) say(lastBotReply); else window.speechSynthesis.cancel();
  });
  try {
    const saved = JSON.parse(sessionStorage.getItem(storageKey) || 'null');
    if (saved && Array.isArray(saved.history)) {
      for (const entry of saved.history.slice(-28)) {
        if (!entry || !['user', 'bot'].includes(entry.role) || typeof entry.text !== 'string') continue;
        renderMessage(entry.role, entry.text.slice(0, 2200), entry.links || [], false);
        history.push({role:entry.role, text:entry.text.slice(0, 2200), links:entry.links || []});
      }
      lastTopic = typeof saved.topic === 'string' ? saved.topic : null;
      turns = Math.min(100, Math.max(0, Number(saved.turns) || 0));
    }
  } catch (_error) { /* Corrupt storage should not break the chat. */ }
  if (!history.length) renderMessage('bot', engine.welcome(lang), [], true);
  suggestions(engine.starter(lang));
})();

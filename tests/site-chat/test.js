'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const root = path.resolve(__dirname, '../..');
const engine = require(path.join(root, 'docs/site/chat-engine.js'));
let passed = 0;
function test(name, fn) { fn(); console.log('PASS', name); passed++; }

const cases = [
 ['pt','oi','greeting'], ['pt','Você é humano?','identity'], ['en','Can you access my device?','identity'],
 ['pt','Não funciona congelamento','freeze'], ['pt','Não congela','freeze'],
 ['pt','Por que não aparece notificação?','notifications'], ['en','How do I enable notifications?','notifications'],
 ['en','Where do I download the APK?','install'], ['pt','onde baixar a versão atual?','release'],
 ['pt','Meu celular está esquentando','heat'], ['pt','O que mudou na 1.11.16?','release'],
 ['en','Privacy and personal data','privacy'], ['pt','Qual a velocidade da luz?','unknown']
];
for (const [lang, question, expected] of cases)
  test('intent ' + lang + ' ' + question, () => assert.equal(engine.reply(question, {}, lang).topic || 'unknown', expected));
test('contextual follow-up', () => {
  const r = engine.reply('Como faço?', {topic:'notifications',turn:1}, 'pt');
  assert.equal(r.topic,'notifications'); assert.match(r.text,/Enviar notificação de teste/);
});
test('links are verified GitHub URLs', () => {
  for (const q of ['Como instalar?','O que mudou na 1.11.16?','erro no app']) {
    for (const l of engine.reply(q,{},'pt').links)
      assert.match(l.url, /^https:\/\/github\.com\/langraficagr-collab\/adaptive-performance(?:\/|$)/);
  }
});
test('unknown questions never trigger hallucinated app state', () => {
  assert.match(engine.reply('Você consegue ver minha tela ao vivo?',{},'pt').text,/acessar|resposta confiável|tela/);
});
for (const lang of ['index.html','en.html']) {
  test('site HTML ' + lang, () => {
    const src = fs.readFileSync(path.join(root,'docs',lang),'utf8');
    for (const id of ['faqBot','faqPanel','faqMsgs','chatClear','chatSpeak','chatSuggestions','faqInput','faqForm','chatSend'])
      assert.equal((src.match(new RegExp('id="'+id+'"','g'))||[]).length,1,'duplicate/missing: '+id);
    for (const asset of ['site/chat-engine.js','site/chat-ui.js','site/chat-ui.css']) assert.ok(src.includes(asset),asset);
    assert.ok(!src.includes('function ans(q)'), 'legacy keyword-only bot must be removed');
    assert.ok(src.toLowerCase().includes('assistente virtual') || src.toLowerCase().includes('virtual assistant'));
  });
}
// Minimal DOM for end-to-end widget events, without a third-party browser dependency.
class Element {
  constructor(tag, owner) {this.tagName=tag;this.ownerDocument=owner;this.children=[];this.listeners={};this.attributes={};this.hidden=false;this.disabled=false;this.value='';this.className='';this._text='';this.id='';this.scrollHeight=1;}
  set textContent(s){this._text=String(s);this.children=[];}
  get textContent(){return this._text+this.children.map(c=>c.textContent).join('');}
  setAttribute(k,v){this.attributes[k]=String(v);}
  getAttribute(k){return this.attributes[k];}
  append(...items){for(const x of items) this.children.push(x);return this;}
  appendChild(x){this.children.push(x);return x;}
  replaceChildren(...items){this.children=items;}
  addEventListener(event, fn){(this.listeners[event] ||= []).push(fn);}
  click(){for(const fn of this.listeners.click||[])fn({preventDefault(){}});}
  dispatch(event, value){for(const fn of this.listeners[event]||[])fn(value||{preventDefault(){}});}
  focus(){this.ownerDocument.active=this;}
  setCustomValidity(s){this.validationError=s;}
  reportValidity(){return !this.validationError;}
  remove(){const node=this.ownerDocument.findParent(this); if(node)node.children=node.children.filter(n=>n!==this);}
}
class Document {
  constructor(lang){this.documentElement={lang};this.items={};this.listeners={};this.active=null;}
  createElement(tag){return new Element(tag,this);}
  createTextNode(text){const el=new Element('#text',this);el.textContent=text;return el;}
  getElementById(id){if(this.items[id])return this.items[id];for(const x of Object.values(this.items)){const found=this.search(x,id);if(found)return found;}return null;}
  search(el,id){if(el.id===id)return el;for(const child of el.children){const r=this.search(child,id);if(r)return r;}return null;}
  findParent(item){for(const p of Object.values(this.items))if(p.children.includes(item))return p;return null;}
  addEventListener(e,fn){(this.listeners[e]||=[]).push(fn);}
}
async function testUi(locale) {
  const document = new Document(locale);
  for (const id of ['faqPanel','faqBot','faqClose','chatClear','chatSpeak','faqMsgs','chatSuggestions','faqForm','faqInput','chatSend']) {
    document.items[id]=document.createElement(id === 'faqInput'?'input':'div');
    document.items[id].id=id;
  }
  document.items.faqPanel.hidden=true;
  const store = new Map();
  const context = {window:{AdaptiveChatEngine:engine},document,sessionStorage:{setItem:(k,v)=>store.set(k,v),getItem:k=>store.get(k)||null,removeItem:k=>store.delete(k)},setTimeout,clearTimeout,console};
  vm.createContext(context);
  vm.runInContext(fs.readFileSync(path.join(root,'docs/site/chat-ui.js'),'utf8'),context);
  assert.equal(document.items.faqMsgs.children.length,1,'welcome should be rendered');
  document.items.faqBot.click();
  assert.equal(document.items.faqPanel.hidden,false,'open panel');
  const input=document.items.faqInput;input.value=locale==='pt'?'As notificações não aparecem':'How do I enable notifications?';
  document.items.faqForm.dispatch('submit');
  await new Promise(resolve=>setTimeout(resolve,350));
  const msgs=document.items.faqMsgs;
  assert.equal(msgs.children.length,3, 'welcome, question, reply');
  assert.match(msgs.textContent,locale==='pt'?/Enviar notificação de teste/:/Send test notification/);
  assert.ok(store.has('adaptive-site-chat-v2-'+locale));
  input.value=locale==='pt'?'Como faço?':'How do I do that?';document.items.faqForm.dispatch('submit');
  await new Promise(resolve=>setTimeout(resolve,350));
  assert.equal(msgs.children.length,5,'contextual follow-up');
  document.items.chatClear.click();
  assert.equal(msgs.children.length,1,'clear chat');
  assert.ok(!msgs.textContent.includes('notificações não aparecem'));
  document.items.faqClose.click();assert.equal(document.items.faqPanel.hidden,true);
  console.log('PASS widget end-to-end',locale);passed++;
}
(async()=>{await testUi('pt');await testUi('en');console.log('TOTAL PASSED:',passed);})().catch(e=>{console.error(e);process.exit(1)});

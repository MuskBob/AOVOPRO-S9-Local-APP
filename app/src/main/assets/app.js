'use strict';
(() => {
  const $ = id => document.getElementById(id);
  const native = () => typeof window.S9Native !== 'undefined';
  const state = {
    demo: false, page: 'home', sheet: null, unit: 'km',
    ble: { phase: 'idle', message: '点击连接，查找附近的滑板车', scanning: false, devices: [], services: [], name: '', address: '', battery: null },
    sample: null
  };
  let previousFocus, toastTimer;
  const adjustments = new Map();
  const adjustSpecs = {101:{min:5,max:15,step:1},102:{min:10,max:24,step:1},103:{min:20,max:31,step:1},104:{min:5,max:90,step:5}};
  const adjustmentPending = () => [...adjustments.values()].some(d=>d.pending);
  const adjustValue = id => state.demo?(id===104?state.sample.shutdown:state.sample.speeds[id-101]):state.ble.datapoints?.[id];
  const adjustText = (id,value) => Number.isFinite(value)?id===104?`${value} 分钟`:speed(value):'—';
  function renderAdjustment(id,enabled){
    const spec=adjustSpecs[id],actual=adjustValue(id),draft=adjustments.get(id),value=draft?draft.value:actual;
    $(id===104?'shutdown-setting':'speed-setting-'+id).disabled=!enabled;
    setText(id===104?'shutdown-value':'speed-value-'+id,adjustText(id,actual));
    const range=$('adjust-'+id);if(!range)return;
    $('adjust-field-'+id).disabled=!enabled;
    range.value=Number.isFinite(value)?value:spec.min;
    range.setAttribute('aria-valuetext',adjustText(id,value)+(draft?draft.pending?'，等待确认':'，调节中':''));
    setText('adjust-value-'+id,adjustText(id,value));
    setText('adjust-status-'+id,draft?(draft.pending?'等待车辆确认':'松手后设置'):(state.demo?'示例设置':Number.isFinite(actual)?'车辆已回报':'等待车辆数据'));
    $('adjust-minus-'+id).disabled=!enabled||!Number.isFinite(value)||value<=spec.min;
    $('adjust-plus-'+id).disabled=!enabled||!Number.isFinite(value)||value>=spec.max;
    range.style.setProperty('--progress',`${Number.isFinite(value)?(value-spec.min)/(spec.max-spec.min)*100:0}%`);
  }
  function commitAdjustment(id,value){
    const spec=adjustSpecs[id],actual=adjustValue(id);
    adjustments.delete(id);
    if(!Number.isFinite(actual)||(!state.demo&&(!state.ble.controlReady||state.ble.commandPending||adjustmentPending()))){render();return;}
    value=Math.max(spec.min,Math.min(spec.max,spec.min+Math.round((value-spec.min)/spec.step)*spec.step));
    if(value===actual){render();return;}
    if(state.demo){if(id===104)state.sample.shutdown=value;else state.sample.speeds[id-101]=value;}
    else if(native()){adjustments.set(id,{value,pending:true});call('control',id,value);}
    render();
  }
  function openAdjustment(id){
    if($(id===104?'shutdown-setting':'speed-setting-'+id).disabled)return;
    const spec=adjustSpecs[id],label=id===104?'自动关机时间':`${id-100} 档限速`;
    openSheet('adjust',label+(state.demo?' · 预览':''));
    $('sheet-content').innerHTML=`<fieldset class="adjust-card" id="adjust-field-${id}">
      <div class="adjust-heading"><label for="adjust-${id}">${id===104?'每次调整 5 分钟':'左右滑动调节'}<small>${adjustText(id,spec.min)} — ${adjustText(id,spec.max)}</small></label><output id="adjust-value-${id}" for="adjust-${id}">—</output></div>
      <div class="adjust-track"><button type="button" id="adjust-minus-${id}" aria-label="减少${label}">−</button><input type="range" id="adjust-${id}" min="${spec.min}" max="${spec.max}" step="${spec.step}" aria-label="${label}" aria-describedby="adjust-status-${id}"><button type="button" id="adjust-plus-${id}" aria-label="增加${label}">+</button></div>
      <p class="adjust-status" id="adjust-status-${id}">等待车辆数据</p></fieldset>`;
    const range=$('adjust-'+id);
    range.oninput=()=>{if(range.matches(':disabled'))return;adjustments.set(id,{value:Number(range.value),pending:false});renderAdjustment(id,true);};
    range.onchange=()=>{if(!range.matches(':disabled'))commitAdjustment(id,Number(range.value));};
    const cancel=()=>{if(adjustments.get(id)?.pending===false){adjustments.delete(id);render();}};
    range.onpointercancel=cancel;range.onblur=cancel;
    $('adjust-minus-'+id).onclick=()=>commitAdjustment(id,adjustValue(id)-spec.step);
    $('adjust-plus-'+id).onclick=()=>commitAdjustment(id,adjustValue(id)+spec.step);
    render();focusSheet();
  }
  const sample = () => ({ battery: 82, trip: 3.6, total: 86.2, locked: false, light: false, cruise: false, zeroStart: false, gear: 2, speeds: [12,18,25], shutdown: 5 });
  const esc = value => String(value).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  const icon = name => `<svg aria-hidden="true"><use href="#i-${name}"/></svg>`;
  const setText = (id, value) => { $(id).textContent = value; };
  const distance = km => (state.unit === 'mi' ? km / 1.609344 : km).toFixed(1);
  const speed = kmh => (state.unit === 'mi' ? kmh / 1.609344 : kmh).toFixed(state.unit === 'mi' ? 1 : 0) + (state.unit === 'mi' ? ' mph' : ' km/h');
  function call(method, ...args) {
    if (!native()) { toast('浏览器只能预览界面；扫描和连接请使用安卓 APK。'); return; }
    if (state.demo && method !== 'disconnect' && method !== 'saveUnit' && method !== 'preview') return;
    try { window.S9Native[method](...args); } catch (error) { toast('操作未完成，请重试。'); }
  }
  function toast(message) {
    clearTimeout(toastTimer); $('toast').textContent = message; $('toast').hidden = false;
    toastTimer = setTimeout(() => { $('toast').hidden = true; }, 4200);
  }
  function render() {
    const demo = state.demo, b = state.ble, ready=b.controlReady===true && b.phase==='ready';
    const dp=ready?(b.datapoints||{}):{};
    const saved=b.lastKnown||{};
    const d=demo?state.sample:{battery:b.battery,trip:Number.isFinite(dp[5])?dp[5]/10:null,total:Number.isFinite(dp[12])?dp[12]/10:null,locked:dp[1]===undefined?undefined:!dp[1],light:dp[8],cruise:dp[13],zeroStart:dp[16]===undefined?undefined:dp[16]===0,gear:dp[15],speeds:[dp[101],dp[102],dp[103]],shutdown:dp[104]};
    const available=demo||ready, enabled=id=>demo||(ready&&!b.commandPending&&!adjustmentPending()&&(id===8||Object.hasOwn(dp,id)));
    const linked = b.transportConnected === true;
    const batteryLive=linked&&Number.isFinite(b.battery)&&b.battery>=0&&b.battery<=100;
    const battery = demo ? d.battery : batteryLive?b.battery:Number.isFinite(saved[3])&&saved[3]>=0&&saved[3]<=100?saved[3]:null;
    const totalLive=available&&Number.isFinite(d.total), total=totalLive?d.total:Number.isFinite(saved[12])?saved[12]/10:null;
    $('preview-banner').hidden = !demo;
    setText('preview-toggle', demo ? '退出预览' : '界面预览');
    setText('connection-label', demo ? '预览模式' : ({connecting:'连接中',discovering:'识别中',subscribing:'准备中',authenticating:'认证中',ready:'车辆已连接',authentication_unavailable:'车辆未接通'}[b.phase] || '连接车辆'));
    $('connect-button').classList.toggle('connected',!demo&&ready);
    setText('battery-value', battery === null ? '—' : battery);
    $('battery-fill').style.width = battery === null ? '0%' : `${battery}%`;
    setText('battery-note', demo ? '示例电量' : batteryLive ? '来自车辆蓝牙' : battery!==null?'上次记录 · 等待更新':linked ? '尚未读取到电量' : '等待车辆数据');
    const modes = ['行人模式','经济模式','日常模式','运动模式'];
    setText('hero-mode', available&&d.gear!==undefined ? modes[d.gear]||'未知模式' : '骑行模式 · 待读取');
    setText('hero-lock', available&&d.locked!==undefined ? (d.locked ? '已锁车' : '已解锁') : '锁车状态 · 待读取');
    setText('trip-value', available&&Number.isFinite(d.trip) ? distance(d.trip) : '—'); setText('total-value', Number.isFinite(total) ? distance(total) : '—');
    setText('total-label',!totalLive&&Number.isFinite(total)?'总里程 · 上次记录':'总里程');
    document.querySelectorAll('.distance-unit').forEach(el => { el.textContent = state.unit; });
    setText('control-caption', demo ? '预览操作' : b.commandPending?'等待车辆确认':ready?'本地控制':'等待连接');
    Object.entries({lock:1,light:8,cruise:13,start:16}).forEach(([key,id])=>{$('control-'+key).disabled=!enabled(id);});
    $('control-lock').classList.toggle('unlocked',available&&d.locked===false);
    setText('lock-title', available&&d.locked!==undefined ? (d.locked ? '解锁车辆' : '锁定车辆') : '电子锁');
    setText('lock-state', available&&d.locked!==undefined ? (d.locked ? '已锁车' : '已解锁') : '待读取');
    setText('lock-hint', demo ? '点击体验锁车切换' : '查看锁车状态');
    $('control-lock').setAttribute('aria-label',d.locked===undefined?'电子锁 · 状态未读取':d.locked?'解锁车辆':'锁定车辆');
    $('control-light').setAttribute('aria-label',demo?'切换前大灯':(b.lightCommandValue===1?'关闭':'开启')+'前大灯 · 按上次指令切换');
    $('control-lock').classList.toggle('on', available && d.locked===true);
    ['light','cruise'].forEach(key => {
      const on = available && d[key]===true;
      $(key + '-switch').classList.toggle('on', on); $('control-' + key).classList.toggle('on', on);
      $('control-' + key).setAttribute('aria-pressed', String(on));
      setText(key + '-hint', available&&d[key]!==undefined ? (on ? '已开启' : '已关闭')+(demo?' · 预览':'') : key==='light'&&ready?'状态未知 · 点击选择开关':'状态待读取');
    });
    $('light-switch').hidden=!demo;
    if(!demo){
      $('control-light').removeAttribute('aria-pressed');$('control-light').classList.remove('on');
      setText('light-hint',ready?'点击'+(b.lightCommandValue===1?'关灯':'开灯')+' · 实际状态不回传':'连接后可设置灯光');
    }
    $('control-light').classList.toggle('command-on',!demo&&ready&&b.lightCommandValue===1);
    setText('start-chip', available&&d.zeroStart!==undefined ? (d.zeroStart ? '零启动' : '非零启动') : '—');
    setText('start-hint', available&&d.zeroStart!==undefined ? (d.zeroStart ? '静止时可启动' : '滑行后再启动')+(demo?' · 预览':'') : '零启动 / 非零启动');
    document.querySelectorAll('[data-gear]').forEach(el => {
      const active = available && Number(el.dataset.gear) === d.gear;
      el.disabled = !enabled(15); el.classList.toggle('active', active); el.setAttribute('aria-pressed', String(active));
    });
    setText('mode-note', available&&d.gear!==undefined ? `${modes[d.gear]||'未知模式'}${Number.isFinite(d.speeds[d.gear-1])?' · '+speed(d.speeds[d.gear-1]):''}` : '连接后读取当前档位');
    setText('status-message', demo ? '当前所有数据与控制均为界面演示。退出预览后可扫描真实设备。' : b.configured===false?'首次使用请在连接页面导入 s9-connection.json，之后无需登录。':b.message);
    setText('settings-caption', demo ? '仅预览，不写入车辆' : ready?'以车辆回报为准':'等待连接车辆');
    setText('unit-value', state.unit === 'mi' ? '英里' : '千米');
    [101,102,103,104].forEach(id=>renderAdjustment(id,enabled(id)));
    const faults=['正常','通讯故障','霍尔故障','高温保护','控制器故障','低压保护','堵转保护','转把故障','过流保护'];
    setText('fault-value', demo ? '正常 · 示例' : faults[dp[25]]||'未读取');
    setText('voltage-value', demo ? '40.10 V' : Number.isFinite(dp[20])?(dp[20]/100).toFixed(2)+' V':'— V');
    setText('home-voltage-value',$('voltage-value').textContent);
    const temperature=demo?'16.4 ℃':Number.isFinite(dp[18])?(dp[18]/10).toFixed(1)+' ℃':'— ℃';
    setText('temperature-value',temperature);setText('home-temperature-value',temperature);
    setText('current-value', demo ? '0.00 A' : Number.isFinite(dp[21])?(dp[21]/100).toFixed(2)+' A':'— A');
    setText('power-value', demo ? '0 W' : Number.isFinite(dp[22])?dp[22]+' W':'— W');
    setText('home-current-value',$('current-value').textContent);setText('home-power-value',$('power-value').textContent);
    setText('device-subtitle', demo ? '当前为界面预览' : b.name || '尚未选择车辆');
    $('page-home').hidden = state.page !== 'home'; $('page-settings').hidden = state.page !== 'settings';
    ['home','settings'].forEach(page => {
      $('nav-' + page).classList.toggle('active', state.page === page);
      if (state.page === page) $('nav-' + page).setAttribute('aria-current','page'); else $('nav-' + page).removeAttribute('aria-current');
    });
    if (state.sheet === 'scan') renderScan();
    if (state.sheet === 'diagnostics') renderDiagnostics();
  }
  function changePage(page) { for(const [id,draft] of adjustments)if(!draft.pending)adjustments.delete(id);state.page = page; render(); window.scrollTo(0,0); }
  function openSheet(type, title) {
    previousFocus = document.activeElement; state.sheet = type;
    document.querySelector('.sheet').classList.toggle('adjust-sheet',type==='adjust');
    setText('sheet-title', title); $('sheet-content').replaceChildren(); $('modal').hidden = false;
    document.body.style.overflow = 'hidden'; document.querySelector('.sheet').scrollTop = 0;
  }
  // Keep focus inside dialogs, including when a physical keyboard is connected.
  function focusSheet() { document.querySelector('.sheet').focus(); }
  function closeSheet() {
    if (state.sheet === 'scan' && native() && state.ble.scanning) call('stopScan');
    for(const [id,draft] of adjustments)if(!draft.pending)adjustments.delete(id);
    state.sheet = null; $('modal').hidden = true; document.body.style.overflow = '';
    $('sheet-content').replaceChildren();
    if (previousFocus && previousFocus.isConnected) previousFocus.focus();
  }
  function demoMode(enabled) {
    adjustments.clear();
    closeSheet();
    if (native()) call('preview',enabled);
    state.demo = enabled; state.sample = enabled ? sample() : null;
    render();
  }
  function openScan() {
    if (state.demo) { toast('请先退出界面预览，再连接真实车辆。'); return; }
    openSheet('scan','连接滑板车'); renderScan(); focusSheet();
  }
  function renderScan() {
    const b = state.ble, busy = ['connecting','discovering','subscribing','authenticating'].includes(b.phase);
    const connected = b.transportConnected === true;
    $('sheet-content').innerHTML = `<p class="sheet-copy">给车辆开机，靠近手机，并先断开涂鸦 App 的蓝牙连接。选择你的 S9；本应用只连接导入配置对应的车辆。</p>
      <button class="secondary-button" id="import-config" ${busy?'disabled':''}>${b.configured?'重新导入连接配置':'首次使用：导入连接配置'}</button>
      <button class="primary-button" id="scan-action" ${busy?'disabled':''}>${b.scanning?'停止扫描':connected?'重新扫描':'扫描附近设备'}</button>
      <p class="scan-status" role="status">${esc(native() ? b.message : '浏览器仅供界面预览，实际连接请安装 APK。')}</p>
      <div class="device-list">${(b.devices || []).map(device => `<button class="device-option" data-address="${esc(device.address)}" ${busy || !device.connectable ? 'disabled' : ''}><span class="device-icon">${icon('bluetooth')}</span><span class="device-copy"><strong>${esc(device.name)}</strong><small>${esc(device.address)}${!device.connectable?' · 不可连接':''}</small></span><span class="rssi">${esc(device.rssi)} dBm</span></button>`).join('') || '<div class="empty-scan">打开蓝牙，开始查找你的车辆。<br>扫描将在 12 秒后自动停止。</div>'}</div>
      ${connected ? '<button id="scan-diagnostics" class="secondary-button">查看设备诊断</button><button id="disconnect-action" class="secondary-button">断开连接</button>' : ''}`;
    $('scan-action').onclick = () => call(b.scanning ? 'stopScan' : 'scan');
    $('import-config').onclick=()=>call('importConfig');
    document.querySelectorAll('[data-address]').forEach(button => { button.onclick = () => call('connect', button.dataset.address); });
    if ($('disconnect-action')) $('disconnect-action').onclick = () => call('disconnect');
    if ($('scan-diagnostics')) $('scan-diagnostics').onclick = openDiagnostics;
  }
  function openDiagnostics() { openSheet('diagnostics','设备诊断'); renderDiagnostics(); focusSheet(); }
  function renderDiagnostics() {
    const b = state.ble;
    $('sheet-content').innerHTML = `<div class="diagnostic-state"><strong>${state.demo?'界面预览':b.controlReady?'车辆已接通':'车辆未接通'}</strong>底层蓝牙链路：${!state.demo && b.transportConnected === true?'已建立':'未建立'}。<br>车辆认证：${!state.demo&&b.controlReady?'已完成':'未完成'}。<br>车辆控制：${!state.demo&&b.controlReady?'已接入，等待各项状态回报':'不可用'}。</div>
      <div class="detail-grid"><p><span>设备名称</span><span>${esc(b.name || '尚未选择')}</span></p><p><span>${b.transportConnected === true?'服务数量':'上次识别服务数量'}</span><span>${(b.services || []).length}</span></p><p><span>标准电量</span><span>${!state.demo && b.transportConnected === true && Number.isFinite(b.battery) ? `${b.battery}%` : '未读取'}</span></p></div>
      <p class="sheet-copy">诊断文件包含握手阶段、错误和车辆功能值，不含密钥；由你手动保存，不会自动上传。</p>
      <pre class="diagnostic-json">${esc(JSON.stringify(b.services || [],null,2))}</pre>
      <button class="primary-button" id="export-report" ${state.demo || !b.address?'disabled':''}>保存设备诊断</button>`;
    $('export-report').onclick = () => call('exportReport');
  }
  function choices(title, values, selected, onSelect) {
    openSheet('choices',title);
    values.forEach(item => {
      const button = document.createElement('button'); button.className = 'option-row' + (item.value === selected ? ' selected' : '');
      button.innerHTML = `<span>${esc(item.label)}</span>${item.value === selected ? icon('check') : ''}`;
      button.onclick = () => { onSelect(item.value); closeSheet(); render(); };
      $('sheet-content').append(button);
    });
    focusSheet();
  }
  function previewAction(action) { if (!state.demo) return; action(state.sample); render(); }
  function about() {
    openSheet('about','关于 S9 本地');
    $('sheet-content').innerHTML = `<div class="diagnostic-state"><strong>只为这一辆车。</strong>本地蓝牙测试版 · 0.2.10</div><p class="sheet-copy">无需账号，没有联网权限，不收集手机定位。首次导入连接配置后，通过本地蓝牙认证车辆。打开或回到应用时自动连接车辆；连接后通过系统前台服务在后台保持，并在通知栏显示车辆电量、电压与单次里程。</p><p class="sheet-copy">前灯点击即交替发送开关指令，浅色高亮仅表示上次开灯指令已接收，实际灯光状态不回传。通过其他 App 或车身操作灯光后，切换方向可能与灯光不同步。锁车状态按这辆车的实际含义显示。请停车后调整其他设置。</p><p class="sheet-copy">“界面预览”中的数据都是示例。显示单位只保存在手机中，预览操作不会写入车辆。</p><p class="sheet-copy">依据涂鸦 BLE SDK 的设备端格式和车辆官方物模型实现本地协议；加密封包参考开源 ha_tuya_ble。尚需实车验证。本应用与 AOVOPRO 或涂鸦无官方隶属关系。</p>`;
    focusSheet();
  }
  $('preview-toggle').onclick = () => demoMode(!state.demo); $('exit-preview').onclick = () => demoMode(false);
  $('connect-button').onclick = openScan; $('device-setting').onclick = openScan;
  $('nav-home').onclick = () => changePage('home'); $('nav-settings').onclick = () => changePage('settings');
  Object.entries({lock:[1,'locked'],light:[8,'light'],cruise:[13,'cruise']}).forEach(([key,[id,field]])=>{
    $('control-'+key).onclick=()=>{
      if(state.demo){previewAction(d=>{d[field]=!d[field];});return;}
      if(!state.ble.controlReady||state.ble.commandPending)return;
      if(id===8)call('control',8,state.ble.lightCommandValue===1?0:1);
      else call('control',id,state.ble.datapoints[id]?0:1);
    };
  });
  $('control-start').onclick = () => {
    if(state.demo){previewAction(d=>{d.zeroStart=!d.zeroStart;});return;}
    if(!state.ble.controlReady||state.ble.commandPending)return;
    const value=state.ble.datapoints[16];if(value===0||value===1)call('control',16,value===0?1:0);
  };
  document.querySelectorAll('[data-gear]').forEach(button => { button.onclick = () => {if(state.demo)previewAction(d=>{d.gear=Number(button.dataset.gear);});else call('control',15,Number(button.dataset.gear));}; });
  $('unit-setting').onclick = () => choices('显示单位', [{value:'km',label:'千米 · km / km/h'},{value:'mi',label:'英里 · mi / mph'}], state.unit, value => {
    state.unit = value;
    if (native()) call('saveUnit',value);
    else { try { localStorage.setItem('s9-unit',value); } catch (error) { } }
  });
  [101,102,103,104].forEach(id=>{
    $(id===104?'shutdown-setting':'speed-setting-'+id).onclick=()=>openAdjustment(id);
  });
  $('diagnostics-setting').onclick = openDiagnostics; $('about-setting').onclick = about;
  $('notification-setting').onclick=()=>call('notificationSettings');
  $('close-sheet').onclick = closeSheet; $('scrim').onclick = closeSheet;
  document.addEventListener('keydown', event => {
    if (!state.sheet) return;
    if (event.key === 'Escape') { event.preventDefault(); closeSheet(); }
    if (event.key === 'Tab') {
      const elements = Array.from(document.querySelectorAll('.sheet button:not(:disabled), .sheet input:not(:disabled)')).filter(el => el.getClientRects().length);
      const first = elements[0], last = elements[elements.length-1];
      if (!first) return;
      if (event.shiftKey && (document.activeElement === first || document.activeElement === document.querySelector('.sheet'))) { event.preventDefault(); last.focus(); }
      else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus(); }
    }
  });
  window.S9App = {
    receive(event,data) {
      if (event === 'init') { state.unit = data.unit === 'mi' ? 'mi' : 'km'; }
      if (event === 'ble') {
        for(const [id,draft] of adjustments){
          if(!data.controlReady||draft.pending&&(data.datapoints?.[id]===draft.value||data.controlResultId&&data.controlResultId!==state.ble.controlResultId))adjustments.delete(id);
        }
        if(data.controlResultId&&data.controlResultId!==state.ble.controlResultId&&data.controlResult)toast(data.controlResult);
        state.ble = {...state.ble,...data}; if(!data.controlReady)state.ble.datapoints={};
      }
      if (event === 'notice') {if(data.message!=='指令已发送，等待车辆确认')for(const [id,draft] of adjustments)if(draft.pending)adjustments.delete(id);toast(data.message);}
      render();
    },
    back() { if (state.sheet) { closeSheet(); return true; } if (state.page !== 'home') { changePage('home'); return true; } return false; }
  };
  if (!native()) { try { state.unit = localStorage.getItem('s9-unit') === 'mi' ? 'mi' : 'km'; } catch (error) { } }
  render();
})();








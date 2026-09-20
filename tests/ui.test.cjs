const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require(require.resolve('playwright', {paths:[process.env.CODEX_NODE_MODULES, path.resolve(__dirname,'..')].filter(Boolean)}));
const root = path.resolve(__dirname,'..');
const out = path.join(root,'dist'); fs.mkdirSync(out,{recursive:true});
const checks = [];
async function check(name, action) { await action(); checks.push(name); console.log('PASS',name); }
(async () => {
  const browser = await chromium.launch({channel:'msedge',headless:true});
  try {
    const context = await browser.newContext({viewport:{width:393,height:873},deviceScaleFactor:2,isMobile:true,hasTouch:true});
    const page = await context.newPage();
    const closeAdjust=async()=>{if(await page.locator('#modal').isVisible())await page.locator('#close-sheet').click();};
    const openAdjust=async id=>{await closeAdjust();await page.locator(id===104?'#shutdown-setting':'#speed-setting-'+id).click();};
    const errors = []; page.on('pageerror', e => errors.push(e.message));
    await page.goto('http://127.0.0.1:8769/');
    await check('真实模式不显示示例电量，控制不可用', async () => {
      assert.equal(await page.locator('#battery-value').textContent(),'—');
      assert.equal(await page.locator('#control-light').isDisabled(),true);
      assert.equal(await page.locator('#preview-banner').isVisible(),false);
    });
    await page.screenshot({path:path.join(out,'home-real.png'),fullPage:true});
    await page.locator('#preview-toggle').click();
    await check('预览模式明确标记，允许演示交互', async () => {
      assert.equal(await page.locator('#preview-banner').isVisible(),true);
      assert.equal(await page.locator('#battery-value').textContent(),'82');
      await page.locator('#control-light').click();
      assert.equal(await page.locator('#control-light').getAttribute('aria-pressed'),'true');
      await page.locator('#control-start').click();
      assert.equal(await page.locator('#modal').isVisible(),false);
      assert.equal(await page.locator('#start-chip').textContent(),'零启动');
    });
    await page.screenshot({path:path.join(out,'home-preview.png'),fullPage:true});
    await page.locator('#nav-settings').click();
    await check('单位换算与本地保存', async () => {
      await page.locator('#unit-setting').click();
      await page.getByRole('button',{name:'英里 · mi / mph',exact:true}).click();
      assert.equal(await page.locator('#unit-value').textContent(),'英里');
      await page.locator('#nav-home').click();
      assert.equal(await page.locator('#total-value').textContent(),'53.6');
      await page.locator('#nav-settings').click();
      await page.locator('#unit-setting').click();
      await page.getByRole('button',{name:'千米 · km / km/h',exact:true}).click();
    });
    await check('设置预览有效且无真实写入', async () => {
      assert.equal(await page.locator('#page-settings input[type=range]').count(),0);
      await openAdjust(104);
      await page.locator('#adjust-plus-104').click();
      assert.equal(await page.locator('#shutdown-value').textContent(),'10 分钟');
      await openAdjust(101);await page.locator('#adjust-101').fill('10');
      assert.equal(await page.locator('#speed-value-101').textContent(),'10 km/h');
      await closeAdjust();
    });
    await page.screenshot({path:path.join(out,'settings-preview.png'),fullPage:true});
    await check('退出预览清除示例数据与开关状态', async () => {
      await page.locator('#exit-preview').click();
      assert.equal(await page.locator('#voltage-value').textContent(),'— V');
      assert.equal(await page.locator('#shutdown-setting').isDisabled(),true);
      await page.locator('#nav-home').click();
      assert.equal(await page.locator('#battery-value').textContent(),'—');
      assert.equal(await page.locator('#control-light').getAttribute('aria-pressed'),null);
    });
    await check('原生状态：0% 保留；断连清空；控制不因链路连接而启用', async () => {
      await page.evaluate(() => window.S9App.receive('ble',{phase:'authentication_unavailable',transportConnected:true,authenticationState:'not_implemented',controlReady:false,name:'TY',address:'AA:BB:CC:DD:EE:FF',battery:0,services:[]}));
      assert.equal(await page.locator('#battery-value').textContent(),'0');
      assert.equal(await page.locator('#control-lock').isDisabled(),true);
      assert.equal(await page.locator('#connection-label').textContent(),'车辆未接通');
      assert.equal(await page.locator('#connect-button').evaluate(el => el.classList.contains('connected')),false);
      await page.locator('#connect-button').click();
      await page.locator('#scan-diagnostics').click();
      assert.match(await page.locator('.diagnostic-state').textContent(),/底层蓝牙链路：已建立/);
      assert.match(await page.locator('.diagnostic-state').textContent(),/车辆认证：未完成/);
      assert.equal(await page.locator('#export-report').isEnabled(),true);
      await page.evaluate(() => window.S9App.receive('ble',{phase:'idle',transportConnected:false,battery:82,message:'已断开'}));
      assert.match(await page.locator('.diagnostic-state').textContent(),/底层蓝牙链路：未建立/);
      assert.equal(await page.locator('#export-report').isEnabled(),true);
      await page.locator('#close-sheet').click();
      assert.equal(await page.locator('#battery-value').textContent(),'—');
    });
    await check('扫描名称按文本呈现；不会注入 HTML', async () => {
      await page.locator('#connect-button').click();
      await page.evaluate(() => window.S9App.receive('ble',{phase:'idle',devices:[{name:'<img src=x onerror=alert(1)>',address:'AA:BB:CC:DD:EE:FF',rssi:-45,connectable:true}]}));
      assert.equal(await page.locator('.device-copy img').count(),0);
      assert.match(await page.locator('.device-copy strong').textContent(),/^<img/);
      await page.locator('#close-sheet').click();
    });
    await check('返回键关闭弹窗并返回首页', async () => {
      await page.locator('#nav-settings').click(); await page.locator('#about-setting').click();
      assert.equal(await page.evaluate(() => window.S9App.back()),true);
      assert.equal(await page.locator('#modal').isVisible(),false);
      assert.equal(await page.evaluate(() => window.S9App.back()),true);
      assert.equal(await page.evaluate(() => window.S9App.back()),false);
    });
    await check('320 / 393 / 560 宽度无横向溢出', async () => {
      for (const width of [320,393,560]) {
        await page.setViewportSize({width,height:873});
        for (const id of ['nav-home','nav-settings']) {
          await page.locator('#'+id).click();
          assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth),`${width} ${id}`);
        }
      }
    });
    await check('预览操作不调用原生控制或扫描', async () => {
      await page.evaluate(() => { window.nativeCalls=[]; window.S9Native={preview(enabled){nativeCalls.push(['preview',enabled])},disconnect(){nativeCalls.push('disconnect')},scan(){nativeCalls.push('scan')},connect(){nativeCalls.push('connect')},control(id,value){nativeCalls.push([id,value])},importConfig(){nativeCalls.push('import')},exportReport(){nativeCalls.push('export')},saveUnit(){nativeCalls.push('unit')}}; });
      await page.locator('#preview-toggle').click();
      await page.locator('#nav-home').click();
      await page.locator('#control-light').click(); await page.locator('#control-lock').click(); await page.locator('#connect-button').click();
      assert.deepEqual(await page.evaluate(() => window.nativeCalls),[['preview',true]]);
    });
    await page.locator('#preview-toggle').click();
    await check('退出预览通知原生层恢复自动连接',async()=>{assert.deepEqual(await page.evaluate(()=>nativeCalls.at(-1)),['preview',false]);});
    await check('认证过程中控制不可用；导入入口存在',async()=>{
      await page.evaluate(()=>window.S9App.receive('ble',{phase:'authenticating',transportConnected:true,controlReady:false,configured:true,battery:null,datapoints:{8:true}}));
      assert.equal(await page.locator('#control-light').isDisabled(),true);
      assert.equal(await page.locator('#connection-label').textContent(),'认证中');
      await page.locator('#connect-button').click();assert.equal(await page.locator('#import-config').isDisabled(),true);await page.locator('#close-sheet').click();
    });
    await check('前灯点击即发送，确认后交替，失败不改变方向且不伪造状态',async()=>{
      await page.evaluate(()=>window.S9App.receive('ble',{phase:'ready',transportConnected:true,controlReady:true,commandPending:false,lightCommandValue:-1,datapoints:{3:82},battery:82}));
      assert.equal(await page.locator('#connection-label').textContent(),'车辆已连接');
      assert.equal(await page.locator('#control-light').isEnabled(),true);
      assert.equal(await page.locator('#control-lock').isDisabled(),true);
      assert.equal(await page.locator('#trip-value').textContent(),'—');
      await page.evaluate(()=>{nativeCalls=[];});
      await page.locator('#control-light').click();
      assert.deepEqual(await page.evaluate(()=>nativeCalls),[[8,1]]);
      assert.equal(await page.locator('#modal').isVisible(),false);
      assert.equal(await page.locator('#control-light').getAttribute('aria-pressed'),null);
      assert.match(await page.locator('#light-hint').textContent(),/实际状态不回传/);
      await page.evaluate(()=>window.S9App.receive('ble',{phase:'ready',controlReady:true,commandPending:true}));
      assert.equal(await page.locator('#control-light').isDisabled(),true);
      await page.evaluate(()=>window.S9App.receive('ble',{phase:'ready',controlReady:true,commandPending:false,lightCommandValue:-1}));
      await page.locator('#control-light').click();
      assert.deepEqual(await page.evaluate(()=>nativeCalls),[[8,1],[8,1]]);
      await page.evaluate(()=>window.S9App.receive('ble',{phase:'ready',controlReady:true,commandPending:false,lightCommandValue:1}));
      assert.match(await page.locator('#control-light').getAttribute('aria-label'),/^关闭/);
      await page.locator('#control-light').click();
      assert.deepEqual(await page.evaluate(()=>nativeCalls),[[8,1],[8,1],[8,0]]);
      await page.evaluate(()=>window.S9App.receive('ble',{phase:'ready',controlReady:true,commandPending:false,lightCommandValue:0}));
      await page.locator('#control-light').click();
      assert.deepEqual(await page.evaluate(()=>nativeCalls.at(-1)),[8,1]);
    });
    await check('复现用户状态：速度零且缺少前灯状态时，锁车巡航可操作、前灯显示未知',async()=>{
      await page.evaluate(()=>window.S9App.receive('ble',{phase:'ready',controlReady:true,transportConnected:true,commandPending:false,battery:55,datapoints:{1:true,2:0,3:55,13:true,15:3,16:0,12:1191}}));
      assert.equal(await page.locator('#control-lock').isEnabled(),true);assert.equal(await page.locator('#control-cruise').isEnabled(),true);
      assert.equal(await page.locator('#lock-title').textContent(),'锁定车辆');assert.match(await page.locator('#light-hint').textContent(),/实际状态不回传/);
    });
    await check('锁车状态按实际车辆反转，两个方向发送正确原始值',async()=>{
      await page.evaluate(()=>{nativeCalls=[];window.S9App.receive('ble',{phase:'ready',controlReady:true,commandPending:false,datapoints:{1:true,2:0}});});
      assert.equal(await page.locator('#lock-state').textContent(),'已解锁');await page.locator('#control-lock').click();
      assert.deepEqual(await page.evaluate(()=>nativeCalls),[[1,0]]);
      await page.evaluate(()=>window.S9App.receive('ble',{phase:'ready',controlReady:true,commandPending:false,datapoints:{1:false,2:0}}));
      assert.equal(await page.locator('#lock-state').textContent(),'已锁车');assert.equal(await page.locator('#lock-title').textContent(),'解锁车辆');
      await page.locator('#control-lock').click();assert.deepEqual(await page.evaluate(()=>nativeCalls),[[1,0],[1,1]]);
    });
    await check('真实状态缩放正确；控制发往核对过的 DP',async()=>{
      await page.evaluate(()=>{nativeCalls=[];window.S9App.receive('ble',{phase:'ready',transportConnected:true,controlReady:true,commandPending:false,battery:82,datapoints:{1:false,2:0,3:82,5:36,8:false,12:862,13:false,15:3,16:1,20:4010,21:0,22:0,25:0,101:12,102:18,103:25,104:5}});});
      assert.equal(await page.locator('#trip-value').textContent(),'3.6');assert.equal(await page.locator('#total-value').textContent(),'86.2');
      assert.equal(await page.locator('#start-chip').textContent(),'非零启动');
      await page.locator('#control-light').click();assert.deepEqual(await page.evaluate(()=>nativeCalls),[[8,1]]);
      assert.equal(await page.locator('#control-light').getAttribute('aria-pressed'),null);
      await page.locator('#control-start').click();
      assert.deepEqual(await page.evaluate(()=>nativeCalls),[[8,1],[16,0]]);
      assert.equal(await page.locator('#modal').isVisible(),false);
      await page.locator('#nav-settings').click();assert.equal(await page.locator('#voltage-value').textContent(),'40.10 V');
    });
    await check('限速滑条遵循车型范围，松手发送一次并标注待确认',async()=>{
      await openAdjust(101);
      assert.equal(await page.locator('#adjust-101').getAttribute('min'),'5');
      assert.equal(await page.locator('#adjust-101').getAttribute('max'),'15');
      const count=await page.evaluate(()=>nativeCalls.length);
      await page.locator('#adjust-101').evaluate(el=>{el.value='14';el.dispatchEvent(new Event('input',{bubbles:true}));});
      assert.equal(await page.evaluate(()=>nativeCalls.length),count);
      await page.evaluate(()=>window.S9App.receive('ble',{phase:'ready',controlReady:true,datapoints:{101:12,102:18,103:25,104:5,2:0}}));
      assert.equal(await page.locator('#adjust-101').inputValue(),'14');
      await page.locator('#adjust-101').fill('15');
      assert.deepEqual(await page.evaluate(()=>nativeCalls.at(-1)),[101,15]);
      assert.equal(await page.evaluate(()=>nativeCalls.length),count+1);
      assert.equal(await page.locator('#speed-value-101').textContent(),'12 km/h');
      assert.equal(await page.locator('#adjust-value-101').textContent(),'15 km/h');
      assert.equal(await page.locator('#adjust-status-101').textContent(),'等待车辆确认');
      assert.equal(await page.locator('#speed-setting-102').isDisabled(),true);
      await page.evaluate(()=>window.S9App.receive('ble',{phase:'ready',controlReady:true,commandPending:false,datapoints:{101:15,102:18,103:25,104:5,2:0}}));
      assert.equal(await page.locator('#adjust-status-101').textContent(),'车辆已回报');
      assert.equal(await page.locator('#adjust-plus-101').isDisabled(),true);
      await closeAdjust();
    });
    await check('待确认期间阻止重复控制；收到回报后更新',async()=>{
      await page.locator('#nav-home').click();await page.evaluate(()=>window.S9App.receive('ble',{phase:'ready',controlReady:true,commandPending:true}));
      assert.equal(await page.locator('#control-light').isDisabled(),true);
      await page.evaluate(()=>window.S9App.receive('ble',{phase:'ready',controlReady:true,commandPending:false,datapoints:{8:true,3:82}}));
      assert.equal(await page.locator('#control-light').getAttribute('aria-pressed'),null);
      assert.equal(await page.locator('#control-lock').isDisabled(),true);
    });
    await check('真实连接断开后清空遥测和控制状态',async()=>{
      await page.evaluate(()=>window.S9App.receive('ble',{phase:'idle',transportConnected:false,controlReady:false,battery:null}));
      assert.equal(await page.locator('#control-light').isDisabled(),true);assert.equal(await page.locator('#battery-value').textContent(),'—');
      assert.equal(await page.locator('#control-light').getAttribute('aria-pressed'),null);
      await page.locator('#connect-button').click();await page.locator('#import-config').click();assert.equal(await page.evaluate(()=>nativeCalls.at(-1)),'import');await page.locator('#close-sheet').click();
    });
    await check('命令超时显示结果提示，不伪造前灯开启状态',async()=>{
      await page.evaluate(()=>window.S9App.receive('ble',{phase:'ready',controlReady:true,commandPending:false,datapoints:{},controlResultId:1,controlResult:'未收到设置回报，请确认车辆实际状态后重试'}));
      assert.match(await page.locator('#toast').textContent(),/未收到设置回报/);
      assert.match(await page.locator('#light-hint').textContent(),/实际状态不回传/);
    });
    await check('离线显示上次电量和总里程，但不复用历史控制状态',async()=>{
      await page.evaluate(()=>window.S9App.receive('ble',{phase:'idle',transportConnected:false,controlReady:false,battery:null,datapoints:{},lastKnown:{3:80,12:1191}}));
      assert.equal(await page.locator('#battery-value').textContent(),'80');assert.equal(await page.locator('#total-value').textContent(),'119.1');
      assert.match(await page.locator('#battery-note').textContent(),/上次记录/);assert.match(await page.locator('#total-label').textContent(),/上次记录/);
      assert.equal(await page.locator('#control-lock').isDisabled(),true);assert.equal(await page.locator('#trip-value').textContent(),'—');
      await page.evaluate(()=>window.S9App.receive('ble',{phase:'ready',transportConnected:true,controlReady:true,battery:0,commandPending:false,datapoints:{3:0,12:1200,1:true,2:0,15:0,13:false,16:0,101:12,102:18,103:31}}));
      assert.equal(await page.locator('#battery-value').textContent(),'0');assert.equal(await page.locator('#total-value').textContent(),'120.0');assert.equal(await page.locator('#total-label').textContent(),'总里程');
    });
    await check('四种骑行模式发送 0 到 3，限速仍只有三档',async()=>{
      await page.locator('#nav-home').click();await page.evaluate(()=>{nativeCalls=[];});
      assert.equal(await page.locator('[data-gear]').count(),4);assert.equal(await page.locator('#hero-mode').textContent(),'行人模式');
      for(let i=0;i<4;i++)await page.locator(`[data-gear="${i}"]`).click();
      assert.deepEqual(await page.evaluate(()=>nativeCalls),[[15,0],[15,1],[15,2],[15,3]]);
      await page.locator('#nav-settings').click();assert.equal(await page.locator('.speed-setting').count(),3);assert.equal(await page.locator('#modal').isVisible(),false);await page.locator('#nav-home').click();
    });
    await check('紧凑首页无需滚动即可看到全部控制，锁灯卡片为半高图标',async()=>{
      for(const [width,height] of [[360,720],[393,780],[412,820]]){
        await page.setViewportSize({width,height});await page.evaluate(()=>scrollTo(0,0));
        const bounds=await page.evaluate(()=>({mode:document.querySelector('.mode-row').getBoundingClientRect().bottom,nav:document.querySelector('.bottom-nav').getBoundingClientRect().top,lock:document.querySelector('#control-lock').getBoundingClientRect().height}));
        assert.ok(bounds.mode<=bounds.nav,JSON.stringify({width,height,...bounds}));assert.equal(bounds.lock,65);
        assert.equal(await page.locator('#lock-title').isVisible(),false);assert.equal(await page.locator('#light-hint').isVisible(),false);
      }
      await page.setViewportSize({width:393,height:780});await page.screenshot({path:path.join(out,'home-compact.png'),fullPage:true});
    });
    await check('四项遥测首页与设置一致，非零电流功率按物模型换算，缺失不冒充零',async()=>{
      const model=JSON.parse(fs.readFileSync(path.join(root,'app/src/main/assets/vehicle-model.json'),'utf8')).services[0].properties;
      assert.equal(model.find(x=>x.abilityId===21).typeSpec.scale,2);assert.equal(model.find(x=>x.abilityId===22).typeSpec.scale,0);
      await page.evaluate(()=>window.S9App.receive('ble',{phase:'ready',controlReady:true,datapoints:{18:163,20:3650,21:1234,22:450}}));
      assert.equal(await page.locator('#home-voltage-value').textContent(),'36.50 V');
      assert.equal(await page.locator('#voltage-value').textContent(),'36.50 V');
      assert.equal(await page.locator('#home-temperature-value').textContent(),'16.3 ℃');
      assert.equal(await page.locator('#temperature-value').textContent(),'16.3 ℃');
      assert.equal(await page.locator('#home-current-value').textContent(),'12.34 A');assert.equal(await page.locator('#current-value').textContent(),'12.34 A');
      assert.equal(await page.locator('#home-power-value').textContent(),'450 W');assert.equal(await page.locator('#power-value').textContent(),'450 W');
      await page.evaluate(()=>window.S9App.receive('ble',{phase:'ready',controlReady:true,datapoints:{18:0,20:0,21:0,22:0}}));
      assert.equal(await page.locator('#home-temperature-value').textContent(),'0.0 ℃');
      assert.equal(await page.locator('#home-voltage-value').textContent(),'0.00 V');
      assert.equal(await page.locator('#home-current-value').textContent(),'0.00 A');assert.equal(await page.locator('#home-power-value').textContent(),'0 W');
      await page.evaluate(()=>window.S9App.receive('ble',{phase:'idle',controlReady:false,datapoints:{}}));
      assert.equal(await page.locator('#home-temperature-value').textContent(),'— ℃');
      assert.equal(await page.locator('#home-voltage-value').textContent(),'— V');
      assert.equal(await page.locator('#home-current-value').textContent(),'— A');assert.equal(await page.locator('#home-power-value').textContent(),'— W');
    });
    await check('启动模式单击双向切换且未知状态禁用，三档限速独立启用',async()=>{
      await page.evaluate(()=>{nativeCalls=[];window.S9App.receive('ble',{phase:'ready',controlReady:true,commandPending:false,datapoints:{16:0,101:12,2:0}});});
      await page.locator('#control-start').click();assert.deepEqual(await page.evaluate(()=>nativeCalls),[[16,1]]);
      assert.equal(await page.locator('#start-chip').textContent(),'零启动');
      await page.evaluate(()=>window.S9App.receive('ble',{phase:'ready',controlReady:true,datapoints:{16:1,101:12,2:0}}));
      await page.locator('#control-start').click();assert.deepEqual(await page.evaluate(()=>nativeCalls),[[16,1],[16,0]]);
      await page.locator('#nav-settings').click();
      assert.equal(await page.locator('#speed-setting-101').isEnabled(),true);
      assert.equal(await page.locator('#speed-setting-102').isDisabled(),true);
      assert.equal(await page.locator('#speed-setting-103').isDisabled(),true);
      await page.evaluate(()=>window.S9App.receive('ble',{phase:'ready',controlReady:true,datapoints:{2:0}}));
      await page.locator('#nav-home').click();assert.equal(await page.locator('#control-start').isDisabled(),true);
    });
    await check('锁梁沿左侧连续转动，保留同一路径并等待车辆状态',async()=>{
      await page.evaluate(()=>window.S9App.receive('ble',{phase:'ready',controlReady:true,commandPending:false,datapoints:{1:false,2:0}}));
      await page.locator('#lock-shackle').evaluate(async el=>{await Promise.all(el.getAnimations().map(a=>a.finished));window.originalShackle=el;});
      await page.locator('#control-lock').click();assert.equal(await page.locator('#control-lock').evaluate(el=>el.classList.contains('unlocked')),false);
      await page.evaluate(()=>window.S9App.receive('ble',{phase:'ready',controlReady:true,datapoints:{1:true,2:0}}));
      const motion=await page.locator('#lock-shackle').evaluate(el=>{
        const animation=el.getAnimations()[0];if(!animation)return {animated:false};
        animation.pause();animation.currentTime=0;const start=getComputedStyle(el).transform,left=el.getBoundingClientRect().left;
        animation.currentTime=260;const middle=getComputedStyle(el).transform;
        animation.currentTime=520;const end=getComputedStyle(el).transform,endLeft=el.getBoundingClientRect().left;
        animation.finish();return {animated:true,start,middle,end,left,endLeft,same:el===window.originalShackle};
      });
      assert.equal(motion.animated,true);assert.equal(motion.same,true);
      assert.notEqual(motion.middle,motion.start);assert.notEqual(motion.middle,motion.end);assert.ok(motion.endLeft<motion.left,JSON.stringify(motion));
      await page.evaluate(()=>window.S9App.receive('ble',{phase:'ready',controlReady:true,datapoints:{1:false,2:0}}));
      assert.equal(await page.locator('#lock-shackle').evaluate(el=>el.getAnimations().length>0),true);
    });
    await check('四条滑条范围步长一致，加减边界和拒绝回退正确',async()=>{
      await page.locator('#nav-settings').click();
      await page.evaluate(()=>{nativeCalls=[];window.S9App.receive('ble',{phase:'ready',controlReady:true,commandPending:false,datapoints:{101:5,102:10,103:31,104:5,2:0}});});
      const model=JSON.parse(fs.readFileSync(path.join(root,'app/src/main/assets/vehicle-model.json'),'utf8')).services[0].properties;
      for(const id of [101,102,103,104]){
        await openAdjust(id);
        const spec=model.find(x=>x.abilityId===id).typeSpec;
        for(const attr of ['min','max','step'])assert.equal(await page.locator('#adjust-'+id).getAttribute(attr),String(spec[attr]));
        assert.equal(await page.locator('#sheet-content input[type=range]').count(),1);
        if(id===101||id===104)assert.equal(await page.locator('#adjust-minus-'+id).isDisabled(),true);
        if(id===103)assert.equal(await page.locator('#adjust-plus-'+id).isDisabled(),true);
      }
      await page.locator('#adjust-plus-104').click();assert.deepEqual(await page.evaluate(()=>nativeCalls),[[104,10]]);
      assert.equal(await page.locator('#adjust-status-104').textContent(),'等待车辆确认');
      await page.evaluate(()=>window.S9App.receive('notice',{message:'车辆上报速度不为零，请停车后设置'}));
      assert.equal(await page.locator('#shutdown-value').textContent(),'5 分钟');assert.equal(await page.locator('#adjust-104').isEnabled(),true);
      await openAdjust(101);await page.locator('#adjust-plus-101').click();assert.deepEqual(await page.evaluate(()=>nativeCalls.at(-1)),[101,6]);
      await page.evaluate(()=>window.S9App.receive('ble',{phase:'ready',controlReady:true,commandPending:false,datapoints:{101:6,102:10,103:31,104:90,2:0}}));
      await openAdjust(104);assert.equal(await page.locator('#adjust-plus-104').isDisabled(),true);
      await page.locator('#adjust-minus-104').click();assert.deepEqual(await page.evaluate(()=>nativeCalls.at(-1)),[104,85]);
      await page.evaluate(()=>window.S9App.receive('ble',{phase:'ready',controlReady:true,commandPending:false,controlResultId:9,controlResult:'未收到设置回报，请确认车辆实际状态后重试',datapoints:{101:6,102:10,103:31,104:90,2:0}}));
      assert.equal(await page.locator('#shutdown-value').textContent(),'90 分钟');assert.equal(await page.locator('#adjust-status-104').textContent(),'车辆已回报');
      await closeAdjust();
    });
    await check('取消拖动或断连不发送，英里显示仍使用车型公里步长',async()=>{
      await page.evaluate(()=>{nativeCalls=[];window.S9App.receive('init',{unit:'mi'});});
      await openAdjust(102);
      await page.locator('#adjust-102').evaluate(el=>{el.value='20';el.dispatchEvent(new Event('input',{bubbles:true}));});
      assert.equal(await page.locator('#adjust-value-102').textContent(),'12.4 mph');assert.deepEqual(await page.evaluate(()=>nativeCalls),[]);
      await page.locator('#adjust-102').dispatchEvent('pointercancel');assert.equal(await page.locator('#adjust-102').inputValue(),'10');
      await page.locator('#adjust-plus-102').click();assert.deepEqual(await page.evaluate(()=>nativeCalls),[[102,11]]);
      await page.evaluate(()=>window.S9App.receive('ble',{phase:'ready',controlReady:true,commandPending:false,datapoints:{101:6,102:11,103:31,104:90,2:0}}));
      await openAdjust(103);
      await page.locator('#adjust-103').evaluate(el=>{el.value='24';el.dispatchEvent(new Event('input',{bubbles:true}));});
      await page.evaluate(()=>window.S9App.receive('ble',{phase:'idle',controlReady:false,commandPending:false,datapoints:{}}));
      assert.equal(await page.locator('#adjust-103').isDisabled(),true);assert.equal(await page.locator('#speed-value-103').textContent(),'—');
      assert.deepEqual(await page.evaluate(()=>nativeCalls),[[102,11]]);await page.evaluate(()=>window.S9App.receive('init',{unit:'km'}));
      await closeAdjust();
      await page.locator('#nav-home').click();
    });
    await page.setViewportSize({width:393,height:780});
    await page.evaluate(()=>{document.querySelector('#toast').hidden=true;window.S9App.receive('ble',{phase:'ready',controlReady:true,transportConnected:true,commandPending:false,lightCommandValue:1,battery:80,name:'demo',message:'车辆已连接，状态来自本地蓝牙',datapoints:{1:true,2:0,3:80,5:0,12:1191,13:false,15:3,16:1,18:164,20:3810,21:0,22:0,25:0,101:12,102:18,103:31,104:5}});});
    await page.locator('#lock-shackle').evaluate(async el=>{await Promise.all(el.getAnimations().map(a=>a.finished));});
    await page.locator('#battery-fill').evaluate(async el=>{await Promise.all(el.getAnimations().map(a=>a.finished));});
    await page.screenshot({path:path.join(out,'home-v029.png'),fullPage:true});
    await page.locator('#nav-settings').click();await page.screenshot({path:path.join(out,'settings-v029.png'),fullPage:true});
    await check('点开设置才显示底部小卡片，关闭丢弃未提交草稿且恢复焦点',async()=>{
      assert.equal(await page.locator('#page-settings input[type=range]').count(),0);
      const before=await page.evaluate(()=>nativeCalls.length);
      await openAdjust(101);
      assert.equal(await page.locator('#sheet-title').textContent(),'1 档限速');
      const bounds=await page.locator('.sheet').boundingBox();assert.ok(bounds.height<350);assert.ok(Math.abs(bounds.y+bounds.height-780)<1);
      await page.locator('#adjust-101').evaluate(el=>{el.value='14';el.dispatchEvent(new Event('input',{bubbles:true}));});
      await page.screenshot({path:path.join(out,'adjustment-v029.png')});
      await page.keyboard.press('Escape');
      assert.equal(await page.locator('#modal').isVisible(),false);assert.equal(await page.locator('#speed-setting-101').evaluate(el=>el===document.activeElement),true);
      assert.equal(await page.evaluate(()=>nativeCalls.length),before);
      await openAdjust(101);assert.equal(await page.locator('#adjust-101').inputValue(),'12');
      await page.evaluate(()=>window.S9App.back());assert.equal(await page.locator('#modal').isVisible(),false);
    });
    await check('无浏览器脚本错误', async () => assert.deepEqual(errors,[]));
    fs.writeFileSync(path.join(out,'ui-test-results.json'),JSON.stringify({passed:checks.length,checks,errors,limitations:'Desktop Chromium WebView-asset tests. No Android runtime or physical S9 test performed.'},null,2));
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode=1; });


const entry=(await listTaskSpaces()).find(space=>space.id===27);
const task=!entry?await taskSpace('DataOcean 反馈处理流程说明'):entry.ownership==='user'?await claimTaskSpace(27):entry.ownership==='agentDelegatedToUser'?await takeOverTaskSpace(27):await taskSpace(27);
for(const tab of await task.tabs())if(!tab.label)await task.adopt(tab.page);
console.log({space:task.spaceId,existing:(await task.tabs()).map(tab=>({label:tab.label,url:tab.url}))});
const page=await task.newPage();await page.goto('http://127.0.0.1:4173/.archify/explainer-feedback-20261115-180917/feedback-flow.html');await page.waitForSelector('h1');await page.cdp('Emulation.setDeviceMetricsOverride',{width:1440,height:1000,deviceScaleFactor:1,mobile:false});
await page.screenshot({path:'D:/projects/DataOcean/.archify/explainer-feedback-20261115-180917/01-page.png'});
console.log({page:page.label});console.log((await page.snapshot()).slice(0,12500));

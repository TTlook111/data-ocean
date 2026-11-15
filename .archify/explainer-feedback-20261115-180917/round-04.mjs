const task=await taskSpace(27),page=task.page('p3');await page.goto('http://127.0.0.1:4173/prototypes/flows/%E5%8F%8D%E9%A6%88%E6%B5%81%E7%A8%8B.html');await page.waitForSelector('h1');
await page.cdp('Emulation.setDeviceMetricsOverride',{width:1440,height:1000,deviceScaleFactor:1,mobile:false});await page.click('a[aria-label="三条路径各自要做什么"]');await page.screenshot({path:'D:/projects/DataOcean/.archify/explainer-feedback-20261115-180917/04-paths-final.png'});
await page.click('a[aria-label="截图里的专业字段到底指什么"]');await page.screenshot({path:'D:/projects/DataOcean/.archify/explainer-feedback-20261115-180917/05-fields.png'});
console.log(await page.evaluate(()=>({title:document.title,replacementGlyph:document.body.textContent.includes('\uFFFD'),diagramCount:document.querySelectorAll('[data-diagram-id]').length,scrollWidth:document.documentElement.scrollWidth,width:innerWidth})));
console.log((await page.snapshot()).slice(-3600));

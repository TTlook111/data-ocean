const task=await taskSpace(27),page=task.page('p3');await page.goto('http://127.0.0.1:4173/.archify/explainer-feedback-20261115-180917/feedback-flow.html?layout=ordered');await page.waitForSelector('h1');await page.cdp('Emulation.setDeviceMetricsOverride',{width:1440,height:1000,deviceScaleFactor:1,mobile:false});
await page.screenshot({path:'D:/projects/DataOcean/.archify/explainer-feedback-20261115-180917/02-ordered-flow.png'});
console.log(await page.evaluate(()=>({width:innerWidth,scrollWidth:document.documentElement.scrollWidth,heading:document.querySelector('h1').textContent,svgCount:document.querySelectorAll('svg').length})));
console.log((await page.snapshot({scope:'full_page'})).slice(-12500));

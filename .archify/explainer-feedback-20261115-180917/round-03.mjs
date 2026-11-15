const task=await taskSpace(27),page=task.page('p3');
await page.click('text="▶ 逐步看"');console.log((await page.snapshot()).slice(-900));
await page.click('a[aria-label="三条路径各自要做什么"]');await page.screenshot({path:'D:/projects/DataOcean/.archify/explainer-feedback-20261115-180917/03-three-paths.png'});
console.log(await page.evaluate(()=>({heading:document.querySelector('#choose-path')?.textContent.slice(0,80),badGlyphs:document.body.textContent.includes('\uFFFD'),horizontalOverflow:document.documentElement.scrollWidth>innerWidth})));

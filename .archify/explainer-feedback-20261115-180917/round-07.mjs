const task=await taskSpace(27),page=task.page('p3');await page.goto('http://127.0.0.1:4173/prototypes/flows/%E5%8F%8D%E9%A6%88%E6%B5%81%E7%A8%8B.html');await page.waitForSelector('h1');await page.click('#whole-process button:has-text("打开交互图")');
console.log((await task.tabs()).map(tab=>({label:tab.label,title:tab.title,url:tab.url.slice(0,140)})));
console.log((await page.snapshot()).slice(-2200));

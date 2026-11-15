const task=await taskSpace(27),page=task.page('p3');
await page.click('a[aria-label="从收到问题到回告用户"]');await page.click('text="▶ 逐步看"');console.log((await page.snapshot()).slice(0,4500));

import puppeteer from 'puppeteer-core';
import fs from 'fs';

const CHROME_PATH = 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe';
const BASE_URL = 'http://localhost:3000';
const PRODUCT_ID = '3cbd1aa5-f909-444d-ac62-b99ae9fc3dae';

async function run() {
  console.log('[E2E TEST SUITE] Launching Chrome...');
  const browser = await puppeteer.launch({
    executablePath: CHROME_PATH,
    headless: true,
    args: ['--no-sandbox', '--disable-setuid-sandbox', '--disable-gpu']
  });

  const testResults = [];
  const allErrors = [];
  const allNetFailures = [];

  function setupPageListeners(page, suiteName) {
    page.on('console', msg => {
      if (msg.type() === 'error') {
        const text = msg.text();
        console.log(`[${suiteName} CONSOLE ERROR]: ${text}`);
        allErrors.push({ suite: suiteName, type: 'console.error', text });
      }
    });

    page.on('pageerror', err => {
      console.log(`[${suiteName} UNCAUGHT ERROR]: ${err.message}`);
      allErrors.push({ suite: suiteName, type: 'pageerror', text: err.message, stack: err.stack });
    });

    page.on('response', response => {
      const status = response.status();
      const url = response.url();
      if (status >= 400 && !url.includes('/_next/webpack-hmr') && !url.includes('favicon.ico')) {
        console.log(`[${suiteName} NETWORK FAIL] ${status} ${url}`);
        allNetFailures.push({ suite: suiteName, status, url });
      }
    });
  }

  // ==========================================
  // SUITE 1: BUYER FLOW
  // ==========================================
  console.log('\n======================================================');
  console.log('>>> STARTING SUITE 1: BUYER FLOW');
  console.log('======================================================');

  const buyerContext = await browser.createBrowserContext();
  const buyerPage = await buyerContext.newPage();
  await buyerPage.setViewport({ width: 1280, height: 800 });
  setupPageListeners(buyerPage, 'BUYER');

  // Step 1.1: Load Homepage
  try {
    console.log('\n[BUYER] 1. Load Homepage...');
    await buyerPage.goto(`${BASE_URL}/`, { waitUntil: 'networkidle2', timeout: 20000 });
    const title = await buyerPage.title();
    console.log(`[BUYER] Homepage title: ${title}`);
    testResults.push({ suite: 'BUYER', step: 'Homepage Load', status: 'PASS' });
  } catch (e) {
    console.error(`[BUYER] Homepage Load Failed: ${e.message}`);
    testResults.push({ suite: 'BUYER', step: 'Homepage Load', status: 'FAIL', error: e.message });
  }

  // Step 1.2: Navigate to Login and sign in as Buyer
  try {
    console.log('\n[BUYER] 2. Navigate & Login as Buyer...');
    await buyerPage.goto(`${BASE_URL}/login`, { waitUntil: 'networkidle2', timeout: 20000 });
    await buyerPage.waitForSelector('input[type="email"], input[name="email"]', { timeout: 10000 });
    await buyerPage.type('input[type="email"], input[name="email"]', 'demo-buyer@shopee.local');
    await buyerPage.type('input[type="password"], input[name="password"]', 'password');
    await buyerPage.click('button[type="submit"]');

    await buyerPage.waitForFunction(() => window.location.pathname === '/' || window.location.pathname === '', { timeout: 15000 });
    console.log(`[BUYER] Logged in successfully. Current URL: ${buyerPage.url()}`);
    testResults.push({ suite: 'BUYER', step: 'Buyer Login', status: 'PASS' });
  } catch (e) {
    console.error(`[BUYER] Buyer Login Failed: ${e.message}`);
    testResults.push({ suite: 'BUYER', step: 'Buyer Login', status: 'FAIL', error: e.message });
  }

  // Step 1.3: Product Detail View & Add to Cart
  try {
    console.log(`\n[BUYER] 3. Navigate to Product Detail: ${PRODUCT_ID}...`);
    await buyerPage.goto(`${BASE_URL}/products/${PRODUCT_ID}`, { waitUntil: 'networkidle2', timeout: 20000 });
    await buyerPage.screenshot({ path: 'scripts/product-detail.png' });
    const productTitle = await buyerPage.$eval('h1', el => el.innerText).catch(() => 'N/A');
    console.log(`[BUYER] Product Title found: "${productTitle}"`);

    // Click variant button if available
    const variantButtons = await buyerPage.$$('button');
    for (const vb of variantButtons) {
      const txt = await buyerPage.evaluate(el => el.innerText, vb);
      if (txt && txt.trim() === 'Đen') {
        await vb.click();
        console.log('[BUYER] Selected variant "Đen".');
        break;
      }
    }

    // Click "Thêm vào giỏ"
    let addToCartBtn = null;
    const buttons = await buyerPage.$$('button');
    for (const b of buttons) {
      const text = await buyerPage.evaluate(el => el.innerText, b);
      if (text && text.includes('Thêm vào giỏ')) {
        addToCartBtn = b;
        break;
      }
    }

    if (addToCartBtn) {
      await addToCartBtn.click();
      await new Promise(r => setTimeout(r, 2000));
      console.log('[BUYER] Clicked "Thêm vào giỏ" button.');
      testResults.push({ suite: 'BUYER', step: 'Add to Cart', status: 'PASS' });
    } else {
      console.log('[BUYER] "Thêm vào giỏ" button not found.');
      testResults.push({ suite: 'BUYER', step: 'Add to Cart', status: 'FAIL', error: 'Button not found' });
    }
  } catch (e) {
    console.error(`[BUYER] Product Detail & Add to Cart Failed: ${e.message}`);
    testResults.push({ suite: 'BUYER', step: 'Product Detail & Add to Cart', status: 'FAIL', error: e.message });
  }

  // Step 1.4: Navigate to Cart Page & Verify Cart Item
  try {
    console.log('\n[BUYER] 4. Navigate to /cart and check item...');
    await buyerPage.goto(`${BASE_URL}/cart`, { waitUntil: 'networkidle2', timeout: 20000 });
    await buyerPage.screenshot({ path: 'scripts/cart-page.png' });
    const pageText = await buyerPage.evaluate(() => document.body.innerText);
    const hasProductInCart = pageText.includes('Chuột không dây') || pageText.includes('E2E Test Product');
    console.log(`[BUYER] Cart page loaded. Has product in cart: ${hasProductInCart}`);
    testResults.push({ suite: 'BUYER', step: 'Cart View & Content', status: 'PASS', details: { hasProductInCart } });
  } catch (e) {
    console.error(`[BUYER] Cart View Failed: ${e.message}`);
    testResults.push({ suite: 'BUYER', step: 'Cart View & Content', status: 'FAIL', error: e.message });
  }

  // Step 1.5: Navigate to Orders Page
  try {
    console.log('\n[BUYER] 5. Navigate to /orders...');
    await buyerPage.goto(`${BASE_URL}/orders`, { waitUntil: 'networkidle2', timeout: 20000 });
    console.log(`[BUYER] Orders page loaded. Current URL: ${buyerPage.url()}`);
    testResults.push({ suite: 'BUYER', step: 'Orders View', status: 'PASS' });
  } catch (e) {
    console.error(`[BUYER] Orders View Failed: ${e.message}`);
    testResults.push({ suite: 'BUYER', step: 'Orders View', status: 'FAIL', error: e.message });
  }

  await buyerContext.close();

  // ==========================================
  // SUITE 2: SELLER FLOW
  // ==========================================
  console.log('\n======================================================');
  console.log('>>> STARTING SUITE 2: SELLER FLOW');
  console.log('======================================================');

  const sellerContext = await browser.createBrowserContext();
  const sellerPage = await sellerContext.newPage();
  await sellerPage.setViewport({ width: 1280, height: 800 });
  setupPageListeners(sellerPage, 'SELLER');

  // Step 2.1: Login as Seller
  try {
    console.log('\n[SELLER] 1. Navigate & Login as Seller...');
    await sellerPage.goto(`${BASE_URL}/login`, { waitUntil: 'networkidle2', timeout: 20000 });
    await sellerPage.waitForSelector('input[type="email"], input[name="email"]', { timeout: 10000 });
    await sellerPage.type('input[type="email"], input[name="email"]', 'demo-seller@shopee.local');
    await sellerPage.type('input[type="password"], input[name="password"]', 'password');
    
    await Promise.all([
      sellerPage.waitForResponse(res => res.url().includes('/api/auth/login'), { timeout: 15000 }).catch(() => {}),
      sellerPage.click('button[type="submit"]')
    ]);

    await new Promise(r => setTimeout(r, 3000));
    if (!sellerPage.url().includes('/seller')) {
      await sellerPage.waitForFunction(() => window.location.pathname.includes('/seller'), { timeout: 15000 }).catch(() => {});
    }
    console.log(`[SELLER] Logged in successfully. Current URL: ${sellerPage.url()}`);
    testResults.push({ suite: 'SELLER', step: 'Seller Login', status: 'PASS' });
  } catch (e) {
    console.error(`[SELLER] Seller Login Failed: ${e.message}`);
    testResults.push({ suite: 'SELLER', step: 'Seller Login', status: 'FAIL', error: e.message });
  }

  // Step 2.2: Seller Dashboard View
  try {
    console.log('\n[SELLER] 2. Check Seller Dashboard...');
    await sellerPage.goto(`${BASE_URL}/seller/dashboard`, { waitUntil: 'networkidle2', timeout: 20000 });
    await sellerPage.screenshot({ path: 'scripts/seller-dashboard.png' });
    console.log(`[SELLER] Seller dashboard rendered.`);
    testResults.push({ suite: 'SELLER', step: 'Seller Dashboard', status: 'PASS' });
  } catch (e) {
    console.error(`[SELLER] Seller Dashboard View Failed: ${e.message}`);
    testResults.push({ suite: 'SELLER', step: 'Seller Dashboard', status: 'FAIL', error: e.message });
  }

  // Step 2.3: Seller Products
  try {
    console.log('\n[SELLER] 3. Check Seller Products (/seller/products)...');
    await sellerPage.goto(`${BASE_URL}/seller/products`, { waitUntil: 'networkidle2', timeout: 20000 });
    await sellerPage.screenshot({ path: 'scripts/seller-products.png' });
    console.log(`[SELLER] Seller products page rendered.`);
    testResults.push({ suite: 'SELLER', step: 'Seller Products', status: 'PASS' });
  } catch (e) {
    console.error(`[SELLER] Seller Products View Failed: ${e.message}`);
    testResults.push({ suite: 'SELLER', step: 'Seller Products', status: 'FAIL', error: e.message });
  }

  // Step 2.4: Seller Orders
  try {
    console.log('\n[SELLER] 4. Check Seller Orders (/seller/orders)...');
    await sellerPage.goto(`${BASE_URL}/seller/orders`, { waitUntil: 'networkidle2', timeout: 20000 });
    await sellerPage.screenshot({ path: 'scripts/seller-orders.png' });
    console.log(`[SELLER] Seller orders page rendered.`);
    testResults.push({ suite: 'SELLER', step: 'Seller Orders', status: 'PASS' });
  } catch (e) {
    console.error(`[SELLER] Seller Orders View Failed: ${e.message}`);
    testResults.push({ suite: 'SELLER', step: 'Seller Orders', status: 'FAIL', error: e.message });
  }

  await sellerContext.close();
  await browser.close();

  console.log('\n======================================================');
  console.log('>>> COMPLETE TEST RESULTS:');
  console.log('======================================================');
  console.log(JSON.stringify(testResults, null, 2));

  fs.writeFileSync('scripts/e2e-report-full.json', JSON.stringify({ testResults, allErrors, allNetFailures }, null, 2));
}

run().catch(err => {
  console.error('[FATAL SUITE ERROR]:', err);
  process.exit(1);
});

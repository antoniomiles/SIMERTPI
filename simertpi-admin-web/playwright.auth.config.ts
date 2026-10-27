import { defineConfig } from '@playwright/test';
import { X509Certificate,createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
const certificate=new X509Certificate(readFileSync('../.local-tools/https/cert.pem'));
const pin=createHash('sha256').update(certificate.publicKey.export({format:'der',type:'spki'})).digest('base64');
const nodeExecutable = `"${process.execPath}"`;
export default defineConfig({
 testDir:'./e2e-auth',fullyParallel:false,workers:1,retries:0,timeout:45000,reporter:[['list']],preserveOutput:'always',
 use:{baseURL:'https://localhost:4443',channel:'chrome',headless:true,ignoreHTTPSErrors:false,launchOptions:{args:[`--ignore-certificate-errors-spki-list=${pin}`]}},
 webServer:[
  {command:`${nodeExecutable} scripts/auth-backend.mjs`,url:'http://127.0.0.1:8089/actuator/health/readiness',timeout:600000,reuseExistingServer:false},
  {command:`${nodeExecutable} node_modules/@angular/cli/bin/ng.js serve --host localhost --port 4443 --configuration development --ssl --ssl-cert ../.local-tools/https/cert.pem --ssl-key ../.local-tools/https/key.pem --proxy-config proxy.local.json`,url:'https://localhost:4443',timeout:360000,reuseExistingServer:false,ignoreHTTPSErrors:true},
 ],
});

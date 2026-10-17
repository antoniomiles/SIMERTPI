import {defineConfig} from '@playwright/test';
import original from './playwright.auth.config';
export default defineConfig({...original,webServer:[
 {command:'node scripts/auth-backend.mjs',url:'http://127.0.0.1:8089/actuator/health/readiness',timeout:180000,reuseExistingServer:false},
 {command:'node deploy/auth-proxy.mjs',url:'https://localhost:4443',timeout:30000,reuseExistingServer:false}
]});

import {test} from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import {mkdtempSync,writeFileSync,rmSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {createPortalServer} from './server.mjs';
test('proxy preserves cookies, CSRF, Origin, body, errors; isolates API and SPA',async()=>{
 const root=mkdtempSync(join(tmpdir(),'simertpi-edge-'));writeFileSync(join(root,'index.html'),'<h1>portal</h1>');
 let received;
 const backend=http.createServer(async(req,res)=>{let body='';for await(const c of req)body+=c;received={url:req.url,headers:req.headers,body};res.writeHead(403,{'Set-Cookie':['__Host-SIMERTPI_ADMIN_SESSION=fixture; Path=/; Secure; HttpOnly; SameSite=Strict','__Host-SIMERTPI_ADMIN_XSRF=fixture; Path=/; Secure; SameSite=Strict'],'Cache-Control':'public'});res.end('csrf rejected');});
 await new Promise(r=>backend.listen(0,'127.0.0.1',r));
 const edge=createPortalServer({root,upstream:'http://127.0.0.1:'+backend.address().port,allowLoopback:true});
 await new Promise(r=>edge.listen(0,'127.0.0.1',r));
 const base='http://127.0.0.1:'+edge.address().port;
 try{
 const response=await fetch(base+'/api/v1/admin/auth/logout?test=1',{method:'POST',headers:{cookie:'fixture=opaque','X-XSRF-TOKEN':'fixture-csrf',Origin:'https://admin.example.invalid','Content-Type':'application/json'},body:'{}'});
 assert.equal(response.status,403);assert.equal(await response.text(),'csrf rejected');assert.equal(response.headers.get('cache-control'),'no-store');assert.equal(response.headers.getSetCookie().length,2);
 assert.equal(received.headers.cookie,'fixture=opaque');assert.equal(received.headers['x-xsrf-token'],'fixture-csrf');assert.equal(received.headers.origin,'https://admin.example.invalid');assert.equal(received.body,'{}');assert.equal(received.url,'/api/v1/admin/auth/logout?test=1');
 assert.equal((await fetch(base+'/api/v1/municipal/auth/login',{method:'POST'})).status,404);
 assert.equal((await fetch(base+'/api')).status,404);
 for(const path of ['https://untrusted.invalid/api/v1/admin/auth/me','//untrusted.invalid/api/v1/admin/auth/me'])assert.equal(await new Promise((resolve,reject)=>{const req=http.request({host:'127.0.0.1',port:edge.address().port,path},res=>{res.resume();resolve(res.statusCode);});req.on('error',reject);req.end();}),400);
 assert.equal((await fetch(base+'/api/v1/admin/auth/me',{headers:{Authorization:'Basic fixture'}})).status,401);
 assert.match(await(await fetch(base+'/admin/personal')).text(),/portal/);
 assert.equal((await fetch(base+'/missing.js')).status,404);
 assert.equal((await fetch(base+'/login',{method:'POST'})).status,405);
 await new Promise(r=>backend.close(r));
 assert.equal((await fetch(base+'/api/v1/admin/auth/me')).status,502);
 }finally{edge.closeAllConnections();await new Promise(r=>edge.close(r));backend.close();rmSync(root,{recursive:true});}
});
test('deployment rejects HTTP upstream and credentials in URL',()=>{
 for(const upstream of ['http://example.invalid','https://user:secret@example.invalid','https://example.invalid/path'])assert.throws(()=>createPortalServer({root:'.',upstream}));
});

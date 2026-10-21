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

async function withProxy({backendHandler, authTimeoutMs=100, upstreamTimeoutMs=35, logger={warn(){}}}, run) {
 const root=mkdtempSync(join(tmpdir(),'simertpi-timeout-'));
 writeFileSync(join(root,'index.html'),'<h1>portal</h1>');
 const backend=http.createServer(backendHandler);
 await new Promise(r=>backend.listen(0,'127.0.0.1',r));
 const edge=createPortalServer({root,upstream:'http://127.0.0.1:'+backend.address().port,allowLoopback:true,authTimeoutMs,upstreamTimeoutMs,logger});
 await new Promise(r=>edge.listen(0,'127.0.0.1',r));
 try { await run('http://127.0.0.1:'+edge.address().port); }
 finally { edge.closeAllConnections(); await new Promise(r=>edge.close(r)); backend.closeAllConnections(); await new Promise(r=>backend.close(r)); rmSync(root,{recursive:true}); }
}

test('slow auth response succeeds within its longer cold-start budget; genuine 401 and 403 pass through',async()=>{
 let hits=0;
 await withProxy({authTimeoutMs:180,backendHandler:(req,res)=>{
  hits++;
  if(req.url.endsWith('/csrf')) return setTimeout(()=>{res.writeHead(204,{'Set-Cookie':'__Host-SIMERTPI_ADMIN_XSRF=csrf; Path=/; Secure; SameSite=Strict'});res.end();},80);
  if(req.url.endsWith('/login')) return setTimeout(()=>{res.writeHead(200,{'Content-Type':'application/json','Set-Cookie':'__Host-SIMERTPI_ADMIN_SESSION=opaque; Path=/; Secure; HttpOnly; SameSite=Strict'});res.end('{"username":"fixture.admin"}');},80);
  const status=req.url.endsWith('/me')?401:403;
  res.writeHead(status,{'Content-Type':'application/json'});res.end(JSON.stringify({message:status===401?'expired':'forbidden'}));
 }},async base=>{
  const csrf=await fetch(base+'/api/v1/admin/auth/csrf');
  assert.equal(csrf.status,204);assert.match(csrf.headers.get('set-cookie'),/Secure/);
  const login=await fetch(base+'/api/v1/admin/auth/login',{method:'POST',headers:{'Content-Type':'application/json'},body:'{"username":"fixture.admin","password":"unit-test-only"}'});
  assert.equal(login.status,200);assert.match(login.headers.get('set-cookie'),/HttpOnly/);assert.match(login.headers.get('set-cookie'),/SameSite=Strict/);
  assert.equal((await fetch(base+'/api/v1/admin/auth/me')).status,401);
  assert.equal((await fetch(base+'/api/v1/admin/auth/logout',{method:'POST'})).status,403);
  assert.equal(hits,4);
 });
});

test('auth timeout is explicit 504, does not echo secrets, and a later manual request recovers without retries',async()=>{
 let hits=0, ready=false;
 const cookie='opaque-session-never-log', csrf='csrf-never-log', password='password-never-log';
 await withProxy({authTimeoutMs:35,backendHandler:(req,res)=>{
  hits++;
  if(!ready) return; // Simulate a sleeping/upstarting upstream.
  res.writeHead(204,{'Set-Cookie':'__Host-SIMERTPI_ADMIN_XSRF=ready; Path=/; Secure; SameSite=Strict'});res.end();
 }},async base=>{
  const first=await fetch(base+'/api/v1/admin/auth/login',{method:'POST',headers:{cookie,'X-XSRF-TOKEN':csrf,'Content-Type':'application/json'},body:JSON.stringify({username:'fixture',password})});
  assert.equal(first.status,504);assert.equal(first.headers.get('retry-after'),'5');
  const errorBody=await first.text();
  for(const secret of [cookie,csrf,password]) assert.equal(errorBody.includes(secret),false);
  assert.equal(hits,1,'proxy must not retry a login POST');
  ready=true;
  const recovered=await fetch(base+'/api/v1/admin/auth/csrf');
  assert.equal(recovered.status,204);assert.equal(hits,2,'recovery occurs only after a new client request');
 });
});

test('non-auth upstream timeout maps to 504 while connection failures map to 502',async()=>{
 await withProxy({upstreamTimeoutMs:30,backendHandler:(req,res)=>{}},async base=>{
  const timeoutResponse=await fetch(base+'/api/v1/admin/audit');
  assert.equal(timeoutResponse.status,504);
  assert.equal((await timeoutResponse.json()).code,'UPSTREAM_TIMEOUT');
 });
 // A well-formed upstream that closes before responding represents connection loss.
 await withProxy({backendHandler:(req,res)=>req.socket.destroy()},async base=>{
  const unavailable=await fetch(base+'/api/v1/admin/auth/csrf');
  assert.equal(unavailable.status,502);
  assert.equal((await unavailable.json()).code,'UPSTREAM_UNAVAILABLE');
  assert.equal(unavailable.headers.get('x-admin-proxy-error'),'UPSTREAM_UNAVAILABLE');
 });
 await withProxy({backendHandler:(req,res)=>{res.writeHead(502,{'Content-Type':'application/json'});res.end('{"message":"upstream gateway response"}');}},async base=>{
  const upstreamError=await fetch(base+'/api/v1/admin/auth/csrf');
  assert.equal(upstreamError.status,502);
  assert.equal(upstreamError.headers.get('x-admin-proxy-error'),null);
  assert.equal(await upstreamError.text(),'{"message":"upstream gateway response"}');
 });
});

test('proxy failure logs are correlated and exclude query strings, cookies and CSRF values',async()=>{
 const events=[];
 const sensitive=['proxy-cookie-never-log','proxy-csrf-never-log','query-secret-never-log','proxy-password-never-log'];
 await withProxy({upstreamTimeoutMs:30,logger:{warn:event=>events.push(event)},backendHandler:(req,res)=>{}},async base=>{
  const correlationId='portal-correlation-2026-1';
  const response=await fetch(base+'/api/v1/admin/parking/tariffs/11111111-1111-4111-8111-111111111111/end?key='+sensitive[2],{
   method:'POST',headers:{cookie:sensitive[0],'X-XSRF-TOKEN':sensitive[1],'X-Correlation-ID':correlationId,'Content-Type':'application/json'},
   body:JSON.stringify({password:sensitive[3]})
  });
  assert.equal(response.status,504);
  assert.equal(response.headers.get('x-correlation-id'),correlationId);
  assert.equal(events.length,1);
  assert.match(events[0],/event=admin_proxy_upstream_failure/);
  assert.match(events[0],/method=POST/);
  assert.match(events[0],/route=\/api\/v1\/admin\/parking\/tariffs\/\{id\}\/end/);
  assert.match(events[0],/status=504/);
  assert.match(events[0],/reason=UPSTREAM_TIMEOUT/);
  assert.match(events[0],/correlationId=portal-correlation-2026-1/);
  for(const secret of sensitive) assert.equal(events[0].includes(secret),false);
 });
});

test('proxy logs normalize plate and username path values',async()=>{
 const events=[];
 await withProxy({upstreamTimeoutMs:25,logger:{warn:event=>events.push(event)},backendHandler:(req,res)=>{}},async base=>{
  assert.equal((await fetch(base+'/api/v1/admin/access/users/username/admin.private')).status,504);
  assert.equal(events.length,1);
  assert.match(events[0],/route=\/api\/v1\/admin\/access\/users\/username\/\{value\}/);
  assert.equal(events[0].includes('admin.private'),false);
 });
});

test('proxy marks an upstream 502 separately from its own network timeout',async()=>{
 const events=[];
 await withProxy({logger:{warn:event=>events.push(event)},backendHandler:(req,res)=>{
  res.writeHead(502,{'Content-Type':'application/json'});res.end('{"message":"gateway unavailable"}');
 }},async base=>{
  const response=await fetch(base+'/api/v1/admin/auth/csrf',{headers:{'X-Correlation-ID':'upstream-correlation-2'}});
  assert.equal(response.status,502);
  assert.equal(response.headers.get('x-correlation-id'),'upstream-correlation-2');
  assert.equal(events.length,1);
  assert.match(events[0],/event=admin_proxy_upstream_response/);
  assert.match(events[0],/reason=UPSTREAM_HTTP_RESPONSE/);
  assert.match(events[0],/upstreamCorrelationPresent=false/);
 });
});

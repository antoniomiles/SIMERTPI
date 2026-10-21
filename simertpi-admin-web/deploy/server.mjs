import http from 'node:http';
import {fileURLToPath} from 'node:url';
import https from 'node:https';
import { readFileSync, statSync, createReadStream } from 'node:fs';
import { resolve, extname, sep } from 'node:path';
import { pipeline } from 'node:stream';
import { randomUUID } from 'node:crypto';
export function createPortalServer({root, upstream, allowLoopback = false, authTimeoutMs = 120000, upstreamTimeoutMs = 15000, logger = console}) {
 const target = new URL(upstream);
 if (target.username || target.password || target.pathname !== '/' || target.search || target.hash ||
     (target.protocol !== 'https:' && !(allowLoopback && target.protocol === 'http:' && ['127.0.0.1','localhost'].includes(target.hostname)))) throw Error('Invalid upstream');
 const base = resolve(root);
 return http.createServer((req,res)=>{
  res.setHeader('X-Content-Type-Options','nosniff');
  res.setHeader('Referrer-Policy','no-referrer');
  res.setHeader('Content-Security-Policy',"default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; font-src 'self'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'");
  res.setHeader('Permissions-Policy','camera=(), microphone=(), geolocation=()');
  res.setHeader('Cache-Control','no-store');
  if(!req.url.startsWith('/')||req.url.startsWith('//')){res.writeHead(400);res.end();return;}
  let pathname;try{pathname=decodeURIComponent(new URL(req.url,'http://localhost').pathname);}catch{res.writeHead(400);res.end();return;}
  if(pathname==='/healthz'){res.writeHead(200,{'Content-Type':'text/plain'});res.end('ok');return;}
  if(pathname==='/api'||pathname.startsWith('/api/')){
   if(!pathname.startsWith('/api/v1/admin/')){res.writeHead(404);res.end();return;}
   const headers={...req.headers,host:target.host};
   const suppliedCorrelation=req.headers['x-correlation-id'];
   const correlationId=typeof suppliedCorrelation==='string'&&/^[A-Za-z0-9._:-]{1,128}$/.test(suppliedCorrelation)
     ? suppliedCorrelation : randomUUID();
   headers['x-correlation-id']=correlationId;
   res.setHeader('X-Correlation-ID',correlationId);
   // Strip hop-by-hop and proxy identity supplied by the caller; preserve Origin/CSRF/Cookie.
   for(const name of ['connection','keep-alive','proxy-authenticate','proxy-authorization','te','trailer','transfer-encoding','upgrade','forwarded','x-forwarded-for','x-forwarded-host','x-forwarded-proto','authorization'])delete headers[name];
   // Do not silently turn a forbidden Basic/Bearer request into cookie authentication.
   if(req.headers.authorization){res.writeHead(401);res.end();return;}
   const transport=target.protocol==='https:'?https:http;
   const isAuthRequest=['/api/v1/admin/auth/csrf','/api/v1/admin/auth/login','/api/v1/admin/auth/me','/api/v1/admin/auth/logout'].includes(pathname);
   const started=Date.now();
   let timedOut=false;
   let responseTimer;
   const request=transport.request(new URL(req.url,target),{method:req.method,headers,rejectUnauthorized:true},response=>{
    clearTimeout(responseTimer);
    if(response.statusCode===502||response.statusCode===504){
     logger.warn(`event=admin_proxy_upstream_response timestamp=${new Date().toISOString()} method=${req.method} route=${normalizeRoute(pathname)} status=${response.statusCode} result=FAILURE reason=UPSTREAM_HTTP_RESPONSE correlationId=${correlationId} durationMs=${Date.now()-started} upstreamCorrelationPresent=${/^[A-Za-z0-9._:-]{1,128}$/.test(response.headers['x-correlation-id']??'')}`);
    }
    const outgoing={...response.headers,'cache-control':'no-store','pragma':'no-cache','x-correlation-id':correlationId};
    for(const name of ['connection','keep-alive','transfer-encoding','upgrade'])delete outgoing[name];
    // Never cache API results, including failures. Set-Cookie is forwarded as separate headers.
    res.writeHead(response.statusCode??502,outgoing);
    pipeline(response,res,()=>{});
   });
   // Render Free may need about a minute to wake. Bound the wait for upstream
   // response headers without retrying any request (especially login POST).
   responseTimer=setTimeout(()=>{timedOut=true;request.destroy();},isAuthRequest?authTimeoutMs:upstreamTimeoutMs);
   request.on('error',()=>{clearTimeout(responseTimer);if(!res.headersSent){
    const status=timedOut?504:502;
    const reason=timedOut?'UPSTREAM_TIMEOUT':'UPSTREAM_UNAVAILABLE';
    logger.warn(`event=admin_proxy_upstream_failure timestamp=${new Date().toISOString()} method=${req.method} route=${normalizeRoute(pathname)} status=${status} result=FAILURE reason=${reason} correlationId=${correlationId} durationMs=${Date.now()-started}`);
    const body=timedOut?'Servicio administrativo excedio el tiempo de espera':'Servicio administrativo no disponible';
    res.writeHead(status,{'Content-Type':'application/json','Cache-Control':'no-store','Pragma':'no-cache','X-Correlation-ID':correlationId,'X-Admin-Proxy-Error':reason,...(timedOut?{'Retry-After':'5'}:{})});
    res.end(JSON.stringify({code:timedOut?'UPSTREAM_TIMEOUT':'UPSTREAM_UNAVAILABLE',message:body}));
   }else res.destroy();});
   res.on('close',()=>clearTimeout(responseTimer));
   req.on('aborted',()=>request.destroy());
   req.pipe(request);return;
  }
  if(!['GET','HEAD'].includes(req.method)){res.writeHead(405,{'Allow':'GET, HEAD'});res.end();return;}
  let path=resolve(base,'.'+pathname);
  if(path!==base&&!path.startsWith(base+sep)){res.writeHead(404);res.end();return;}
  try{if(!statSync(path).isFile())throw Error();}catch{
   if(extname(pathname)){res.writeHead(404);res.end();return;}
   path=resolve(base,'index.html');
  }
  const types={'.html':'text/html; charset=utf-8','.js':'text/javascript; charset=utf-8','.css':'text/css; charset=utf-8','.svg':'image/svg+xml','.ttf':'font/ttf','.ico':'image/x-icon','.json':'application/json'};
  try{const stat=statSync(path);res.writeHead(200,{'Content-Type':types[extname(path)]??'application/octet-stream','Content-Length':stat.size});if(req.method==='HEAD')res.end();else pipeline(createReadStream(path),res,()=>{});}catch{res.writeHead(503);res.end();}
 });
}
function normalizeRoute(pathname) {
 return pathname.replace(/\/[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}(?=\/|$)/gi,'/{id}')
  .replace(/\/[0-9]+(?=\/|$)/g,'/{id}')
  .replace(/(\/(?:plate|username|qr|code)\/)[^/]+/gi,'$1{value}').slice(0,180);
}
if(process.argv[1] && resolve(process.argv[1])===resolve(fileURLToPath(import.meta.url))){
 const server=createPortalServer({root:process.env.PORTAL_ROOT??'/app/browser',upstream:process.env.ADMIN_BACKEND_ORIGIN??'https://simertpi-backend-dev.onrender.com'});
 const port=Number(process.env.PORT??10000);if(!Number.isInteger(port)||port<1024||port>65535)throw Error('Invalid PORT');
 server.listen(port,'0.0.0.0');
 for(const signal of ['SIGTERM','SIGINT'])process.on(signal,()=>{server.close(()=>process.exit(0));setTimeout(()=>process.exit(1),10000).unref();});
}

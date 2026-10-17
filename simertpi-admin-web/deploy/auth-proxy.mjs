import https from 'node:https';
import http from 'node:http';
import {readFileSync} from 'node:fs';
import {createPortalServer} from './server.mjs';
const edge=createPortalServer({root:'dist/simertpi-admin-web/browser',upstream:'http://127.0.0.1:8089',allowLoopback:true});
edge.listen(4302,'127.0.0.1');
const tls=https.createServer({key:readFileSync('../.local-tools/https/key.pem'),cert:readFileSync('../.local-tools/https/cert.pem')},(req,res)=>{
 const forward=http.request({hostname:'127.0.0.1',port:4302,path:req.url,method:req.method,headers:req.headers},incoming=>{res.writeHead(incoming.statusCode,incoming.headers);incoming.pipe(res);});
 forward.on('error',()=>{res.writeHead(502);res.end();});req.pipe(forward);
});tls.listen(4443,'localhost');
for(const signal of ['SIGTERM','SIGINT'])process.on(signal,()=>{tls.close();edge.close();process.exit(0);});

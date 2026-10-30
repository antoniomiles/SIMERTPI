import { spawn, execFileSync } from 'node:child_process';
import { mkdirSync, unlinkSync, existsSync } from 'node:fs';
import { resolve } from 'node:path';
const directory=resolve('../.local-tools/https');mkdirSync(directory,{recursive:true});
const openssl=process.platform==='win32'?'C:/Program Files/Git/usr/bin/openssl.exe':'openssl';
// Ephemeral, ignored TLS fixture. Trust is process-local and restricted to this certificate.
execFileSync(openssl,['req','-x509','-newkey','rsa:2048','-sha256','-days','1','-nodes','-subj','/CN=localhost','-addext','subjectAltName=DNS:localhost,IP:127.0.0.1','-keyout',resolve(directory,'key.pem'),'-out',resolve(directory,'cert.pem')],{stdio:['ignore','ignore','pipe'],windowsHide:true});
const child=spawn(process.execPath,['node_modules/@playwright/test/cli.js','test','--config=playwright.auth.config.ts',...process.argv.slice(2)],{stdio:'inherit',windowsHide:true,env:{...process.env,NODE_EXTRA_CA_CERTS:resolve(directory,'cert.pem')}});
child.on('exit',code=>{
 const mailbox=resolve('../simertpi-backend/target/admin-browser-mailbox.tsv');
 try{if(existsSync(mailbox))unlinkSync(mailbox);}catch{}
 process.exit(code??1);
});

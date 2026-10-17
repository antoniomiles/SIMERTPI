import { spawn } from 'node:child_process';
import { writeFileSync, existsSync, unlinkSync } from 'node:fs';
import { resolve } from 'node:path';
const backend=resolve('../simertpi-backend');const stop=resolve(backend,'target/admin-browser.stop');
if(existsSync(stop))unlinkSync(stop);
const args=['--batch-mode','--no-transfer-progress','-Dtest=AdminBrowserHarness','-Dsimertpi.admin-browser-harness=true','test'];
const child=process.platform==='win32'?spawn('cmd.exe',['/d','/s','/c','mvn.cmd '+args.join(' ')],{cwd:backend,stdio:'inherit',windowsHide:true}):spawn('mvn',args,{cwd:backend,stdio:'inherit'});
function finish(){try{writeFileSync(stop,'stop');}catch{} }
process.on('SIGTERM',finish);process.on('SIGINT',finish);
child.on('exit',code=>process.exit(code??1));

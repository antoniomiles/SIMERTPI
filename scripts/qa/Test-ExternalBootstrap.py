"""Runs only an ephemeral local PostgreSQL container. Never receives DEV credentials."""
import os,sys,subprocess,tempfile,pathlib,time,secrets,shutil,socket,threading,select,struct
ROOT=pathlib.Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'.local-tools/python-lib'))
from winpty import PtyProcess
JAVA=pathlib.Path('C:/Program Files/Microsoft/jdk-21.0.12.101-hotspot')
HOST='dpg-db1hq55g1s2s73a8e340-a.oregon-postgres.render.com'
OPENSSL='C:/Program Files/Git/usr/bin/openssl.exe'
FIX=ROOT/'.local-tools/bootstrap-tls'
FIX.mkdir(parents=True,exist_ok=True)
def run(args,env=None,check=True):
 p=subprocess.run([str(x) for x in args],env=env,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
 if check and p.returncode:
  detail=(p.stderr+p.stdout).decode(errors='replace')
  for key in ['DB_PASSWORD','POSTGRES_PASSWORD','QA_ADMIN_PASSWORD']:
   if env and env.get(key):detail=detail.replace(env[key],'<redacted>')
  lines=[line for line in detail.splitlines() if 'Exception' in line or 'Caused by:' in line]
  raise RuntimeError('QA command failed: '+str(args[0])+'; '+str(lines[-5:]))
 return p
def cert(name,hostname):
 run([OPENSSL,'req','-x509','-newkey','rsa:2048','-sha256','-days','1','-nodes','-subj','/CN='+hostname,'-addext','subjectAltName=DNS:'+hostname,'-keyout',FIX/(name+'.key'),'-out',FIX/(name+'.pem')])
cert('server',HOST);cert('wrong','untrusted.example.invalid')
hosts=FIX/'hosts';hosts.write_text('127.0.0.2 '+HOST+'\n',encoding='ascii')
env=os.environ.copy();env['DB_PASSWORD']=secrets.token_urlsafe(24);env['POSTGRES_PASSWORD']=env['DB_PASSWORD'];env['DB_USERNAME']='qa'
env['DB_URL']='jdbc:postgresql://'+HOST+':5432/simertpi?sslmode=verify-full&sslrootcert='+str(FIX/'server.pem').replace('\\','/')
env['JDK_JAVA_OPTIONS']='-Djdk.net.hosts.file='+str(hosts)
env['QA_ADMIN_PASSWORD']=secrets.token_urlsafe(24)
name='simertpi-d1-bootstrap-qa-'+secrets.token_hex(4)
cp=str(ROOT/'simertpi-backend/target/classes')+';'+str(ROOT/'simertpi-backend/target/bootstrap-lib/*')+';'+str(ROOT/'simertpi-backend/target/qa-bootstrap')
out=ROOT/'simertpi-backend/target/qa-bootstrap';out.mkdir(parents=True,exist_ok=True)
run([JAVA/'bin/javac.exe','-cp',cp,'-d',out,ROOT/'scripts/qa/BootstrapProbe.java'])
def console(ca,expect_success,preflight=False):
 args=['powershell.exe','-NoLogo','-NoProfile','-ExecutionPolicy','Bypass','-File',str(ROOT/'scripts/Invoke-DevAdminBootstrap.ps1'),'-ExpectedHost',HOST,'-ExpectedDbUser','bootstrap_qa','-CaCertificate',str(ca),'-Operator','isolated.qa','-ApprovalId','CP25.3-D1-ISOLATED','-JavaHome',str(JAVA)]
 if preflight:args.append('-PreflightOnly')
 entry_password=env['QA_ADMIN_PASSWORD'] if expect_success else secrets.token_urlsafe(24)
 child_env=env.copy();child_env['DB_USERNAME']='bootstrap_qa'
 if expect_success:child_env.pop('DB_PASSWORD',None)
 proc=PtyProcess.spawn(args,env=child_env,cwd=str(ROOT))
 captured='';cursor=0;proc.fileobj.settimeout(0.5)
 def waitfor(marker,timeout=70):
  nonlocal captured,cursor
  deadline=time.time()+timeout
  while marker not in captured[cursor:]:
   if time.time()>deadline or not proc.isalive():
    safe=captured
    for value in [env['DB_PASSWORD'],env['QA_ADMIN_PASSWORD'],entry_password]:safe=safe.replace(value,'<redacted>')
    raise RuntimeError('Console stage failed: '+marker+'; '+safe[-1600:])
   try:captured+=proc.read(4096)
   except socket.timeout:continue
  cursor=captured.index(marker,cursor)+len(marker)
 try:
  if preflight:waitfor('Preflight local correcto')
  elif ca.name=='wrong.pem':
   waitfor('Bootstrap detenido')
  else:
   if expect_success:
    waitfor('PostgreSQL DEV');proc.write(env['DB_PASSWORD']+'\r\n')
   for marker,value in [('Usuario administrativo aprobado:','qa.bootstrap'),('Correo aprobado:','qa.bootstrap@example.invalid'),('Nombres:','QA'),('Apellidos:','Isolated'),('Contrase',entry_password),('Repita contrase',entry_password)]:
    waitfor(marker);proc.write(value+'\r\n')
   waitfor('Administrador creado y verificado' if expect_success else 'Bootstrap detenido')
  deadline=time.time()+20
  while proc.isalive() and time.time()<deadline:
   try:captured+=proc.read(4096)
   except socket.timeout:continue
   except EOFError:break
  if proc.isalive():raise RuntimeError('PowerShell did not finish')
  if (proc.exitstatus==0)!=expect_success:raise RuntimeError('Unexpected PowerShell exit status')
  if env['DB_PASSWORD'] in captured or env['QA_ADMIN_PASSWORD'] in captured or entry_password in captured:raise RuntimeError('Protected input leaked')
  print('PASS: external PowerShell '+('preflight' if preflight else 'TLS rejection' if ca.name=='wrong.pem' else 'creation' if expect_success else 'repeat refusal')+' without secret output')
 finally:
  if proc.isalive():proc.terminate(force=True)
try:
 console(FIX/'server.pem',True,preflight=True)
 run(['docker','run','-d','--name',name,'-p','127.0.0.1::5432','-e','POSTGRES_PASSWORD','-e','POSTGRES_USER=qa','-e','POSTGRES_DB=simertpi','-v',str(FIX)+':/qa:ro','postgres:16.15','sh','-c','cp /qa/server.key /tmp/qa-server.key && chown postgres:postgres /tmp/qa-server.key && chmod 600 /tmp/qa-server.key && exec docker-entrypoint.sh postgres -c ssl=on -c ssl_cert_file=/qa/server.pem -c ssl_key_file=/tmp/qa-server.key'],env)
 for _ in range(60):
  if run(['docker','exec',name,'pg_isready','-U','qa','-d','simertpi'],check=False).returncode==0:break
  time.sleep(1)
 else:raise RuntimeError('Isolated PostgreSQL not ready')
 mapped=run(['docker','port',name,'5432/tcp']).stdout.decode().strip();pg_port=int(mapped.rsplit(':',1)[1])
 socks=socket.socket();socks.bind(('127.0.0.1',0));socks.listen()
 def exact(conn,n):
  data=b''
  while len(data)<n:
   part=conn.recv(n-len(data))
   if not part:raise EOFError()
   data+=part
  return data
 def tunnel(conn):
  remote=None
  try:
   ver,count=exact(conn,2);exact(conn,count);conn.sendall(b'\x05\x00')
   ver,cmd,reserved,kind=exact(conn,4)
   if kind==1:host=socket.inet_ntoa(exact(conn,4))
   elif kind==3:host=exact(conn,exact(conn,1)[0]).decode()
   else:raise RuntimeError('Unsupported QA proxy target')
   port=struct.unpack('!H',exact(conn,2))[0]
   if host not in [HOST,'127.0.0.2'] or port!=5432:raise RuntimeError('Non-isolated target rejected')
   remote=socket.create_connection(('127.0.0.1',pg_port));conn.sendall(b'\x05\x00\x00\x01\x7f\x00\x00\x01\x00\x00')
   while True:
    ready,_,_=select.select([conn,remote],[],[],30)
    if not ready:break
    for source in ready:
     data=source.recv(65536)
     if not data:return
     (remote if source is conn else conn).sendall(data)
  finally:
   conn.close()
   if remote:remote.close()
 def accept():
  while True:
   try:conn,_=socks.accept()
   except OSError:return
   threading.Thread(target=tunnel,args=(conn,),daemon=True).start()
 threading.Thread(target=accept,daemon=True).start()
 env['JDK_JAVA_OPTIONS']+=' -DsocksProxyHost=127.0.0.1 -DsocksProxyPort='+str(socks.getsockname()[1])+' -DsocksNonProxyHosts='
 run([JAVA/'bin/java.exe','-cp',cp,'BootstrapProbe','init'],env)
 console(FIX/'wrong.pem',False)
 run([JAVA/'bin/java.exe','-cp',cp,'BootstrapProbe','empty'],env)
 console(FIX/'server.pem',True)
 console(FIX/'server.pem',False)
 result=run([JAVA/'bin/java.exe','-cp',cp,'BootstrapProbe','verify'],env)
 print(result.stdout.decode(errors='replace').strip())
finally:
 run(['docker','rm','-f',name],check=False)
 for name in ['DB_PASSWORD','POSTGRES_PASSWORD','QA_ADMIN_PASSWORD']:env.pop(name,None)
 for key in ['server.key','wrong.key']:(FIX/key).unlink(missing_ok=True)

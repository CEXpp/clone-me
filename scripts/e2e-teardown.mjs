import { readFile } from 'node:fs/promises';
export default async function teardown(){
  const control=JSON.parse(await readFile(new URL('../.cache/e2e-control.json',import.meta.url),'utf8'));
  await fetch(control.url,{method:'POST'});
  for(let i=0;i<100;i++){
    try{await fetch('http://127.0.0.1:'+control.port+'/api/health/ready',{signal:AbortSignal.timeout(500)});}
    catch{return;}
    await new Promise(resolve=>setTimeout(resolve,100));
  }
  throw new Error('Isolated test server did not stop');
}

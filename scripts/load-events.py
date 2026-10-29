"""Small reproducible HTTP latency sample, including authentication and database work."""
import base64, concurrent.futures, json, os, statistics, time, urllib.request
from pathlib import Path
url=os.environ.get('APP_URL','http://localhost:8080')
auth=base64.b64encode(('globex-hr:'+os.environ.get('APP_PASSWORD','local-demo-password')).encode()).decode()
version=int(time.time())
def send(i):
    body=[{'employeeId':f'LOAD{i}', 'version':version, 'type':'UPDATE','name':'Synthetic','email':'load@example.test','phone':'919000000001','office':'BLR'}]
    request=urllib.request.Request(url+'/api/events',data=json.dumps(body).encode(),headers={'Authorization':'Basic '+auth,'Content-Type':'application/json'})
    start=time.perf_counter()
    with urllib.request.urlopen(request,timeout=30) as response:
        result=json.load(response)
    assert result[0]['status']=='APPLIED',result
    return (time.perf_counter()-start)*1000
start=time.perf_counter()
with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
    latencies=sorted(pool.map(send,range(100)))
report={'requests':100,'concurrency':8,'seconds':round(time.perf_counter()-start,3),'p50_ms':round(statistics.median(latencies),2),'p99_ms':round(latencies[98],2)}
Path('docs/event-load-results.json').write_text(json.dumps(report,indent=2))
print(json.dumps(report,indent=2))

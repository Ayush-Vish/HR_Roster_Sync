"""API walkthrough. Start docker compose up --build first; use a fresh demo DB."""
import base64, json, os, urllib.request, datetime

url = os.environ.get('APP_URL', 'http://localhost:8080')
password = os.environ.get('APP_PASSWORD', 'local-demo-password')
def call(method, path, body=None, role='hr'):
    auth = base64.b64encode(('acme-'+role+':'+password).encode()).decode()
    req = urllib.request.Request(url+'/api'+path, method=method,
        data=None if body is None else json.dumps(body).encode(),
        headers={'Authorization':'Basic '+auth, 'Content-Type':'application/json'})
    with urllib.request.urlopen(req) as response:
        result=json.load(response)
    print(method, path, json.dumps(result, indent=2))
    return result

version=int(datetime.datetime.now().timestamp())
event={'employeeId':'DEMO1','version':version,'type':'CREATE','name':'Demo Employee','email':'demo@example.test',
       'phone':'919000000001','manager':'M1','office':'BLR','shift':'DAY'}
call('POST','/events',[event])
call('POST','/events',[event])
call('PATCH','/employees/DEMO1/transport',{'pickupPoint':'Gate A','assistance':'Ramp'},'admin')
call('POST','/bookings',{'employeeId':'DEMO1','startsAt':(datetime.datetime.now(datetime.timezone.utc)+datetime.timedelta(days=1)).isoformat()},'admin')
call('POST','/events',[{'employeeId':'DEMO1','version':version+1,'type':'DEACTIVATE'}])
import time
time.sleep(2)
call('GET','/bookings',role='admin')
call('GET','/outbox',role='operator')

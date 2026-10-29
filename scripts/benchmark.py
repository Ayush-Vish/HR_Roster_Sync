"""Generate a synthetic snapshot, import it twice, and save measured timings.
Run against an empty dedicated demo database. Python standard library only.
"""
import argparse, base64, csv, io, json, os, platform, time, urllib.request
from pathlib import Path

p = argparse.ArgumentParser()
p.add_argument('--url', default='http://localhost:8080')
p.add_argument('--rows', type=int, default=200000)
p.add_argument('--output', default='docs/benchmark-results.json')
a = p.parse_args()
password = os.environ.get('APP_PASSWORD', 'local-demo-password')
auth = base64.b64encode(('acme-hr:' + password).encode()).decode()
report = {'client_platform': platform.platform(), 'rows': a.rows, 'runs': []}
base_version = int(time.time())
for attempt in range(2):
    data = io.StringIO(newline='')
    writer = csv.writer(data)
    writer.writerow(['employeeId', 'version', 'name', 'email', 'phone', 'manager', 'office', 'shift'])
    for i in range(a.rows):
        writer.writerow([f'B{i:07}', base_version, f'Synthetic {i}', f'employee{i}@example.test', '919000000001', 'M1', 'BLR', 'DAY'])
    boundary = 'roster-benchmark-boundary'
    body = (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="snapshot.csv"\r\nContent-Type: text/csv\r\n\r\n'.encode()
            + data.getvalue().encode() + f'\r\n--{boundary}--\r\n'.encode())
    request = urllib.request.Request(f'{a.url}/api/snapshots?watermark={base_version+attempt}', data=body,
        headers={'Authorization': 'Basic '+auth, 'Content-Type': 'multipart/form-data; boundary='+boundary})
    start = time.perf_counter()
    with urllib.request.urlopen(request, timeout=300) as response:
        result = json.load(response)
    elapsed = time.perf_counter() - start
    report['runs'].append({'attempt': attempt+1, 'seconds': round(elapsed,3), 'rows_per_second': round(a.rows/elapsed), 'result': result})
Path(a.output).parent.mkdir(parents=True, exist_ok=True)
Path(a.output).write_text(json.dumps(report, indent=2), encoding='utf-8')
print(json.dumps(report, indent=2))

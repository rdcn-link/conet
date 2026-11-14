"""Run one autonomous research round or replay, and verify publication gating.

Uses the server's configured model and local Docker environment. Never approves a
product or invents source licenses. Keeps the resulting audit record.
"""
import argparse
import json
import time
import urllib.error
import urllib.request


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--url', default='http://127.0.0.1:8870')
    parser.add_argument('--replay', help='Replay this candidate without a model call')
    parser.add_argument('--timeout', type=int, default=600)
    args = parser.parse_args()
    base = args.url.rstrip('/')

    def api(path, body=None):
        data = None if body is None else json.dumps(body).encode()
        request = urllib.request.Request(base + path, data=data,
                                         headers={'Content-Type': 'application/json'})
        with urllib.request.urlopen(request, timeout=30) as response:
            return json.load(response)

    before = {r['id'] for r in api('/api/product-robot')['records']}
    if args.replay:
        candidate = api('/api/product-robot/' + args.replay + '/replay', {})['id']
    else:
        api('/api/product-robot/run', {})
        candidate = None
    deadline = time.monotonic() + args.timeout
    while time.monotonic() < deadline:
        records = api('/api/product-robot')['records']
        if candidate is None:
            candidate = next((r['id'] for r in records if r['id'] not in before), None)
        record = next((r for r in records if r['id'] == candidate), None)
        if record and record['state'] not in ('planning', 'submitting', 'executing'):
            assert record['state'] == 'pending_review', record.get('error') or record
            break
        time.sleep(2)
    else:
        raise AssertionError('Research or processing did not finish before the deadline')

    assert all(c['passed'] for c in record['quality'])
    bundle = api('/api/product-robot/' + candidate + '/bundle')
    assert bundle['proposal']['job']['steps']
    assert bundle['files'] and bundle['runtime']['image'].startswith('sha256:')
    assert not any(p.get('automation', {}).get('candidate_id') == candidate
                   for p in api('/api/products')), 'Pending candidate became public'
    for path, body in [('/api/product-robot/' + candidate + '/approve', {}),
                       ('/apps/' + record['app_id'] + '/api/jobs/' + record['run_id'] + '/publication', {})]:
        try:
            api(path, body)
            raise AssertionError('Approval or publication without rights was accepted')
        except urllib.error.HTTPError as error:
            assert error.code == 400
    print(json.dumps({'candidate': candidate, 'state': record['state'],
                      'title': record['title'], 'run_id': record['run_id'],
                      'generated_operators': len(bundle['proposal']['new_operators']),
                      'quality_checks': len(record['quality'])}, ensure_ascii=False))


if __name__ == '__main__':
    main()

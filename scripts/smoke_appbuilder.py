#!/usr/bin/env python3
"""Publish a fourth configured application, execute it and verify persistent usage counters."""
import argparse
import json
import re
import time
from urllib.error import HTTPError
from urllib.request import Request, urlopen


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--url', default='http://127.0.0.1:8870')
    args = parser.parse_args()
    base = args.url.rstrip('/'); 

    def api(path, payload=None):
        body = None if payload is None else json.dumps(payload).encode()
        with urlopen(Request(base + path, body, {'Content-Type': 'application/json'}), timeout=20) as response:
            return json.load(response)

    original = api('/api/appbuilder/apps/observatory')
    identifier = 'observatory-builder-demo'
    application = re.sub(r'^id: .*$', 'id: ' + identifier, original['application_yaml'], count=1, flags=re.M)
    application = re.sub(r'^title: .*$', 'title: 配置式天文分析演示', application, count=1, flags=re.M)
    payload = {'application_yaml': application, 'job_yaml': original['job_yaml']}
    try:
        current = api('/api/appbuilder/apps/' + identifier)
        payload['revision'] = current['revision']
    except HTTPError as error:
        assert error.code == 404
    assert api('/api/appbuilder/validate', payload)['valid']
    doc = api('/api/appbuilder/apps', payload)
    assert doc['url'] == '/apps/' + identifier + '/'
    with urlopen(base + doc['url']) as response:
        assert response.status == 200
    # The old revision cannot overwrite a published application.
    stale = dict(payload, revision='stale')
    try:
        api('/api/appbuilder/apps', stale)
    except HTTPError as error:
        assert error.code == 400
    else:
        raise AssertionError('Stale publication accepted')
    before = api('/api/appbuilder/statistics')
    prefix = '/apps/' + identifier
    cfg = api(prefix + '/api/config')
    inputs = {'cone': cfg['manifest']['default_cone'], 'match_radius_arcsec': .8, 'min_snr': 5}
    assert api(prefix + '/api/preview', inputs)['input_variables']['CONE'] == inputs['cone']
    job = api(prefix + '/api/jobs', inputs)
    deadline = time.monotonic() + 360
    while job['state'] in ('queued', 'running'):
        assert time.monotonic() < deadline, 'Analysis timed out'
        time.sleep(1)
        job = api(prefix + '/api/jobs/' + job['id'])
    assert job['state'] == 'succeeded', job.get('error')
    assert job['result']['rows']
    for path in job['artifacts'].values():
        with urlopen(base + path) as response:
            assert response.read()
    # Node observations are persisted just after output completion.
    time.sleep(5)
    after = api('/api/appbuilder/statistics')
    assert after['submissions'] >= before['submissions'] + 1
    assert sum(x['count'] for x in after['datasets']) >= sum(x['count'] for x in before['datasets']) + 3
    assert sum(x['count'] for x in after['operators']) >= sum(x['count'] for x in before['operators']) + 7
    assert sum(x['count'] for x in after['nodes']) >= sum(x['count'] for x in before['nodes']) + 3
    stable = api('/api/appbuilder/statistics')
    for key in ('apps', 'datasets', 'operators', 'nodes'):
        assert stable[key] == after[key], 'Reading counters must not increment usage'
    print(json.dumps({'url': base + doc['url'], 'run': job['id'], 'job_id': job['job_id'],
                      'matched_sources': len(job['result']['rows']), 'statistics': 'verified'}, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()

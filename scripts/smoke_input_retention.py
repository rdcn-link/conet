"""Verify upload retention and rerun against the published uploaded-csv App.

Creates two real analyses. Does not edit application definitions or publish inputs.
"""
import argparse
import json
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--url', default='http://127.0.0.1:8870')
    args = parser.parse_args()
    base = args.url.rstrip('/') + '/apps/uploaded-csv'

    def api(path, data=None, headers=None):
        headers = dict(headers or {})
        if isinstance(data, dict):
            data = json.dumps(data).encode()
            headers['Content-Type'] = 'application/json'
        request = urllib.request.Request(base + path, data=data, headers=headers)
        with urllib.request.urlopen(request, timeout=90) as response:
            return json.load(response)

    def completed(identifier):
        for _ in range(180):
            run = api('/api/jobs/' + identifier)
            if run['state'] not in ('queued', 'running'):
                assert run['state'] == 'succeeded', run.get('error')
                return run
            time.sleep(1)
        raise AssertionError('Analysis timed out')

    data = (Path(__file__).resolve().parents[1] / 'examples/uploaded-csv/sample.csv').read_bytes()
    uploaded = api('/api/uploads/source', data, {
        'Content-Type': 'application/octet-stream',
        'X-File-Name': urllib.parse.quote('输入保留测试.csv'),
    })
    first = completed(api('/api/jobs', {'source': {'upload_id': uploaded['upload_id']}})['id'])
    assert first['input_retention']['available']
    retained = api('/api/jobs/' + first['id'] + '/inputs/retention', {'keep': True})
    assert retained['retained'] and 'expires_at' not in retained
    try:
        second = completed(api('/api/jobs/' + first['id'] + '/rerun', {})['id'])
        assert second['rerun_of'] == first['id']
        assert first['source']['sha256'] == second['source']['sha256']
        assert first['source']['uri'] != second['source']['uri']
        assert first['result'] == second['result']
        count = len(api('/api/jobs'))
        for body, headers, expected in [({'keep': True, 'extra': True}, {}, 400),
                                        ({'keep': True}, {'Origin': 'https://other.example'}, 403)]:
            try:
                api('/api/jobs/' + first['id'] + '/inputs/retention', body, headers)
                raise AssertionError('Unsafe retention request was accepted')
            except urllib.error.HTTPError as error:
                assert error.code == expected
        try:
            api('/api/jobs/' + first['id'] + '/rerun', {'source': 'replacement'})
            raise AssertionError('Replacement parameters were accepted')
        except urllib.error.HTTPError as error:
            assert error.code == 400
        assert len(api('/api/jobs')) == count
    finally:
        released = api('/api/jobs/' + first['id'] + '/inputs/retention', {'keep': False})
        assert not released['retained'] and released['expires_at']
    print(json.dumps({'original': first['id'], 'rerun': second['id'],
                      'rows': second['result']['row_count'],
                      'expires_at': released['expires_at']}, ensure_ascii=False))


if __name__ == '__main__':
    main()

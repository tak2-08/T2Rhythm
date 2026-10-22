"""Reassemble only pre-signed, locally verified public APKs; publish without keys."""
from pathlib import Path
import base64, hashlib, json, os, re, subprocess, urllib.request, urllib.error

root = Path('release-1323')
manifest = json.loads((root / 'ready.json').read_text())
assert manifest['source_revision'] == '6f8f7be38607575407d27f39e99680352f471cb3'
assert manifest['tag'] == '5.5.499.1323'
repository = os.environ['GITHUB_REPOSITORY']
assert repository == 'tak2-08/T2Rhythm'
token = os.environ['GH_TOKEN']

def api(method, path, data=None, missing=False):
    body = None if data is None else json.dumps(data).encode()
    request = urllib.request.Request('https://api.github.com/repos/' + repository + path,
        data=body, method=method, headers={'Authorization': 'Bearer ' + token,
        'Accept': 'application/vnd.github+json', 'Content-Type': 'application/json',
        'X-GitHub-Api-Version': '2022-11-28'})
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            return json.load(response)
    except urllib.error.HTTPError as error:
        if missing and error.code == 404:
            return None
        raise

output = Path('release-assets')
output.mkdir(exist_ok=True)
assert {a['name'] for a in manifest['assets']} == {
    'T2Rhythm-5.5.499.1323.apk', 'T2Rhythm-5.5.499.1323-debug.apk'}
for asset in manifest['assets']:
    assert re.fullmatch('[0-9a-f]{64}', asset['sha256'])
    destination = output / asset['name']
    with destination.open('wb') as stream:
        for part in asset['parts']:
            assert re.fullmatch(r'parts/asset-[01]-\d{3}\.b64', part)
            stream.write(base64.b64decode((root / part).read_bytes(), validate=True))
    assert destination.stat().st_size == asset['size'], 'APK size mismatch'
    assert hashlib.sha256(destination.read_bytes()).hexdigest() == asset['sha256'], 'APK digest mismatch'
    print('Verified prepared asset:', asset['name'], asset['sha256'])

sums = ''.join(a['sha256'] + '  ' + a['name'] + '\n' for a in manifest['assets'])
(output / 'SHA256SUMS').write_text(sums)
tag = manifest['tag']
tag_ref = api('GET', '/git/ref/tags/' + tag, missing=True)
if tag_ref is None:
    tag_ref = api('POST', '/git/refs', {'ref': 'refs/tags/' + tag,
                                      'sha': manifest['source_revision']})
assert tag_ref['object']['type'] == 'commit'
assert tag_ref['object']['sha'] == manifest['source_revision'], 'Refusing to move an existing tag'
assert manifest['release_id'] == 420933363
release = api('GET', '/releases/' + str(manifest['release_id']))
assert release['tag_name'] == tag
assert release['draft'], 'Refusing to alter an already published release'
assert release['body'] == (root / 'release-body.md').read_text()
subprocess.run(['gh', 'release', 'upload', tag, '--repo', repository, '--clobber',
    *[str(output / a['name']) for a in manifest['assets']], str(output / 'SHA256SUMS')], check=True)
release = api('GET', '/releases/' + str(release['id']))
assert len(release['assets']) == 3
for expected in manifest['assets']:
    actual = next(a for a in release['assets'] if a['name'] == expected['name'])
    assert actual['size'] == expected['size']
    assert actual['digest'] == 'sha256:' + expected['sha256']
release = api('PATCH', '/releases/' + str(release['id']),
              {'draft': False, 'prerelease': False, 'make_latest': 'true'})
assert not release['draft'] and not release['prerelease']
assert api('GET', '/releases/latest')['id'] == release['id']
Path('publisher-result.json').write_text(json.dumps(release, indent=2) + '\n')
print('Published latest stable:', release['html_url'])

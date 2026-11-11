"""Static guards supplement, not replace, Android build and real-device security tests."""
from pathlib import Path
import xml.etree.ElementTree as ET
base = Path('app/src/main/java/com/motonav/companion')
activity = (base / 'UpdateActivity.java').read_text()
repository = (base / 'UpdateRepository.java').read_text()
provider = (base / 'UpdateApkProvider.java').read_text()
transport = (base / 'UpdateTransport.java').read_text()
manifest = ET.parse('app/src/main/AndroidManifest.xml').getroot()
a = '{http://schemas.android.com/apk/res/android}'
assert any(e.get(a+'name') == 'android.permission.REQUEST_INSTALL_PACKAGES' for e in manifest.findall('uses-permission'))
app = manifest.find('application')
assert app.get(a+'usesCleartextTraffic') == 'false'
p = next(e for e in app.findall('provider') if e.get(a+'name') == '.UpdateApkProvider')
assert p.get(a+'exported') == 'false' and p.get(a+'grantUriPermissions') == 'true'
assert p.get(a+'authorities') == '${applicationId}.updates'
assert next(e for e in app.findall('activity') if e.get(a+'name') == '.UpdateActivity').get(a+'exported') == 'false'
assert 'UpdateActivity.class' in (base / 'AmapActivity.java').read_text()
assert 'getApkContentsSigners()' in repository and 'getSigningCertificateHistory' not in repository
assert 'GET_SIGNATURES | PackageManager.GET_SIGNING_CERTIFICATES' in repository
assert repository.index('UpdatePolicy.validateHash(release.sha256, hex(digest.digest()))') < repository.index('pending.renameTo(verified)')
assert repository.index('verifyArchive(pending, release)') < repository.index('pending.renameTo(verified)')
assert 'getCanonicalFile()' in repository and 'pending.setReadOnly()' in repository
assert 'repository.verifyForInstall(selectedFile, selected, token)' in activity
assert 'canRequestPackageInstalls()' in activity and 'ACTION_MANAGE_UNKNOWN_APP_SOURCES' in activity
resume = activity.split('@Override protected void onResume()', 1)[1].split('@Override protected void onPause()', 1)[0]
assert 'requestInstall()' not in resume and 'startActivity(' not in resume and 'runWork(' not in resume
assert 'operation.cancel(); operation = null;' in activity
assert 'destroyed || !foreground || operation != token || token.isCancelled()' in activity
assert 'if (result instanceof File) ((File) result).delete();' in activity
assert 'ready = null; release = null;' in activity.split('startActivity(intent)', 1)[1]
assert 'Intent.FLAG_GRANT_READ_URI_PERMISSION' in activity
assert 'FLAG_GRANT_WRITE_URI_PERMISSION' not in activity and 'FLAG_GRANT_PERSISTABLE_URI_PERMISSION' not in activity
assert 'if (!"r".equals(mode))' in provider and 'ParcelFileDescriptor.MODE_READ_ONLY' in provider
assert 'uri.getPathSegments().size() != 1' in provider and 'getCanonicalFile().equals(file)' in provider
assert 'uri.getEncodedPath()' in provider and 'uri.getQuery() != null' in provider
assert 'UnsupportedOperationException("read_only")' in provider
assert 'setInstanceFollowRedirects(false)' in transport and 'setConnectTimeout(10000)' in transport and 'setReadTimeout(10000)' in transport
assert 'MAX_REDIRECTS' in transport and 'checkDeadline(deadline)' in transport
for source in (activity, repository, provider, transport):
    assert 'SavedMapConfig' not in source and 'AmapKeySession' not in source
    assert 'android.location' not in source and 'Log.' not in source and 'printStackTrace' not in source
    assert 'setHostnameVerifier' not in source and 'setSSLSocketFactory' not in source
print('PASS: updater manifest/lifecycle/signer/provider/privacy static guards (not Android execution)')

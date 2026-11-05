import base64
import hashlib
import hmac
import json
import os
import time


def encode_segment(value):
    raw = json.dumps(value, separators=(',', ':')).encode('utf-8')
    return base64.urlsafe_b64encode(raw).rstrip(b'=').decode('ascii')


secret = os.environ.get('JWT_SECRET', '')
if not secret:
    raise SystemExit('Set JWT_SECRET to a base64-encoded key of at least 32 bytes')
key = base64.b64decode(secret, validate=True)
if len(key) < 32:
    raise SystemExit('JWT_SECRET must decode to at least 32 bytes')

now = int(time.time())
claims = {
    'sub': 'local-admin',
    'iat': now,
    'exp': now + 3600,
    os.environ.get('JWT_ROLE_CLAIM', 'role'): os.environ.get('JWT_ADMIN_ROLE', 'urlshortener.admin'),
}
audience = os.environ.get('JWT_AUDIENCE')
issuer = os.environ.get('JWT_ISSUER_URI')
if audience:
    claims['aud'] = audience
if issuer:
    claims['iss'] = issuer

unsigned = encode_segment({'alg': 'HS256', 'typ': 'JWT'}) + '.' + encode_segment(claims)
signature = hmac.new(key, unsigned.encode('ascii'), hashlib.sha256).digest()
print(unsigned + '.' + base64.urlsafe_b64encode(signature).rstrip(b'=').decode('ascii'))
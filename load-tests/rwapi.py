#!/usr/bin/env python3
"""Helper: query the Railway GraphQL API (v2, backboard) with the CLI token.

Uso: python rwapi.py '<query-json-file>'   ou
     RW_QUERY='...' python rwapi.py
Lê o token do ~/.railway/config.json (o mesmo da CLI `railway`).
"""
import json, os, sys, urllib.request

cfg = json.load(open(os.path.expanduser('~/.railway/config.json')))
tok = cfg['user']['accessToken']

if len(sys.argv) > 1:
    payload = open(sys.argv[1], 'rb').read()
else:
    payload = os.environ['RW_QUERY'].encode()

req = urllib.request.Request(
    'https://backboard.railway.com/graphql/v2',
    data=payload,
    headers={
        'Authorization': 'Bearer ' + tok,
        'Content-Type': 'application/json',
        'User-Agent': 'Mozilla/5.0',
        'origin': 'https://railway.com',
        'referer': 'https://railway.com/',
    },
)
try:
    r = urllib.request.urlopen(req, timeout=30)
    print(r.read().decode())
except urllib.error.HTTPError as e:
    print('HTTP', e.code)
    print(e.read().decode()[:3000])

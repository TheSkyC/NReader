"""Sign or verify a tag feed's exact version 1 manifest bytes."""

import argparse
import base64
import binascii
import json
import os
from pathlib import Path
import tempfile

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

PREFIXES = {
    "catalog": b"NReader tag catalog manifest v2\n",
    "translation": b"NReader tag translation manifest v2\n",
}
MANIFEST_LIMITS = {"catalog": 4096, "translation": 32768}
ENVELOPE_LIMIT = 65536


def _read(path, limit):
    with Path(path).open("rb") as source:
        data = source.read(limit + 1)
    if not data or len(data) > limit:
        raise ValueError("File size outside allowed range")
    return data


def _key(data, private):
    key = (serialization.load_pem_private_key(data, password=None) if private
           else serialization.load_pem_public_key(data))
    expected = ec.EllipticCurvePrivateKey if private else ec.EllipticCurvePublicKey
    if not isinstance(key, expected) or not isinstance(key.curve, ec.SECP256R1):
        raise ValueError("A P-256 key is required")
    return key


def sign_manifest(private_key, kind, manifest):
    if not manifest or len(manifest) > MANIFEST_LIMITS[kind]:
        raise ValueError("Manifest size outside allowed range")
    signature = private_key.sign(PREFIXES[kind] + manifest, ec.ECDSA(hashes.SHA256()))
    envelope = {"schemaVersion": 2, "manifest": base64.b64encode(manifest).decode("ascii"),
                "signature": base64.b64encode(signature).decode("ascii")}
    return (json.dumps(envelope, separators=(",", ":")) + "\n").encode("utf-8")


def _object(pairs):
    result = {}
    for name, value in pairs:
        if name in result:
            raise ValueError("Duplicate envelope field")
        result[name] = value
    return result


def _decode(value):
    if not isinstance(value, str):
        raise ValueError("Invalid Base64 field")
    try:
        data = base64.b64decode(value, validate=True)
    except (binascii.Error, ValueError) as error:
        raise ValueError("Invalid Base64 field") from error
    if base64.b64encode(data).decode("ascii") != value:
        raise ValueError("Non-canonical Base64")
    return data


def verify_manifest(public_key, kind, envelope):
    if not envelope or len(envelope) > ENVELOPE_LIMIT:
        raise ValueError("Envelope size outside allowed range")
    parsed = json.loads(envelope.decode("utf-8"), object_pairs_hook=_object)
    if not isinstance(parsed, dict) or set(parsed) != {"schemaVersion", "manifest", "signature"}:
        raise ValueError("Invalid envelope fields")
    if type(parsed["schemaVersion"]) is not int or parsed["schemaVersion"] != 2:
        raise ValueError("Unsupported envelope version")
    manifest = _decode(parsed["manifest"])
    signature = _decode(parsed["signature"])
    if not manifest or len(manifest) > MANIFEST_LIMITS[kind] or not 8 <= len(signature) <= 80:
        raise ValueError("Invalid signed data size")
    try:
        public_key.verify(signature, PREFIXES[kind] + manifest, ec.ECDSA(hashes.SHA256()))
    except InvalidSignature as error:
        raise ValueError("Invalid manifest signature") from error
    return manifest


def validate_v1_manifest(kind, manifest):
    if kind == "catalog":
        from scripts.build_tag_catalog import parse_manifest
    elif kind == "translation":
        from scripts.build_tag_translation_release import parse_manifest
    else:
        raise ValueError("Invalid tag feed kind")
    parse_manifest(manifest)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("operation", choices=("sign", "verify"))
    parser.add_argument("--kind", required=True, choices=PREFIXES)
    parser.add_argument("--key", required=True, type=Path)
    parser.add_argument("--input", required=True, type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    if args.operation == "sign":
        if args.output is None or args.output.resolve() in {args.key.resolve(), args.input.resolve()}:
            parser.error("sign requires an output distinct from both inputs")
        key = _key(_read(args.key, 8192), True)
        manifest = _read(args.input, MANIFEST_LIMITS[args.kind])
        validate_v1_manifest(args.kind, manifest)
        content = sign_manifest(key, args.kind, manifest)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        name = None
        try:
            with tempfile.NamedTemporaryFile(dir=args.output.parent, delete=False) as target:
                name = target.name
                target.write(content)
            os.replace(name, args.output)
        finally:
            if name is not None and os.path.exists(name):
                os.unlink(name)
    else:
        if args.output is not None:
            parser.error("verify does not write an output")
        key = _key(_read(args.key, 8192), False)
        verify_manifest(key, args.kind, _read(args.input, ENVELOPE_LIMIT))
        print("Signature verified")


if __name__ == "__main__":
    main()

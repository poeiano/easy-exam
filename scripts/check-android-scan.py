"""Check actual DEX class definitions, not strings that merely reference missing classes."""
import argparse
import json
import struct
import zipfile
from pathlib import Path


def definitions(data):
    def u32(offset):
        return struct.unpack_from('<I', data, offset)[0]

    string_ids = u32(0x3c)
    type_ids = u32(0x44)
    count, offset = u32(0x60), u32(0x64)
    result = set()
    for i in range(count):
        type_index = u32(offset + i * 32)
        string_index = u32(type_ids + type_index * 4)
        start = u32(string_ids + string_index * 4)
        while data[start] & 0x80:
            start += 1
        start += 1
        result.add(data[start:data.index(b'\x00', start)].decode('utf-8', errors='replace'))
    return result


parser = argparse.ArgumentParser()
parser.add_argument('apk', type=Path)
args = parser.parse_args()
classes = set()
with zipfile.ZipFile(args.apk) as archive:
    for name in archive.namelist():
        if name.endswith('.dex'):
            classes.update(definitions(archive.read(name)))
required = [
    'Lcom/google/zxing/integration/android/IntentIntegrator;',
    'Lcom/journeyapps/barcodescanner/CaptureActivity;',
    'Lcom/journeyapps/barcodescanner/CaptureManager;',
    'Landroidx/core/content/ContextCompat;',
    'Landroidx/core/app/ActivityCompat;',
    'Landroidx/fragment/app/Fragment;',
]
missing = [name for name in required if name not in classes]
print(json.dumps({'scanner_classes_present': not missing, 'missing_classes': missing}))
raise SystemExit(1 if missing else 0)

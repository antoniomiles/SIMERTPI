"""Offline QA sheet derived from the guarded SQL seed. Optional: qrcode==8.2 (BSD)."""
from pathlib import Path
import html
import re
import qrcode

root = Path(__file__).resolve().parents[2]
sql = (root / "src/main/resources/dev/pinas-parking.sql").read_text(encoding="utf-8")
zones = dict(re.findall(r"parking.zones.*?VALUES \('([^']+)','([^']+)'", sql))
streets = {identifier: (zone, name) for identifier, zone, name in re.findall(
    r"parking.streets.*?VALUES \('([^']+)','([^']+)','[^']+','([^']+)'", sql)}
spaces = re.findall(r"parking.parking_spaces.*?VALUES \('[^']+','([^']+)','([^']+)','([^']+)'", sql)
assert len(spaces) == 9 and len({qr for _, _, qr in spaces}) == 9
cards = []
for street_id, code, payload in spaces:
    assert payload.startswith("SIMERTPI-DEV-PIN-") and "://" not in payload
    qr = qrcode.QRCode(error_correction=qrcode.constants.ERROR_CORRECT_M, border=4)
    qr.add_data(payload)
    qr.make(fit=True)
    matrix = qr.get_matrix()
    size = len(matrix)
    modules = "".join(f'<rect x="{x}" y="{y}" width="1" height="1"/>'
                      for y, row in enumerate(matrix) for x, enabled in enumerate(row) if enabled)
    zone, street = streets[street_id]
    svg = (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {size} {size}" '
           f'role="img" aria-label="QR DEV {html.escape(code)}" shape-rendering="crispEdges">'
           f'<rect width="{size}" height="{size}" fill="white"/><g fill="black">{modules}</g></svg>')
    cards.append(f'<article><h2>{html.escape(code)}</h2><p>{html.escape(zones[zone])} · '
                 f'{html.escape(street)}</p>{svg}<p><code>{html.escape(payload)}</code></p>'
                 '<strong>DEV / NO OFICIAL</strong></article>')
document = ('<!doctype html><html lang="es"><meta charset="utf-8"><title>QR DEV Piñas</title>'
            '<style>body{font:16px sans-serif;margin:24px}main{display:grid;grid-template-columns:'
            'repeat(auto-fit,minmax(260px,1fr));gap:24px}article{text-align:center;break-inside:avoid}'
            'svg{width:220px;height:220px}code{overflow-wrap:anywhere}</style>'
            '<h1>SIMERTPI — QR de prueba DEV Piñas</h1><p>Espacios y QR simulados; no oficiales '
            'municipales. Abrir offline, mostrar en otra pantalla o imprimir. No contienen URLs.</p>'
            '<main>' + "".join(cards) + '</main></html>\n')
(root / "docs/dev-pinas-qrs.html").write_text(document, encoding="utf-8")
print("Generated nine offline DEV QR cards from the SQL dataset.")
